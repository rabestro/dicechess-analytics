package dicechess.analytics.api

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.time.OffsetDateTime
import java.util.UUID
import scala.util.Try

import cats.effect.IO
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import doobie.Transactor
import doobie.implicits.*
import io.circe.Json
import io.circe.parser.*
import io.circe.syntax.*
import org.http4s.*
import org.http4s.headers.{`Content-Type`, Location}

import dicechess.analytics.AppConfig
import dicechess.analytics.repository.{UserRepository, User}
import dicechess.analytics.api.Protocol.MeResponse

object AuthRoutes:

  private val httpClient = HttpClient.newHttpClient()
  private def exchangeGoogleCode(code: String, config: AppConfig): IO[Json] = IO.blocking {
    val params = s"code=$code" +
      s"&client_id=${config.googleClientId.getOrElse("")}" +
      s"&client_secret=${config.googleClientSecret.getOrElse("")}" +
      s"&redirect_uri=${config.googleRedirectUri.getOrElse("")}" +
      s"&grant_type=authorization_code"

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create("https://oauth2.googleapis.com/token"))
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(params))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      throw new Exception(s"Failed to exchange Google code: ${response.body()}")
    else parse(response.body()).getOrElse(Json.Null)
  }

  private def fetchGoogleProfile(accessToken: String): IO[Json] = IO.blocking {
    val request = HttpRequest
      .newBuilder()
      .uri(URI.create("https://openidconnect.googleapis.com/v1/userinfo"))
      .header("Authorization", s"Bearer $accessToken")
      .GET()
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      throw new Exception(s"Failed to fetch Google profile: ${response.body()}")
    else parse(response.body()).getOrElse(Json.Null)
  }

  def routes(xa: Transactor[IO], config: AppConfig): HttpRoutes[IO] =
    val secretKey = config.secretKey.getOrElse(
      if config.mockAuth then "temporary-secret-key-for-development"
      else throw new IllegalStateException("SECRET_KEY must be provided unless MOCK_AUTH=true")
    )
    val algorithm = Algorithm.HMAC256(secretKey)
    val verifier  = JWT.require(algorithm).build()

    HttpRoutes.of[IO] {
      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/login" =>
        if config.mockAuth then
          val targetUri = Uri.unsafeFromString(config.frontendUrl)
          IO.pure(Response[IO](status = Status.SeeOther).putHeaders(Location(targetUri)))
        else
          val clientId    = config.googleClientId.getOrElse("")
          val redirectUri = config.googleRedirectUri.getOrElse("")
          val googleUrl   = s"https://accounts.google.com/o/oauth2/v2/auth?" +
            s"client_id=$clientId&" +
            s"redirect_uri=$redirectUri&" +
            s"response_type=code&" +
            s"scope=openid%20email%20profile"
          val targetUri = Uri.unsafeFromString(googleUrl)
          IO.pure(Response[IO](status = Status.SeeOther).putHeaders(Location(targetUri)))

      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/callback" =>
        val codeOpt = req.uri.query.params.get("code")
        codeOpt match
          case None =>
            IO.pure(Response[IO](status = Status.BadRequest).withEntity("Missing code parameter"))
          case Some(code) =>
            val flow = for
              tokenJson   <- exchangeGoogleCode(code, config)
              accessToken <- IO.fromOption(tokenJson.hcursor.get[String]("access_token").toOption)(
                new Exception("access_token not found in Google response")
              )
              profileJson <- fetchGoogleProfile(accessToken)
              email       <- IO.fromOption(profileJson.hcursor.get[String]("email").toOption)(
                new Exception("email not found in Google profile")
              )
              name       = profileJson.hcursor.get[String]("name").toOption
              pictureUrl = profileJson.hcursor.get[String]("picture").toOption

              user <- UserRepository.getByEmail(email).transact(xa).flatMap {
                case Some(existing) =>
                  val updated = existing.copy(
                    lastLoginAt = Some(OffsetDateTime.now()),
                    name = name.orElse(existing.name),
                    pictureUrl = pictureUrl.orElse(existing.pictureUrl)
                  )
                  UserRepository
                    .updateLogin(
                      existing.id,
                      updated.lastLoginAt.get,
                      updated.name,
                      updated.pictureUrl
                    )
                    .transact(xa)
                    .map(_ => updated)
                case None =>
                  val isAdmin    = config.adminEmail.contains(email)
                  val role       = if isAdmin then "ADMIN" else "USER"
                  val isApproved = isAdmin
                  val newUser    = User(
                    id = UUID.randomUUID(),
                    email = email,
                    name = name,
                    pictureUrl = pictureUrl,
                    role = role,
                    isApproved = isApproved,
                    isActive = true,
                    lastLoginAt = Some(OffsetDateTime.now()),
                    createdAt = OffsetDateTime.now()
                  )
                  UserRepository.create(newUser).transact(xa).flatMap { _ =>
                    UserRepository.getByEmail(email).transact(xa).map(_.getOrElse(newUser))
                  }
              }

              expiresAt = new java.util.Date(System.currentTimeMillis() + 30L * 24L * 3600L * 1000L)
              token     = JWT
                .create()
                .withSubject(user.id.toString)
                .withClaim("role", user.role)
                .withExpiresAt(expiresAt)
                .sign(algorithm)

              cookie = ResponseCookie(
                name = "access_token",
                content = token,
                maxAge = Some(30L * 24L * 3600L),
                path = Some("/"),
                sameSite = Some(SameSite.Lax),
                httpOnly = true,
                secure = !config.mockAuth
              )

              targetUri = Uri.unsafeFromString(config.frontendUrl)
              response  = Response[IO](status = Status.SeeOther)
                .putHeaders(Location(targetUri))
                .addCookie(cookie)
            yield response

            flow.handleErrorWith { err =>
              IO.println(s"Callback error: ${err.getMessage}") *>
                IO.pure(
                  Response[IO](status = Status.InternalServerError)
                    .withEntity(s"Authentication callback failed: ${err.getMessage}")
                )
            }

      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/me" =>
        if config.mockAuth then
          val mockUser = MeResponse(
            id = UUID.fromString("00000000-0000-0000-0000-000000000000"),
            email = "dev@local",
            name = Some("Dev User"),
            pictureUrl = None,
            role = "ADMIN",
            isApproved = true,
            isActive = true
          )
          val jsonStr = mockUser.asJson.noSpaces
          IO.pure(
            Response[IO](status = Status.Ok)
              .withEntity(jsonStr)
              .withContentType(`Content-Type`(MediaType.application.json))
          )
        else
          val tokenOpt = req.cookies.find(_.name == "access_token").map(_.content)
          tokenOpt match
            case None =>
              val unauthorizedResponse = MeResponse(
                id = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                email = "",
                name = None,
                pictureUrl = None,
                role = "",
                isApproved = false,
                isActive = false
              ).asJson.noSpaces

              IO.pure(
                Response[IO](status = Status.Unauthorized)
                  .withEntity(unauthorizedResponse)
                  .withContentType(`Content-Type`(MediaType.application.json))
              )
            case Some(token) =>
              Try(verifier.verify(token)).toOption match
                case None =>
                  IO.pure(Response[IO](status = Status.Unauthorized).withEntity("Invalid token"))
                case Some(decoded) =>
                  val userIdStr = decoded.getSubject
                  val userIdTry = Try(UUID.fromString(userIdStr))
                  userIdTry.toOption match
                    case None =>
                      IO.pure(
                        Response[IO](status = Status.Unauthorized).withEntity("Invalid user ID")
                      )
                    case Some(userId) =>
                      UserRepository.get(userId).transact(xa).flatMap {
                        case None =>
                          IO.pure(
                            Response[IO](status = Status.Unauthorized).withEntity("User not found")
                          )
                        case Some(user) =>
                          val me = MeResponse(
                            id = user.id,
                            email = user.email,
                            name = user.name,
                            pictureUrl = user.pictureUrl,
                            role = user.role,
                            isApproved = user.isApproved,
                            isActive = user.isActive
                          )
                          val jsonStr = me.asJson.noSpaces
                          IO.pure(
                            Response[IO](status = Status.Ok)
                              .withEntity(jsonStr)
                              .withContentType(`Content-Type`(MediaType.application.json))
                          )
                      }

      case req if req.method == Method.POST && req.uri.path.renderString == "/api/auth/logout" =>
        val expiredCookie = ResponseCookie(
          name = "access_token",
          content = "",
          maxAge = Some(0L),
          path = Some("/"),
          sameSite = Some(SameSite.Lax),
          httpOnly = true,
          secure = !config.mockAuth
        )
        IO.pure(
          Response[IO](status = Status.Ok)
            .withEntity("""{"detail": "Logged out"}""")
            .withContentType(`Content-Type`(MediaType.application.json))
            .addCookie(expiredCookie)
        )
    }

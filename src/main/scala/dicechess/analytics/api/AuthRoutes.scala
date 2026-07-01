package dicechess.analytics.api

import java.net.URI
import java.net.http.{HttpClient, HttpRequest, HttpResponse}
import java.net.URLEncoder
import java.nio.charset.StandardCharsets.UTF_8
import java.security.MessageDigest
import java.security.SecureRandom
import java.security.interfaces.RSAPublicKey
import java.time.OffsetDateTime
import java.util.{Base64, Date, UUID}

import cats.effect.IO
import com.auth0.jwk.{JwkProvider, JwkProviderBuilder}
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import doobie.Transactor
import doobie.implicits.*
import io.circe.Json
import io.circe.parser.parse
import io.circe.syntax.*
import org.http4s.*
import org.http4s.headers.{`Content-Type`, Location}
import org.slf4j.LoggerFactory

import dicechess.analytics.AppConfig
import dicechess.analytics.repository.{User, UserRepository}
import dicechess.analytics.api.Protocol.MeResponse

/** Google OAuth2 login/callback plus the session `/me` and `/logout` endpoints.
  *
  * Security notes:
  *   - The login → callback round-trip is CSRF-protected with a random `state` stored in a
  *     short-lived cookie and checked (constant-time) on return.
  *   - Identity comes from Google's signed `id_token`, whose signature is verified against Google's
  *     published JWKS and whose issuer/audience are checked — we never trust unverified profile
  *     data. This is preferred over a second call to the userinfo endpoint.
  *   - Callback failures return a generic message to the client; the detail is logged server-side.
  */
object AuthRoutes:

  // Finite timeouts on every outbound Google call so a slow upstream can't stall the OAuth callback.
  private val HttpTimeout  = java.time.Duration.ofSeconds(10)
  private val logger       = LoggerFactory.getLogger(getClass)
  private val httpClient   = HttpClient.newBuilder().connectTimeout(HttpTimeout).build()
  private val secureRandom = new SecureRandom()

  private val GoogleAuthUrl  = "https://accounts.google.com/o/oauth2/v2/auth"
  private val GoogleTokenUrl = "https://oauth2.googleapis.com/token"
  private val GoogleCertsUrl = "https://www.googleapis.com/oauth2/v3/certs"
  private val GoogleIssuers  = List("https://accounts.google.com", "accounts.google.com")

  private val StateCookie   = "oauth_state"
  private val SessionCookie = "access_token"
  private val SessionTtl    = 30L * 24L * 3600L // 30 days, in seconds
  private val StateTtl      = 600L              // 10 minutes, in seconds

  /** Fetches and caches Google's public signing keys (JWKS). Thread-safe; built once. Bounded
    * connect/read timeouts so a slow JWKS fetch cannot hang the callback.
    */
  private val jwkProvider: JwkProvider =
    JwkProviderBuilder(URI.create(GoogleCertsUrl).toURL).timeouts(10000, 10000).build()

  private def enc(value: String): String = URLEncoder.encode(value, UTF_8)

  private def randomState(): String =
    val bytes = new Array[Byte](32)
    secureRandom.nextBytes(bytes)
    Base64.getUrlEncoder.withoutPadding.encodeToString(bytes)

  private def constantTimeEquals(a: String, b: String): Boolean =
    MessageDigest.isEqual(a.getBytes(UTF_8), b.getBytes(UTF_8))

  private def redirect(target: String): Response[IO] =
    Response[IO](status = Status.SeeOther).putHeaders(Location(Uri.unsafeFromString(target)))

  private def jsonResponse(status: Status, body: Json): Response[IO] =
    Response[IO](status = status)
      .withEntity(body.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))

  private def detail(message: String): Json = Json.obj("detail" -> Json.fromString(message))

  private def sessionCookie(token: String, config: AppConfig): ResponseCookie =
    ResponseCookie(
      name = SessionCookie,
      content = token,
      maxAge = Some(SessionTtl),
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      httpOnly = true,
      secure = !config.mockAuth
    )

  private def stateCookie(state: String, config: AppConfig): ResponseCookie =
    ResponseCookie(
      name = StateCookie,
      content = state,
      maxAge = Some(StateTtl),
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      httpOnly = true,
      secure = !config.mockAuth
    )

  private def expiredCookie(name: String, config: AppConfig): ResponseCookie =
    ResponseCookie(
      name = name,
      content = "",
      maxAge = Some(0L),
      path = Some("/"),
      sameSite = Some(SameSite.Lax),
      httpOnly = true,
      secure = !config.mockAuth
    )

  private final case class GoogleIdentity(
      email: String,
      name: Option[String],
      picture: Option[String]
  )

  /** Exchange the authorization `code` for Google's token response (containing the `id_token`). All
    * form fields are percent-encoded — Google codes/URLs contain characters that would otherwise
    * corrupt an `application/x-www-form-urlencoded` body.
    */
  private def exchangeGoogleCode(code: String, config: AppConfig): IO[Json] = IO.blocking {
    val form = List(
      "code"          -> code,
      "client_id"     -> config.googleClientId.getOrElse(""),
      "client_secret" -> config.googleClientSecret.getOrElse(""),
      "redirect_uri"  -> config.googleRedirectUri.getOrElse(""),
      "grant_type"    -> "authorization_code"
    ).map((k, v) => s"${enc(k)}=${enc(v)}").mkString("&")

    val request = HttpRequest
      .newBuilder()
      .uri(URI.create(GoogleTokenUrl))
      .timeout(HttpTimeout)
      .header("Content-Type", "application/x-www-form-urlencoded")
      .POST(HttpRequest.BodyPublishers.ofString(form))
      .build()

    val response = httpClient.send(request, HttpResponse.BodyHandlers.ofString())
    if response.statusCode() != 200 then
      throw new RuntimeException(s"Google token exchange returned HTTP ${response.statusCode()}")
    parse(response.body()).fold(err => throw err, identity)
  }

  /** Verify the Google `id_token`: RS256 signature against the matching JWKS key, plus issuer and
    * audience (our client id). Returns the verified identity or fails the effect.
    */
  private def verifyIdToken(idToken: String, clientId: String): IO[GoogleIdentity] = IO.blocking {
    val decoded   = JWT.decode(idToken)
    val publicKey = jwkProvider.get(decoded.getKeyId).getPublicKey.asInstanceOf[RSAPublicKey]
    val algorithm = Algorithm.RSA256(publicKey, null)
    val verified  = JWT
      .require(algorithm)
      .withIssuer(GoogleIssuers*)
      .withAudience(clientId)
      .build()
      .verify(idToken)

    val emailVerified =
      Option(verified.getClaim("email_verified").asBoolean()).exists(_.booleanValue())
    if !emailVerified then throw new RuntimeException("Google id_token email is not verified")
    Option(verified.getClaim("email").asString()) match
      case None =>
        throw new RuntimeException("Google id_token has no email claim")
      case Some(value) =>
        GoogleIdentity(
          email = value,
          name = Option(verified.getClaim("name").asString()),
          picture = Option(verified.getClaim("picture").asString())
        )
  }

  /** Create the user on first login, or refresh profile + last-login on return. If the email
    * matches `ADMIN_EMAIL` the user is (re)promoted to an approved admin even if the row already
    * existed as a plain user — this is the only admin bootstrap path. A single atomic upsert, so a
    * concurrent first login can never mint a session for a non-persisted id.
    */
  private def upsertOnLogin(
      xa: Transactor[IO],
      config: AppConfig,
      identity: GoogleIdentity
  ): IO[User] =
    val email        = identity.email.toLowerCase(java.util.Locale.ROOT)
    val isAdminEmail = config.adminEmail.exists(_.equalsIgnoreCase(email))
    val now          = OffsetDateTime.now()
    val candidate    = User(
      id = UUID.randomUUID(),
      email = email,
      name = identity.name,
      pictureUrl = identity.picture,
      role = if isAdminEmail then "ADMIN" else "USER",
      isApproved = isAdminEmail,
      isActive = true,
      lastLoginAt = Some(now),
      createdAt = now
    )
    UserRepository.upsert(candidate, isAdminEmail).transact(xa)

  private def signSession(algorithm: Algorithm, user: User): String =
    JWT
      .create()
      .withSubject(user.id.toString)
      .withClaim("role", user.role)
      .withExpiresAt(new Date(System.currentTimeMillis() + SessionTtl * 1000L))
      .sign(algorithm)

  private def meJson(user: User): Json =
    MeResponse(
      id = user.id,
      email = user.email,
      name = user.name,
      pictureUrl = user.pictureUrl,
      role = user.role,
      isApproved = user.isApproved,
      isActive = user.isActive
    ).asJson

  def routes(xa: Transactor[IO], config: AppConfig): HttpRoutes[IO] =
    val secret    = AuthSupport.resolveSecret(config.secretKey, config.mockAuth)
    val algorithm = AuthSupport.sessionAlgorithm(secret)
    val verifier  = AuthSupport.sessionVerifier(secret)

    HttpRoutes.of[IO] {
      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/login" =>
        if config.mockAuth then IO.pure(redirect(config.frontendUrl))
        else
          IO.delay(randomState()).map { state =>
            val url = s"$GoogleAuthUrl?client_id=${enc(config.googleClientId.getOrElse(""))}" +
              s"&redirect_uri=${enc(config.googleRedirectUri.getOrElse(""))}" +
              s"&response_type=code" +
              s"&scope=${enc("openid email profile")}" +
              s"&state=${enc(state)}"
            redirect(url).addCookie(stateCookie(state, config))
          }

      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/callback" =>
        val code       = req.uri.query.params.get("code")
        val state      = req.uri.query.params.get("state")
        val savedState = req.cookies.find(_.name == StateCookie).map(_.content)

        (code, state, savedState) match
          case (Some(c), Some(s), Some(saved)) if constantTimeEquals(s, saved) =>
            val flow = for
              tokenJson <- exchangeGoogleCode(c, config)
              idToken   <- IO.fromOption(tokenJson.hcursor.get[String]("id_token").toOption)(
                new RuntimeException("Google token response had no id_token")
              )
              identity <- verifyIdToken(idToken, config.googleClientId.getOrElse(""))
              user     <- upsertOnLogin(xa, config, identity)
            yield redirect(config.frontendUrl)
              .addCookie(sessionCookie(signSession(algorithm, user), config))
              .addCookie(expiredCookie(StateCookie, config))

            flow.handleErrorWith { err =>
              IO.delay(logger.warn("OAuth callback failed", err)) *>
                IO.pure(jsonResponse(Status.InternalServerError, detail("Authentication failed")))
            }
          case (None, _, _) =>
            IO.pure(jsonResponse(Status.BadRequest, detail("Missing authorization code")))
          case _ =>
            // Missing or mismatched state ⇒ possible CSRF; refuse and clear the stale cookie.
            IO.pure(
              jsonResponse(Status.BadRequest, detail("Invalid OAuth state"))
                .addCookie(expiredCookie(StateCookie, config))
            )

      case req if req.method == Method.GET && req.uri.path.renderString == "/api/auth/me" =>
        if config.mockAuth then
          IO.pure(
            jsonResponse(
              Status.Ok,
              MeResponse(
                id = UUID.fromString("00000000-0000-0000-0000-000000000000"),
                email = "dev@local",
                name = Some("Dev User"),
                pictureUrl = None,
                role = "ADMIN",
                isApproved = true,
                isActive = true
              ).asJson
            )
          )
        else
          req.cookies.find(_.name == SessionCookie).map(_.content) match
            case None => IO.pure(jsonResponse(Status.Unauthorized, detail("Not authenticated")))
            case Some(token) =>
              AuthSupport.userFromToken(xa, verifier, token).map {
                case Some(user) => jsonResponse(Status.Ok, meJson(user))
                case None       => jsonResponse(Status.Unauthorized, detail("Not authenticated"))
              }

      case req if req.method == Method.POST && req.uri.path.renderString == "/api/auth/logout" =>
        IO.pure(
          jsonResponse(Status.Ok, detail("Logged out"))
            .addCookie(expiredCookie(SessionCookie, config))
        )
    }

package dicechess.analytics.api

import java.util.UUID
import scala.util.Try

import cats.data.OptionT
import cats.effect.IO
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*
import org.http4s.headers.Authorization

import dicechess.analytics.repository.{UserRepository, User}

object AuthMiddleware:

  def apply(
      xa: Transactor[IO],
      secretKeyOpt: Option[String],
      mockAuth: Boolean
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    val secretKey = secretKeyOpt.getOrElse(
      if mockAuth then "temporary-secret-key-for-development"
      else throw new IllegalStateException("SECRET_KEY must be provided unless MOCK_AUTH=true")
    )
    val algorithm = Algorithm.HMAC256(secretKey)
    val verifier  = JWT.require(algorithm).build()

    HttpRoutes[IO] { req =>
      val path = req.uri.path.renderString

      // 1. Check if the path is explicitly public
      val isPublic = path == "/" ||
        path == "/version" ||
        path.startsWith("/docs") ||
        path.startsWith("/swagger-ui") ||
        path.startsWith("/api/auth/")

      if isPublic then routes(req)
      else
        // 2. Check if this is an ingest/curator write path carrying a Bearer token
        val hasBearer =
          req.headers.get[Authorization].exists(_.credentials.toString.startsWith("Bearer"))

        if hasBearer then
          // Delegate validation to the Tapir endpoint security logic
          routes(req)
        else
          // 3. Otherwise, cookie authentication is required
          if mockAuth then
            val mockEmail = "dev@local"
            val action    = for
              existing <- UserRepository.getByEmail(mockEmail)
              user     <- existing match
                case Some(u) => doobie.free.connection.pure(u)
                case None    =>
                  val newUser = User(
                    id = UUID.randomUUID(),
                    email = mockEmail,
                    name = Some("Dev User"),
                    pictureUrl = None,
                    role = "ADMIN",
                    isApproved = true,
                    isActive = true,
                    lastLoginAt = None,
                    createdAt = java.time.OffsetDateTime.now()
                  )
                  UserRepository.create(newUser).map(_ => newUser)
            yield user

            OptionT.liftF(action.transact(xa)).flatMap { user =>
              if !user.isApproved || !user.isActive then
                OptionT.pure[IO](Response[IO](status = Status.Forbidden))
              else routes(req)
            }
          else
            val tokenOpt = req.cookies.find(_.name == "access_token").map(_.content)

            tokenOpt match
              case None =>
                OptionT.pure[IO](Response[IO](status = Status.Unauthorized))
              case Some(token) =>
                Try(verifier.verify(token)).toOption match
                  case None =>
                    OptionT.pure[IO](Response[IO](status = Status.Unauthorized))
                  case Some(decoded) =>
                    val userIdStr = decoded.getSubject
                    val userIdTry = Try(UUID.fromString(userIdStr))

                    userIdTry.toOption match
                      case None =>
                        OptionT.pure[IO](Response[IO](status = Status.Unauthorized))
                      case Some(userId) =>
                        OptionT.liftF(UserRepository.get(userId).transact(xa)).flatMap {
                          case None =>
                            OptionT.pure[IO](Response[IO](status = Status.Unauthorized))
                          case Some(user) =>
                            if !user.isApproved || !user.isActive then
                              OptionT.pure[IO](Response[IO](status = Status.Forbidden))
                            else if path.startsWith("/api/admin/") && user.role != "ADMIN" then
                              OptionT.pure[IO](Response[IO](status = Status.Forbidden))
                            else routes(req)
                        }
    }

package dicechess.analytics.api

import java.time.OffsetDateTime
import java.util.UUID

import cats.data.OptionT
import cats.effect.IO
import doobie.Transactor
import doobie.implicits.*
import org.http4s.*

import dicechess.analytics.repository.{User, UserRepository}
import dicechess.analytics.api.AuthSupport.AuthError

/** The single authentication gate for the whole HTTP app.
  *
  * Policy (fail-closed — anything not explicitly allowed requires an approved session):
  *   1. Public paths (welcome, version, docs, the `/api/auth/` flow) pass through untouched.
  *   2. The machine-to-machine write endpoints pass through to their own bearer-token validation
  *      (Tapir `serverSecurityLogic`); these are the only endpoints reachable without a session
  *      cookie, and they validate their token downstream — so nothing unvalidated is exposed.
  *   3. Everything else requires a valid session cookie for an approved, active user. Endpoints
  *      under `/api/admin/` additionally require the `ADMIN` role.
  *
  * Unlike a per-endpoint scheme, a new endpoint that nobody remembered to protect still lands in
  * case 3 and is denied by default, rather than being silently public.
  */
object CookieAuthMiddleware:

  private val CookieName = "access_token"

  def apply(
      xa: Transactor[IO],
      secretKey: Option[String],
      mockAuth: Boolean
  )(routes: HttpRoutes[IO]): HttpRoutes[IO] =
    val verifier = AuthSupport.sessionVerifier(AuthSupport.resolveSecret(secretKey, mockAuth))

    val unauthorized = OptionT.pure[IO](Response[IO](Status.Unauthorized))
    val forbidden    = OptionT.pure[IO](Response[IO](Status.Forbidden))

    HttpRoutes[IO] { req =>
      val path                                        = req.uri.path.renderString
      val adminOnly                                   = path.startsWith("/api/admin/")
      def gate(user: User): OptionT[IO, Response[IO]] =
        if adminOnly && user.role != "ADMIN" then forbidden else routes(req)

      if isPublic(path) || isMachineWrite(req.method, path) then routes(req)
      else if mockAuth then OptionT.liftF(mockUser(xa)).flatMap(gate)
      else
        req.cookies.find(_.name == CookieName).map(_.content) match
          case None        => unauthorized
          case Some(token) =>
            OptionT.liftF(AuthSupport.authenticate(xa, verifier, token)).flatMap {
              case Left(AuthError.Unauthorized) => unauthorized
              case Left(AuthError.Forbidden)    => forbidden
              case Right(user)                  => gate(user)
            }
    }

  private def isPublic(path: String): Boolean =
    path == "/" ||
      path == "/version" ||
      path.startsWith("/docs") ||
      path.startsWith("/swagger-ui") ||
      path.startsWith("/api/auth/")

  /** Machine-to-machine write endpoints that carry their own bearer token (`INGEST_TOKEN` /
    * `CURATION_TOKEN`), validated by the Tapir security layer. A human browser never hits these; a
    * caller without the correct token is rejected downstream with 401.
    */
  private def isMachineWrite(method: Method, path: String): Boolean =
    (method == Method.POST && path == "/api/games") ||            // ingest a game
      (method == Method.PUT && path.startsWith("/api/games/")) || // replace a game
      ((method == Method.PUT || method == Method.DELETE) &&
        path == "/api/opening-book/favorites") // curate favorites

  /** In MOCK_AUTH mode, transparently provide a pre-approved admin so the API is usable without a
    * real Google login (local dev / tests).
    */
  private def mockUser(xa: Transactor[IO]): IO[User] =
    val email = "dev@local"
    UserRepository
      .getByEmail(email)
      .flatMap {
        case Some(user) => doobie.free.connection.pure(user)
        case None       =>
          val user = User(
            id = UUID.randomUUID(),
            email = email,
            name = Some("Dev User"),
            pictureUrl = None,
            role = "ADMIN",
            isApproved = true,
            isActive = true,
            lastLoginAt = None,
            createdAt = OffsetDateTime.now()
          )
          UserRepository.create(user).map(_ => user)
      }
      .transact(xa)

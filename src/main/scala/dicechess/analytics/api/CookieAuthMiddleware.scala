package dicechess.analytics.api

import java.time.OffsetDateTime
import java.util.UUID

import cats.data.OptionT
import cats.effect.IO
import doobie.Transactor
import doobie.implicits.*
import io.circe.Json
import org.http4s.*
import org.http4s.headers.`Content-Type`

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

    // Match the FastAPI-style `{"detail": ...}` error shape used by the rest of the API.
    def deny(status: Status, message: String): OptionT[IO, Response[IO]] =
      OptionT.pure[IO](
        Response[IO](status = status)
          .withEntity(Json.obj("detail" -> Json.fromString(message)).noSpaces)
          .withContentType(`Content-Type`(MediaType.application.json))
      )

    val unauthorized = deny(Status.Unauthorized, "Not authenticated")
    val forbidden    = deny(Status.Forbidden, "Forbidden")

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

  // Boundary-aware so an adjacent path (e.g. `/docsx`) can't accidentally fall into the allowlist.
  private def isPublic(path: String): Boolean =
    path == "/" ||
      path == "/version" ||
      path == "/docs" || path.startsWith("/docs/") ||
      path == "/swagger-ui" || path.startsWith("/swagger-ui/") ||
      path.startsWith("/api/auth/")

  /** Machine-to-machine write endpoints that carry their own bearer token (`INGEST_TOKEN` /
    * `CURATION_TOKEN`), validated by the Tapir security layer. A human browser never hits these; a
    * caller without the correct token is rejected downstream with 401. Matched precisely so a
    * future sibling route is not silently exempted from the cookie gate.
    */
  private def isMachineWrite(method: Method, path: String): Boolean =
    (method == Method.POST && path == "/api/games") ||     // ingest a game
      (method == Method.PUT && isGameReplacePath(path)) || // replace a game: PUT /api/games/{id}
      ((method == Method.PUT || method == Method.DELETE) &&
        path == "/api/opening-book/favorites") // curate favorites

  /** Exactly `/api/games/{id}` — one segment after the prefix, not a deeper sub-resource. */
  private def isGameReplacePath(path: String): Boolean =
    path.startsWith("/api/games/") && {
      val rest = path.stripPrefix("/api/games/")
      rest.nonEmpty && !rest.contains("/")
    }

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

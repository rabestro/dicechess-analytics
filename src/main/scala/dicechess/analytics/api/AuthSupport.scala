package dicechess.analytics.api

import java.util.UUID
import scala.util.Try

import cats.effect.IO
import com.auth0.jwt.JWT
import com.auth0.jwt.algorithms.Algorithm
import com.auth0.jwt.interfaces.JWTVerifier
import doobie.Transactor
import doobie.implicits.*

import dicechess.analytics.repository.{User, UserRepository}

/** Shared primitives for the session-cookie authentication used by the human-facing API.
  *
  * The session is a JWT (HMAC-signed with `SECRET_KEY`) carried in the `access_token` cookie. The
  * JWT only proves identity; approval/active/role are always re-read from the database on each
  * request so that revoking a user takes effect immediately (the token is never trusted for
  * authorization state). Both the auth middleware and the `/api/auth/me` endpoint go through here
  * to avoid duplicating the verify-then-load logic.
  */
object AuthSupport:

  /** Deterministic secret used only when MOCK_AUTH bypasses real authentication (dev/test). */
  private val MockSecret = "temporary-secret-key-for-development"

  /** Resolve the session-signing secret once. `AppConfig.load` already rejects a missing
    * `SECRET_KEY` unless `MOCK_AUTH=true`, so the throw is a defensive backstop, not a live path.
    */
  def resolveSecret(secretKey: Option[String], mockAuth: Boolean): String =
    secretKey.getOrElse(
      if mockAuth then MockSecret
      else throw new IllegalStateException("SECRET_KEY must be provided unless MOCK_AUTH=true")
    )

  def sessionAlgorithm(secret: String): Algorithm = Algorithm.HMAC256(secret)

  def sessionVerifier(secret: String): JWTVerifier = JWT.require(sessionAlgorithm(secret)).build()

  /** Verify the session JWT and extract the user id from its subject. `None` for any failure (bad
    * signature, expired, missing/malformed subject).
    */
  def subjectUuid(verifier: JWTVerifier, token: String): Option[UUID] =
    Try(verifier.verify(token)).toOption
      .flatMap(decoded => Option(decoded.getSubject))
      .flatMap(subject => Try(UUID.fromString(subject)).toOption)

  /** Load the user referenced by a session token, without enforcing approval/active (used by
    * `/api/auth/me`, which must report a logged-in-but-pending user).
    */
  def userFromToken(
      xa: Transactor[IO],
      verifier: JWTVerifier,
      token: String
  ): IO[Option[User]] =
    subjectUuid(verifier, token) match
      case None         => IO.pure(None)
      case Some(userId) => UserRepository.get(userId).transact(xa)

  /** Why a request failed cookie authentication. */
  enum AuthError:
    case Unauthorized, Forbidden

  /** Full gate for protected endpoints: valid session → existing user → approved and active.
    * `Unauthorized` when there is no usable identity; `Forbidden` when the user is known but not
    * (yet) allowed in.
    */
  def authenticate(
      xa: Transactor[IO],
      verifier: JWTVerifier,
      token: String
  ): IO[Either[AuthError, User]] =
    userFromToken(xa, verifier, token).map {
      case None       => Left(AuthError.Unauthorized)
      case Some(user) =>
        if !user.isApproved || !user.isActive then Left(AuthError.Forbidden)
        else Right(user)
    }

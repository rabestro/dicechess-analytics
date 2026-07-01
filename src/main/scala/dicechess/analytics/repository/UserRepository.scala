package dicechess.analytics.repository

import java.time.OffsetDateTime
import java.util.UUID

import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*

final case class User(
    id: UUID,
    email: String,
    name: Option[String],
    pictureUrl: Option[String],
    role: String,
    isApproved: Boolean,
    isActive: Boolean,
    lastLoginAt: Option[OffsetDateTime],
    createdAt: OffsetDateTime
)

object UserRepository:

  def get(id: UUID): ConnectionIO[Option[User]] =
    sql"""
      SELECT id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at
      FROM users
      WHERE id = $id
    """
      .query[User]
      .option

  def getByEmail(email: String): ConnectionIO[Option[User]] =
    sql"""
      SELECT id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at
      FROM users
      WHERE email = lower($email)
    """
      .query[User]
      .option

  /** Locks the current set of active, approved admins (`SELECT ... FOR UPDATE`) and returns their
    * ids. Callers that may demote/disable/delete an admin must go through this so concurrent
    * transactions serialize on those rows — otherwise two parallel demotions can each observe two
    * admins and both commit, leaving zero. Membership also tells the caller whether the target user
    * currently counts as an active admin at all.
    */
  def lockActiveAdmins(): ConnectionIO[List[UUID]] =
    sql"""
      SELECT id
      FROM users
      WHERE role = 'ADMIN' AND is_approved = TRUE AND is_active = TRUE
      FOR UPDATE
    """.query[UUID].to[List]

  def list(statusFilter: Option[String]): ConnectionIO[List[User]] =
    val base = fr"""
      SELECT id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at
      FROM users
    """
    val filter = statusFilter match
      case Some("pending")  => fr"WHERE is_approved = FALSE"
      case Some("approved") => fr"WHERE is_approved = TRUE"
      case Some("blocked")  => fr"WHERE is_active = FALSE"
      case Some("admins")   => fr"WHERE role = 'ADMIN'"
      case _                => fr""
    (base ++ filter ++ fr"ORDER BY created_at DESC")
      .query[User]
      .to[List]

  def create(user: User): ConnectionIO[Int] =
    sql"""
      INSERT INTO users (id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at)
      VALUES (${user.id}, ${user.email}, ${user.name}, ${user.pictureUrl}, ${user.role}, ${user.isApproved}, ${user.isActive}, ${user.lastLoginAt}, ${user.createdAt})
      ON CONFLICT (email) DO UPDATE SET last_login_at = EXCLUDED.last_login_at
    """.update.run

  /** Atomic create-or-refresh used by the login flow. Inserts a new user, or on an existing email
    * refreshes last-login and profile and (re)promotes to an approved admin when `isAdminEmail`.
    * `RETURNING` yields the row actually persisted, so a concurrent first login can never hand back
    * a client-generated id that was not the one stored (which would produce an unusable session).
    */
  def upsert(user: User, isAdminEmail: Boolean): ConnectionIO[User] =
    sql"""
      INSERT INTO users (id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at)
      VALUES (${user.id}, ${user.email}, ${user.name}, ${user.pictureUrl}, ${user.role}, ${user.isApproved}, ${user.isActive}, ${user.lastLoginAt}, ${user.createdAt})
      ON CONFLICT (email) DO UPDATE SET
        last_login_at = EXCLUDED.last_login_at,
        name          = COALESCE(EXCLUDED.name, users.name),
        picture_url   = COALESCE(EXCLUDED.picture_url, users.picture_url),
        role          = CASE WHEN $isAdminEmail THEN 'ADMIN' ELSE users.role END,
        is_approved   = CASE WHEN $isAdminEmail THEN TRUE ELSE users.is_approved END
      RETURNING id, email, name, picture_url, role, is_approved, is_active, last_login_at, created_at
    """.query[User].unique

  def updateApproval(id: UUID, isApproved: Boolean): ConnectionIO[Int] =
    sql"UPDATE users SET is_approved = $isApproved WHERE id = $id".update.run

  def updateActive(id: UUID, isActive: Boolean): ConnectionIO[Int] =
    sql"UPDATE users SET is_active = $isActive WHERE id = $id".update.run

  def updateRole(id: UUID, role: String): ConnectionIO[Int] =
    sql"UPDATE users SET role = $role WHERE id = $id".update.run

  def delete(id: UUID): ConnectionIO[Int] =
    sql"DELETE FROM users WHERE id = $id".update.run

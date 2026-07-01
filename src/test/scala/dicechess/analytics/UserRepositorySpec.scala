package dicechess.analytics

import java.time.OffsetDateTime
import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.repository.{UserRepository, User}

class UserRepositorySpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  override def afterContainersStart(pg: PostgreSQLContainer): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()

  private def xa(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  test("UserRepository lifecycle: create, get, list, update, delete") {
    withContainers { pg =>
      val transactor = xa(pg)
      val userId     = UUID.randomUUID()
      val user       = User(
        id = userId,
        email = "test@example.com",
        name = Some("Test User"),
        pictureUrl = Some("https://example.com/pic.jpg"),
        role = "USER",
        isApproved = false,
        isActive = true,
        lastLoginAt = None,
        createdAt = OffsetDateTime.now()
      )

      for
        // 1. Create User
        createResult <- UserRepository.create(user).transact(transactor)
        _ = assertEquals(createResult, 1)

        // 2. Get by Email
        byEmail <- UserRepository.getByEmail("test@example.com").transact(transactor)
        _ = assert(byEmail.isDefined)
        _ = assertEquals(byEmail.get.id, userId)
        _ = assertEquals(byEmail.get.name, Some("Test User"))

        // 3. Get by ID
        byId <- UserRepository.get(userId).transact(transactor)
        _ = assert(byId.isDefined)
        _ = assertEquals(byId.get.email, "test@example.com")

        // 4. List all/pending/approved/blocked
        allUsers <- UserRepository.list(None).transact(transactor)
        _ = assertEquals(allUsers.size, 1)
        pendingUsers <- UserRepository.list(Some("pending")).transact(transactor)
        _ = assertEquals(pendingUsers.size, 1)
        approvedUsers <- UserRepository.list(Some("approved")).transact(transactor)
        _ = assertEquals(approvedUsers.size, 0)

        // 5. Update Approval
        _                  <- UserRepository.updateApproval(userId, true).transact(transactor)
        approvedUsersAfter <- UserRepository.list(Some("approved")).transact(transactor)
        _ = assertEquals(approvedUsersAfter.size, 1)

        // 6. Update Role
        _          <- UserRepository.updateRole(userId, "ADMIN").transact(transactor)
        adminUsers <- UserRepository.list(Some("admins")).transact(transactor)
        _ = assertEquals(adminUsers.size, 1)

        // 7. Upsert on a repeat login: refreshes profile, keeps role/approval when not an admin
        // email, and RETURNS the already-persisted row (its id, not the freshly generated one).
        now = OffsetDateTime.now()
        returned <- UserRepository
          .upsert(
            User(
              id = UUID.randomUUID(),
              email = "test@example.com",
              name = Some("New Name"),
              pictureUrl = Some("new-pic.jpg"),
              role = "USER",
              isApproved = false,
              isActive = true,
              lastLoginAt = Some(now),
              createdAt = now
            ),
            isAdminEmail = false
          )
          .transact(transactor)
        _ = assertEquals(returned.id, userId)    // existing row, not the new random id
        _ = assertEquals(returned.name, Some("New Name"))
        _ = assertEquals(returned.pictureUrl, Some("new-pic.jpg"))
        _ = assertEquals(returned.role, "ADMIN") // not demoted by a non-admin upsert
        _ = assertEquals(returned.isApproved, true)

        // 8. Update Active Status
        _            <- UserRepository.updateActive(userId, false).transact(transactor)
        blockedUsers <- UserRepository.list(Some("blocked")).transact(transactor)
        _ = assertEquals(blockedUsers.size, 1)

        // 9. Delete
        deleteResult <- UserRepository.delete(userId).transact(transactor)
        _ = assertEquals(deleteResult, 1)
        deletedUser <- UserRepository.get(userId).transact(transactor)
        _ = assert(deletedUser.isEmpty)
      yield ()
    }
  }

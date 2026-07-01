package dicechess.analytics

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.*
import org.http4s.client.Client
import org.http4s.headers.Authorization
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.api.Routes
import dicechess.analytics.api.Protocol.VersionInfo

class AuthRoutesSpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  override def afterContainersStart(pg: PostgreSQLContainer): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()

  private def transactor(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  private val testVersion = VersionInfo("test", "test", "test")

  private def withClient[A](pg: PostgreSQLContainer, mockAuth: Boolean)(
      run: Client[IO] => IO[A]
  ): IO[A] =
    val config = AppConfig(
      db = DbConfig("", "", ""),
      host = com.comcast.ip4s.Host.fromString("0.0.0.0").get,
      port = com.comcast.ip4s.Port.fromInt(8000).get,
      corsOrigins = List("http://localhost:5173"),
      dbPoolSize = 1,
      ingestToken = None,
      curatorToken = None,
      secretKey = Some("test-secret-key"),
      googleClientId = None,
      googleClientSecret = None,
      googleRedirectUri = None,
      frontendUrl = "http://localhost:5173",
      adminEmail = Some("admin@example.com"),
      mockAuth = mockAuth
    )
    val app = Routes(transactor(pg), config, testVersion).httpApp
    run(Client.fromHttpApp(app))

  test("GET /api/auth/login redirects to Google OAuth") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        // Need to prevent client from following redirects to see the 303 status
        val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/login"))
        client.run(request).use { response =>
          assertEquals(response.status, Status.SeeOther)
          val redirectUrl =
            response.headers.get[org.http4s.headers.Location].map(_.uri.renderString).getOrElse("")
          assert(redirectUrl.startsWith("https://accounts.google.com/o/oauth2/v2/auth"))
          assert(redirectUrl.contains("response_type=code"))
          assert(redirectUrl.contains("scope="))
          assert(redirectUrl.contains("state="), "login must include a CSRF state parameter")
          // The same state must be planted as a cookie so the callback can verify it.
          assert(
            response.cookies.exists(_.name == "oauth_state"),
            "login must set the oauth_state cookie"
          )
          IO.unit
        }
      }
    }
  }

  test("GET /api/auth/me in mock mode returns mock admin user") {
    withContainers { pg =>
      withClient(pg, mockAuth = true) { client =>
        client.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/me"))).use { response =>
          assertEquals(response.status, Status.Ok)
          response.as[String].map { body =>
            val json = parse(body).getOrElse(Json.Null)
            assertEquals(json.hcursor.downField("email").as[String], Right("dev@local"))
            assertEquals(json.hcursor.downField("role").as[String], Right("ADMIN"))
            assertEquals(json.hcursor.downField("is_approved").as[Boolean], Right(true))
          }
        }
      }
    }
  }

  test("GET /api/auth/login in mock mode redirects to frontendUrl") {
    withContainers { pg =>
      withClient(pg, mockAuth = true) { client =>
        client.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/login"))).use {
          response =>
            assertEquals(response.status, Status.SeeOther)
            val loc = response.headers.get[org.http4s.headers.Location].map(_.uri.renderString)
            assertEquals(loc, Some("http://localhost:5173"))
            IO.unit
        }
      }
    }
  }

  test("POST /api/auth/logout clears access token cookie") {
    withContainers { pg =>
      withClient(pg, mockAuth = true) { client =>
        client.run(Request[IO](Method.POST, Uri.unsafeFromString("/api/auth/logout"))).use {
          response =>
            assertEquals(response.status, Status.Ok)
            val cookies     = response.cookies
            val tokenCookie = cookies.find(_.name == "access_token")
            assert(tokenCookie.isDefined)
            assertEquals(tokenCookie.get.content, "")
            assertEquals(tokenCookie.get.maxAge, Some(0L))
            IO.unit
        }
      }
    }
  }

  test("GET /api/auth/me in non-mock mode with missing cookie returns 401 Unauthorized") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        client.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/me"))).use { response =>
          assertEquals(response.status, Status.Unauthorized)
          response.as[String].map { body =>
            val json = parse(body).getOrElse(Json.Null)
            assertEquals(json.hcursor.downField("detail").as[String], Right("Not authenticated"))
          }
        }
      }
    }
  }

  test("GET /api/auth/me with invalid token returns 401") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/me"))
          .addCookie(RequestCookie("access_token", "invalid-token-value"))
        client.run(request).use { response =>
          assertEquals(response.status, Status.Unauthorized)
          IO.unit
        }
      }
    }
  }

  test("GET /api/admin/users in mock mode returns list of users for admin") {
    withContainers { pg =>
      withClient(pg, mockAuth = true) { client =>
        client.run(Request[IO](Method.GET, Uri.unsafeFromString("/api/admin/users"))).use {
          response =>
            assertEquals(response.status, Status.Ok)
            response.as[String].map { body =>
              val json = parse(body).getOrElse(Json.Null)
              assert(json.isArray)
            }
        }
      }
    }
  }

  test("GET /api/admin/users in non-mock mode for non-admin user returns 403 Forbidden") {
    withContainers { pg =>
      val t      = transactor(pg)
      val userId = java.util.UUID.randomUUID()
      // Seed the user in database first so that the auth gate can find them
      val user = dicechess.analytics.repository.User(
        id = userId,
        email = "user@example.com",
        name = Some("Normal User"),
        pictureUrl = None,
        role = "USER",
        isApproved = true,
        isActive = true,
        lastLoginAt = None,
        createdAt = java.time.OffsetDateTime.now()
      )

      for
        _   <- dicechess.analytics.repository.UserRepository.create(user).transact(t)
        res <- withClient(pg, mockAuth = false) { client =>
          val algorithm = com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret-key")
          val expiresAt = new java.util.Date(System.currentTimeMillis() + 3600 * 1000)
          val token     = com.auth0.jwt.JWT
            .create()
            .withSubject(userId.toString)
            .withClaim("role", "USER")
            .withExpiresAt(expiresAt)
            .sign(algorithm)

          val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/admin/users"))
            .addCookie(RequestCookie("access_token", token))

          client.run(request).use { response =>
            assertEquals(response.status, Status.Forbidden)
            IO.unit
          }
        }
      yield res
    }
  }

  test("GET /api/auth/me in non-mock mode with valid token returns user info") {
    withContainers { pg =>
      val t      = transactor(pg)
      val userId = java.util.UUID.randomUUID()
      val user   = dicechess.analytics.repository.User(
        id = userId,
        email = "member@example.com",
        name = Some("Member User"),
        pictureUrl = Some("http://pic"),
        role = "USER",
        isApproved = true,
        isActive = true,
        lastLoginAt = None,
        createdAt = java.time.OffsetDateTime.now()
      )

      for
        _   <- dicechess.analytics.repository.UserRepository.create(user).transact(t)
        res <- withClient(pg, mockAuth = false) { client =>
          val algorithm = com.auth0.jwt.algorithms.Algorithm.HMAC256("test-secret-key")
          val expiresAt = new java.util.Date(System.currentTimeMillis() + 3600 * 1000)
          val token     = com.auth0.jwt.JWT
            .create()
            .withSubject(userId.toString)
            .withClaim("role", "USER")
            .withExpiresAt(expiresAt)
            .sign(algorithm)

          val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/me"))
            .addCookie(RequestCookie("access_token", token))

          client.run(request).use { response =>
            assertEquals(response.status, Status.Ok)
            response.as[String].map { body =>
              val json = parse(body).getOrElse(Json.Null)
              assertEquals(json.hcursor.downField("email").as[String], Right("member@example.com"))
              assertEquals(json.hcursor.downField("role").as[String], Right("USER"))
              assertEquals(json.hcursor.downField("is_approved").as[Boolean], Right(true))
            }
          }
        }
      yield res
    }
  }

  test(
    "PATCH /api/admin/users/{id} updates user details and DELETE /api/admin/users/{id} deletes user"
  ) {
    withContainers { pg =>
      val t      = transactor(pg)
      val userId = java.util.UUID.randomUUID()
      val user   = dicechess.analytics.repository.User(
        id = userId,
        email = "target@example.com",
        name = Some("Target User"),
        pictureUrl = None,
        role = "USER",
        isApproved = false,
        isActive = true,
        lastLoginAt = None,
        createdAt = java.time.OffsetDateTime.now()
      )

      for
        _   <- dicechess.analytics.repository.UserRepository.create(user).transact(t)
        res <- withClient(pg, mockAuth = true) { client =>
          // 1. PATCH User
          val patchRequest =
            Request[IO](Method.PATCH, Uri.unsafeFromString(s"/api/admin/users/$userId"))
              .withEntity("""{"is_approved":true,"role":"ADMIN"}""")
              .withContentType(
                org.http4s.headers.`Content-Type`(org.http4s.MediaType.application.json)
              )

          client.run(patchRequest).use { patchResponse =>
            assertEquals(patchResponse.status, Status.Ok)

            // 2. DELETE User
            val deleteRequest =
              Request[IO](Method.DELETE, Uri.unsafeFromString(s"/api/admin/users/$userId"))
            client.run(deleteRequest).use { deleteResponse =>
              assertEquals(deleteResponse.status, Status.Ok)
              IO.unit
            }
          }
        }
      yield res
    }
  }

  // Regression: a bogus `Authorization: Bearer` header must NOT bypass the cookie gate. Read and
  // admin endpoints have no bearer validation of their own, so they must stay locked to 401.
  test("Bearer header does not bypass the cookie gate on read or admin endpoints") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        val bearer = Authorization(Credentials.Token(AuthScheme.Bearer, "totally-fake-token"))
        val admin  = Request[IO](Method.GET, Uri.unsafeFromString("/api/admin/users"))
          .putHeaders(bearer)
        val reads = Request[IO](Method.GET, Uri.unsafeFromString("/api/games"))
          .putHeaders(bearer)
        for
          adminStatus <- client.run(admin).use(r => IO.pure(r.status))
          readStatus  <- client.run(reads).use(r => IO.pure(r.status))
        yield
          assertEquals(adminStatus, Status.Unauthorized)
          assertEquals(readStatus, Status.Unauthorized)
      }
    }
  }

  test("GET /api/auth/callback rejects a missing or mismatched OAuth state") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        // No oauth_state cookie present, so the returned state cannot be validated → 400.
        val request = Request[IO](
          Method.GET,
          Uri.unsafeFromString("/api/auth/callback?code=abc&state=attacker-supplied")
        )
        client.run(request).use { response =>
          assertEquals(response.status, Status.BadRequest)
          IO.unit
        }
      }
    }
  }

  test("GET /api/auth/callback without a code returns 400") {
    withContainers { pg =>
      withClient(pg, mockAuth = false) { client =>
        val request = Request[IO](Method.GET, Uri.unsafeFromString("/api/auth/callback"))
        client.run(request).use { response =>
          assertEquals(response.status, Status.BadRequest)
          IO.unit
        }
      }
    }
  }

package dicechess.analytics

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.Transactor
import io.circe.Json
import io.circe.parser.decode
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.api.Protocol.VersionInfo
import dicechess.analytics.api.Routes

/** End-to-end tests for GET/PUT/DELETE /api/opening-book/favorites. */
class FavoritesEndpointSpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  override def afterContainersStart(pg: PostgreSQLContainer): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()

  private val curatorToken = "test-curator-token"
  private val testVersion  = VersionInfo("test", "test", "test")

  private def transactor(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  private def withClient[A](pg: PostgreSQLContainer)(run: Client[IO] => IO[A]): IO[A] =
    val config = AppConfig(
      db = DbConfig("", "", ""),
      host = com.comcast.ip4s.Host.fromString("0.0.0.0").get,
      port = com.comcast.ip4s.Port.fromInt(8000).get,
      corsOrigins = List("http://localhost:5173"),
      dbPoolSize = 1,
      ingestToken = None,
      curatorToken = Some(curatorToken),
      secretKey = Some("test-secret-key"),
      googleClientId = None,
      googleClientSecret = None,
      googleRedirectUri = None,
      frontendUrl = "http://localhost:5173",
      adminEmail = None,
      mockAuth = true
    )
    val app = Routes(transactor(pg), config, testVersion).httpApp
    run(Client.fromHttpApp(app))

  private val startFen     = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val favoritesUri = "/api/opening-book/favorites"

  private def bearerHeader(token: String) =
    Authorization(Credentials.Token(AuthScheme.Bearer, token))

  private def putBody(fen: String, dice: String, moves: List[String]): String =
    s"""{"fen":"$fen","dice":"$dice","moves":${moves
        .map(m => s""""$m"""")
        .mkString("[", ",", "]")}}"""

  private def deleteBody(fen: String, dice: String): String =
    s"""{"fen":"$fen","dice":"$dice"}"""

  private def putReq(body: String, bearer: Option[String]): Request[IO] =
    val base = Request[IO](Method.PUT, Uri.unsafeFromString(favoritesUri))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
    bearer.fold(base)(t => base.putHeaders(bearerHeader(t)))

  private def deleteReq(body: String, bearer: Option[String]): Request[IO] =
    val base = Request[IO](Method.DELETE, Uri.unsafeFromString(favoritesUri))
      .withEntity(body)
      .withContentType(`Content-Type`(MediaType.application.json))
    bearer.fold(base)(t => base.putHeaders(bearerHeader(t)))

  private def getReq(fen: String): Request[IO] =
    Request[IO](Method.GET, Uri.unsafeFromString(s"$favoritesUri?fen=${Uri.encode(fen)}"))

  test("PUT /api/opening-book/favorites returns 401 without token"):
    withContainers { pg =>
      withClient(pg) { client =>
        client
          .run(putReq(putBody(startFen, "BPR", List("e2e4")), bearer = None))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.Unauthorized))
      }
    }

  test("PUT /api/opening-book/favorites returns 401 with wrong token"):
    withContainers { pg =>
      withClient(pg) { client =>
        client
          .run(putReq(putBody(startFen, "BPR", List("e2e4")), bearer = Some("wrong")))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.Unauthorized))
      }
    }

  test("PUT /api/opening-book/favorites returns 400 on empty moves"):
    withContainers { pg =>
      withClient(pg) { client =>
        client
          .run(putReq(putBody(startFen, "BPR", Nil), bearer = Some(curatorToken)))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.BadRequest))
      }
    }

  test("PUT /api/opening-book/favorites returns 400 on empty dice"):
    withContainers { pg =>
      withClient(pg) { client =>
        client
          .run(putReq(putBody(startFen, "", List("e2e4")), bearer = Some(curatorToken)))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.BadRequest))
      }
    }

  test("PUT then GET returns the stored favorite"):
    withContainers { pg =>
      withClient(pg) { client =>
        for
          putStatus <- client
            .run(
              putReq(putBody(startFen, "BPR", List("e2e4", "f1c4")), bearer = Some(curatorToken))
            )
            .use(r => IO.pure(r.status))
          getBody <- client.run(getReq(startFen)).use(r => r.bodyText.compile.string)
          json    <- IO.fromEither(decode[Json](getBody))
        yield
          assertEquals(putStatus, Status.Ok)
          val items = json.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
          assertEquals(items.size, 1)
          val moves = items.head.hcursor.downField("moves").as[List[String]].getOrElse(Nil)
          assertEquals(moves, List("e2e4", "f1c4"))
      }
    }

  test("DELETE /api/opening-book/favorites returns 401 without token"):
    withContainers { pg =>
      withClient(pg) { client =>
        client
          .run(deleteReq(deleteBody(startFen, "BPR"), bearer = None))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.Unauthorized))
      }
    }

  test("DELETE returns 404 for a missing key"):
    withContainers { pg =>
      withClient(pg) { client =>
        // QQR is never inserted in this suite — safe unique combo
        client
          .run(deleteReq(deleteBody(startFen, "QQR"), bearer = Some(curatorToken)))
          .use(r => IO.pure(r.status))
          .map(assertEquals(_, Status.NotFound))
      }
    }

  test("DELETE returns 204 after a successful delete"):
    withContainers { pg =>
      withClient(pg) { client =>
        // BNN is a unique combo to avoid collision with the PUT-then-GET test (BPR)
        for
          _ <- client
            .run(putReq(putBody(startFen, "BNN", List("e2e4")), bearer = Some(curatorToken)))
            .use(_ => IO.unit)
          delStatus <- client
            .run(deleteReq(deleteBody(startFen, "BNN"), bearer = Some(curatorToken)))
            .use(r => IO.pure(r.status))
          getBody <- client.run(getReq(startFen)).use(r => r.bodyText.compile.string)
          json    <- IO.fromEither(decode[Json](getBody))
        yield
          assertEquals(delStatus, Status.NoContent)
          val items = json.hcursor.downField("items").as[List[Json]].getOrElse(Nil)
          assert(!items.exists(_.hcursor.downField("dice_sorted").as[String].contains("BNN")))
      }
    }

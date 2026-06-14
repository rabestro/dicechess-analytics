package dicechess.analytics

import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.Transactor
import io.circe.syntax.*
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.headers.{Authorization, `Content-Type`}
import org.http4s.{AuthScheme, Credentials, MediaType, Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.api.IngestProtocol.*
import dicechess.analytics.api.Routes

/** End-to-end tests for `POST /api/games` (auth + replay + persist) via the full http4s app. */
class IngestEndpointSpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  override def afterContainersStart(pg: PostgreSQLContainer): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()

  private val token = "test-ingest-token"

  private def transactor(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  private def withClient[A](pg: PostgreSQLContainer)(run: Client[IO] => IO[A]): IO[A] =
    val app = Routes(transactor(pg), List("http://localhost:5173"), Some(token)).httpApp
    run(Client.fromHttpApp(app))

  private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def game(id: UUID, moves: List[String]): GameIngest =
    GameIngest(
      id = id,
      source = "test",
      mode = "classic",
      result = Some(1),
      termination = Some("king_captured"),
      startedAt = None,
      timeInitialSec = Some(300),
      timeIncrementSec = Some(5),
      initialStakeAmount = None,
      finalStakeAmount = None,
      whiteMoneyDelta = None,
      blackMoneyDelta = None,
      stakeCurrency = None,
      whitePlayer = Some(PlayerInput("ext-w", Some("alice"), Some("human"), Some(1500))),
      blackPlayer = Some(PlayerInput("ext-b", Some("bob"), Some("bot"), Some(1480))),
      initialFen = start,
      turns = List(TurnInputDto(1, "w", List(1, 2, 5), moves, Some(3500), None)),
      events = Nil
    )

  private def postStatus(client: Client[IO], body: GameIngest, bearer: Option[String]): IO[Status] =
    val base = Request[IO](Method.POST, Uri.unsafeFromString("/api/games"))
      .withEntity(body.asJson.noSpaces)
      .withContentType(`Content-Type`(MediaType.application.json))
    val req = bearer.fold(base)(t =>
      base.putHeaders(Authorization(Credentials.Token(AuthScheme.Bearer, t)))
    )
    client.run(req).use(response => IO.pure(response.status))

  private val opening = List("b1c3", "e2e4", "d1f3")

  test("POST /api/games returns 201 on a valid game and 200 on re-ingest"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000b1")
    withContainers { pg =>
      withClient(pg) { client =>
        for
          first  <- postStatus(client, game(id, opening), Some(token))
          second <- postStatus(client, game(id, opening), Some(token))
        yield
          assertEquals(first, Status.Created)
          assertEquals(second, Status.Ok)
      }
    }

  test("POST /api/games returns 422 for an illegal move"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000b2")
    withContainers { pg =>
      withClient(pg) { client =>
        postStatus(client, game(id, List("a1a4")), Some(token))
          .map(status => assertEquals(status, Status.UnprocessableEntity))
      }
    }

  test("POST /api/games returns 401 without a token or with a wrong one"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000b3")
    withContainers { pg =>
      withClient(pg) { client =>
        for
          missing <- postStatus(client, game(id, opening), None)
          wrong   <- postStatus(client, game(id, opening), Some("nope"))
        yield
          assertEquals(missing, Status.Unauthorized)
          assertEquals(wrong, Status.Unauthorized)
      }
    }

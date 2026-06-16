package dicechess.analytics

import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.api.IngestProtocol.*
import dicechess.analytics.ingest.{GameReplay, ReplayedGame, TurnInput}
import dicechess.analytics.repository.{GamesRepository, IngestRepository}

/** `IngestRepository.persist` against a fresh PostgreSQL (testcontainers). */
class IngestRepositorySpec extends CatsEffectSuite with TestContainerForAll:

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

  private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def request(id: UUID): GameIngest =
    GameIngest(
      id = id,
      source = "test",
      mode = "x2",
      result = Some(1),
      termination = Some("king_captured"),
      startedAt = None,
      timeInitialSec = Some(300),
      timeIncrementSec = Some(5),
      initialStakeAmount = Some(500),
      finalStakeAmount = Some(1000),
      whiteMoneyDelta = Some(BigDecimal("12.5")),
      blackMoneyDelta = Some(BigDecimal("-12.5")),
      stakeCurrency = Some("GOLD"),
      whitePlayer = Some(PlayerInput("ext-w", Some("alice"), Some("human"), Some(1500))),
      blackPlayer = Some(PlayerInput("ext-b", Some("bob"), Some("bot"), Some(1480))),
      initialFen = start,
      turns =
        List(TurnInputDto(1, "w", List(2, 1, 5), List("b1c3", "e2e4", "d1f3"), Some(3500), None)),
      events = List(
        GameEventInput(
          1,
          Some(1),
          "DOUBLE_OFFER",
          Some("w"),
          Some(170000),
          Some(165000),
          Some(Json.obj("bank" -> Json.fromInt(100)))
        )
      )
    )

  private def replayedFor(req: GameIngest) =
    GameReplay
      .replay(req.initialFen, req.turns.map(t => TurnInput(t.dice, t.moves)), req.termination)
      .toOption
      .get

  test("persists a validated game and reads it back through the detail query"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000a1")
    withContainers { pg =>
      val t   = xa(pg)
      val req = request(id)
      for
        _      <- IngestRepository.persist(req, replayedFor(req)).transact(t)
        detail <- GamesRepository.detail(id).transact(t)
      yield detail match
        case None    => fail("expected the game to be persisted")
        case Some(d) =>
          assertEquals(d.mode, "x2")
          assertEquals(d.result, Some(1))
          assertEquals(d.whiteRating, Some(1500))
          assertEquals(d.whitePlayer.flatMap(_.username), Some("alice"))
          assertEquals(d.blackPlayer.flatMap(_.username), Some("bob"))
          assertEquals(d.turns.size, 1)
          val turn = d.turns.head
          assertEquals(turn.diceSorted, "125") // dice [2,1,5] stored sorted
          assertEquals(turn.playedMoves, Some(List("b1c3", "e2e4", "d1f3")))
          assertEquals(
            turn.positionFen.map(_.split(" ")(0)),
            Some("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
          )
          assertEquals(d.events.size, 1)
          assertEquals(d.events.head.eventType, "DOUBLE_OFFER")
          assertEquals(
            d.events.head.payload.flatMap(_.hcursor.get[Int]("bank").toOption),
            Some(100)
          )
    }

  test("rejects a turns / replayed-turns size mismatch"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000a3")
    withContainers { pg =>
      val mismatched = ReplayedGame(start, Nil) // 0 replayed turns vs 1 request turn
      IngestRepository.persist(request(id), mismatched).transact(xa(pg)).attempt.map { outcome =>
        assert(outcome.isLeft, s"expected a failure on size mismatch, got $outcome")
      }
    }

  test("is idempotent on the game id"):
    val id = UUID.fromString("00000000-0000-0000-0000-0000000000a2")
    withContainers { pg =>
      val t   = xa(pg)
      val req = request(id)
      for
        _     <- IngestRepository.persist(req, replayedFor(req)).transact(t)
        _     <- IngestRepository.persist(req, replayedFor(req)).transact(t) // no-op
        games <- sql"SELECT count(*) FROM games WHERE id = $id".query[Int].unique.transact(t)
        turns <- sql"SELECT count(*) FROM turns WHERE game_id = $id".query[Int].unique.transact(t)
      yield
        assertEquals(games, 1)
        assertEquals(turns, 1)
    }

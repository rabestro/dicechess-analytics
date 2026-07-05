package dicechess.analytics

import java.util.UUID

import cats.effect.IO
import cats.syntax.all.*
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.maintenance.ExportTrainingDataApp
import dicechess.analytics.repository.TrainingExportRepository.TrainingRow
import dicechess.analytics.repository.{PositionsRepository, TrainingExportRepository}

/** `TrainingExportRepository` streaming/filters against a fresh PostgreSQL, plus the CSV shape. */
class TrainingExportSpec extends CatsEffectSuite with TestContainerForAll:

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

  // Board states: start, after a white move, a terminal capture (black king gone, side
  // unflipped — legacy style), and a partial turn (both kings, side unflipped).
  private val fenStart   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val fenAfterW  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1"
  private val fenNoKing  = "rnbq1bnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq - 0 2"
  private val fenPartial = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR w KQkq - 0 1"

  private def newPlayer(playerType: String): ConnectionIO[UUID] =
    val id = UUID.randomUUID()
    sql"""INSERT INTO players (id, external_id, username, player_type)
          VALUES ($id, ${id.toString}, ${"p-" + playerType}, $playerType)""".update.run.as(id)

  private def newGame(
      result: Option[Int],
      rating: Option[Int],
      white: Option[UUID],
      black: Option[UUID]
  ): ConnectionIO[UUID] =
    val id = UUID.randomUUID()
    sql"""INSERT INTO games (id, source, mode, result, started_at,
                             white_rating, black_rating, white_player_id, black_player_id)
          VALUES ($id, 'test', 'classic'::game_mode_enum, $result, now(),
                  $rating, $rating, $white, $black)""".update.run.as(id)

  private def addTurn(
      game: UUID,
      n: Int,
      color: String,
      dice: String,
      moves: List[String],
      position: Long,
      after: Long
  ): ConnectionIO[Unit] =
    sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                             dice_sorted, played_moves, position_after_id)
          VALUES ($game, $n, $color, $position, $dice, $moves, $after)""".update.run.void

  test("rows/counts apply the explorer guards, keep passes, and join player metadata"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          pStart   <- PositionsRepository.getOrCreate(fenStart)
          pAfterW  <- PositionsRepository.getOrCreate(fenAfterW)
          pNoKing  <- PositionsRepository.getOrCreate(fenNoKing)
          pPartial <- PositionsRepository.getOrCreate(fenPartial)
          human    <- newPlayer("human")
          bot      <- newPlayer("bot")
          // Decided, strong, with player rows: a move, a pass, and a terminal capture.
          decided <- newGame(Some(1), Some(2200), Some(human), Some(bot))
          _       <- addTurn(decided, 1, "w", "PPQ", List("e2e4", "d2d4"), pStart, pAfterW)
          _       <- addTurn(decided, 2, "b", "bnp", Nil, pAfterW, pStart) // legal pass: side flips
          _       <- addTurn(decided, 3, "w", "QQR", List("h5f7", "f7e8"), pStart, pNoKing)
          // Unfinished game: result IS NULL — excluded entirely.
          unfinished <- newGame(None, Some(2200), None, None)
          _          <- addTurn(unfinished, 1, "w", "PPP", List("e2e4"), pStart, pAfterW)
          // Weak game without player rows: kept only when no rating floor is set.
          weak <- newGame(Some(-1), Some(1200), None, None)
          _    <- addTurn(weak, 1, "w", "NNP", List("g1f3"), pStart, pAfterW)
          // Junk: a no-op self-loop and an abandoned partial turn — always excluded.
          _ <- addTurn(decided, 4, "b", "ppp", Nil, pAfterW, pAfterW)
          _ <- addTurn(weak, 2, "w", "PPP", List("d2d4"), pStart, pPartial)
        yield ()

      for
        _        <- seed.transact(t)
        all      <- TrainingExportRepository.rows(None).transact(t).compile.toList
        strong   <- TrainingExportRepository.rows(Some(2000)).transact(t).compile.toList
        countAll <- TrainingExportRepository.counts(None).transact(t)
        countStr <- TrainingExportRepository.counts(Some(2000)).transact(t)
      yield
        assertEquals(all.size, 4)
        assertEquals(countAll, (4L, 2L))
        assertEquals(strong.size, 3)
        assertEquals(countStr, (3L, 1L))

        val move = all.find(r => r.turnNumber == 1 && r.dice == "PPQ").get
        assertEquals(move.fen, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -")
        assertEquals(move.side, "w")
        assertEquals(move.moves, "e2e4 d2d4")
        assertEquals(move.result, 1)
        assertEquals(move.termination, "unknown")
        assertEquals(move.mode, "classic")
        assertEquals(move.source, "test")
        assertEquals(move.whiteRating, Some(2200))
        assertEquals(move.whiteType, Some("human"))
        assertEquals(move.blackType, Some("bot"))

        val pass = all.find(_.dice == "bnp").get
        assertEquals(pass.moves, "") // forced pass survives with an empty move list

        val terminal = all.find(_.dice == "QQR").get
        assertEquals(terminal.moves, "h5f7 f7e8") // unflipped king-capture kept

        val weakRow = all.find(_.dice == "NNP").get
        assertEquals(weakRow.whiteType, None) // LEFT JOIN: no player rows
        assert(strong.forall(_.whiteRating == Some(2200)))
    }

  test("csvLine renders 14 columns, quotes only when needed, and blanks missing optionals"):
    val row = TrainingRow(
      gameId = UUID.fromString("00000000-0000-0000-0000-000000000001"),
      turnNumber = 7,
      fen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
      dice = "PPQ",
      side = "w",
      moves = "e2e4 d2d4",
      result = -1,
      termination = "king_captured",
      mode = "classic",
      source = "dicechess.com",
      whiteRating = Some(2394),
      blackRating = None,
      whiteType = Some("human"),
      blackType = None
    )
    val line = ExportTrainingDataApp.csvLine(row)
    assertEquals(line.split(",", -1).length, ExportTrainingDataApp.Header.split(",", -1).length)
    assertEquals(
      line,
      "00000000-0000-0000-0000-000000000001,7,rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -," +
        "PPQ,w,e2e4 d2d4,-1,king_captured,classic,dicechess.com,2394,,human,"
    )
    // A field containing a comma or quote is quoted with doubled inner quotes (RFC 4180).
    val tricky = ExportTrainingDataApp.csvLine(row.copy(source = "a,\"b\""))
    assert(tricky.contains("\"a,\"\"b\"\"\""))

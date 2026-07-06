package dicechess.analytics

import java.io.{BufferedWriter, StringWriter}
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
import dicechess.analytics.repository.TrainingExportRepository.{Filters, TrainingRow}
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
      whiteRating: Option[Int],
      blackRating: Option[Int],
      white: Option[UUID],
      black: Option[UUID],
      mode: String = "classic",
      termination: String = "unknown"
  ): ConnectionIO[UUID] =
    val id = UUID.randomUUID()
    sql"""INSERT INTO games (id, source, mode, termination, result, started_at,
                             white_rating, black_rating, white_player_id, black_player_id)
          VALUES ($id, 'test', $mode::game_mode_enum, $termination::game_termination_enum, $result,
                  now(), $whiteRating, $blackRating, $white, $black)""".update.run.as(id)

  private def addTurn(
      game: UUID,
      n: Int,
      color: String,
      dice: String,
      moves: Option[List[String]],
      position: Long,
      after: Long
  ): ConnectionIO[Unit] =
    sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                             dice_sorted, played_moves, position_after_id)
          VALUES ($game, $n, $color, $position, $dice, $moves, $after)""".update.run.void

  // TestContainerForAll shares one Postgres for the whole class; reset before each test so one
  // test's seed data can't leak into another's assertions.
  private val resetTables: ConnectionIO[Unit] =
    sql"TRUNCATE turns, games, positions, players RESTART IDENTITY CASCADE".update.run.void

  test("rows/counts apply the explorer guards, keep passes, and join player metadata"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _        <- resetTables
          pStart   <- PositionsRepository.getOrCreate(fenStart)
          pAfterW  <- PositionsRepository.getOrCreate(fenAfterW)
          pNoKing  <- PositionsRepository.getOrCreate(fenNoKing)
          pPartial <- PositionsRepository.getOrCreate(fenPartial)
          human    <- newPlayer("human")
          bot      <- newPlayer("bot")
          // Decided, strong, with player rows: a move, a pass, and a terminal capture.
          decided <- newGame(Some(1), Some(2200), Some(2200), Some(human), Some(bot))
          _       <- addTurn(decided, 1, "w", "PPQ", Some(List("e2e4", "d2d4")), pStart, pAfterW)
          _ <- addTurn(decided, 2, "b", "bnp", Some(Nil), pAfterW, pStart) // legal pass: side flips
          _ <- addTurn(decided, 3, "w", "QQR", Some(List("h5f7", "f7e8")), pStart, pNoKing)
          // NULL played_moves (nullable column) must export as an empty move list, not crash.
          _ <- addTurn(decided, 4, "b", "brr", None, pAfterW, pStart)
          // Unfinished game: result IS NULL — excluded entirely.
          unfinished <- newGame(None, Some(2200), Some(2200), None, None)
          _          <- addTurn(unfinished, 1, "w", "PPP", Some(List("e2e4")), pStart, pAfterW)
          // Weak game without player rows: kept only when no rating floor is set.
          weak <- newGame(Some(-1), Some(1200), Some(1200), None, None)
          _    <- addTurn(weak, 1, "w", "NNP", Some(List("g1f3")), pStart, pAfterW)
          // Asymmetric ratings: one side qualifies, the other does not — the floor needs BOTH.
          lopsided <- newGame(Some(1), Some(2500), Some(1000), None, None)
          _        <- addTurn(lopsided, 1, "w", "BBP", Some(List("f1c4")), pStart, pAfterW)
          // Junk: a no-op self-loop and an abandoned partial turn — always excluded.
          _ <- addTurn(decided, 5, "b", "ppp", Some(Nil), pAfterW, pAfterW)
          _ <- addTurn(weak, 2, "w", "PPP", Some(List("d2d4")), pStart, pPartial)
        yield ()

      for
        _      <- seed.transact(t)
        all    <- TrainingExportRepository.rows(Filters()).transact(t).compile.toList
        strong <- TrainingExportRepository
          .rows(Filters(minRating = Some(2000)))
          .transact(t)
          .compile
          .toList
        countAll <- TrainingExportRepository.counts(Filters()).transact(t)
        countStr <- TrainingExportRepository.counts(Filters(minRating = Some(2000))).transact(t)
      yield
        assertEquals(all.size, 6)
        assertEquals(countAll, (6L, 3L))
        assertEquals(strong.size, 4)
        assertEquals(countStr, (4L, 1L))
        // The rating floor requires BOTH players to qualify: 2500-vs-1000 stays out.
        assert(strong.forall(_.dice != "BBP"))

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

        val nullMoves = all.find(_.dice == "brr").get
        assertEquals(nullMoves.moves, "") // NULL played_moves coalesces to empty, not a crash

        val terminal = all.find(_.dice == "QQR").get
        assertEquals(terminal.moves, "h5f7 f7e8") // unflipped king-capture kept

        val weakRow = all.find(_.dice == "NNP").get
        assertEquals(weakRow.whiteType, None) // LEFT JOIN: no player rows
        assert(strong.forall(_.whiteRating == Some(2200)))
    }

  test("rows/counts compose mode and termination filters with minRating"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _       <- resetTables
          pStart  <- PositionsRepository.getOrCreate(fenStart)
          pAfterW <- PositionsRepository.getOrCreate(fenAfterW)
          // Classic, decisive by king capture, strong — survives every filter combination below.
          captured <- newGame(
            Some(1),
            Some(2200),
            Some(2200),
            None,
            None,
            mode = "classic",
            termination = "king_captured"
          )
          _ <- addTurn(captured, 1, "w", "PPP", Some(List("e2e4")), pStart, pAfterW)
          // Classic, but ended by timeout — excluded once termination=king_captured is set.
          timedOut <- newGame(
            Some(1),
            Some(2200),
            Some(2200),
            None,
            None,
            mode = "classic",
            termination = "timeout"
          )
          _ <- addTurn(timedOut, 1, "w", "NNP", Some(List("g1f3")), pStart, pAfterW)
          // x2 mode, otherwise identical — excluded once mode=classic is set.
          doubled <- newGame(
            Some(1),
            Some(2200),
            Some(2200),
            None,
            None,
            mode = "x2",
            termination = "king_captured"
          )
          _ <- addTurn(doubled, 1, "w", "RRQ", Some(List("h1h4")), pStart, pAfterW)
          // Classic, king-captured, but weak — excluded once minRating joins the other two filters.
          weakCaptured <- newGame(
            Some(1),
            Some(900),
            Some(900),
            None,
            None,
            mode = "classic",
            termination = "king_captured"
          )
          _ <- addTurn(weakCaptured, 1, "w", "BBP", Some(List("f1c4")), pStart, pAfterW)
        yield ()

      for
        _           <- seed.transact(t)
        classicOnly <- TrainingExportRepository
          .rows(Filters(mode = Some("classic")))
          .transact(t)
          .compile
          .toList
        capturedOnly <- TrainingExportRepository
          .rows(Filters(termination = Some("king_captured")))
          .transact(t)
          .compile
          .toList
        strongCapturedClassic <- TrainingExportRepository
          .rows(
            Filters(
              minRating = Some(2000),
              mode = Some("classic"),
              termination = Some("king_captured")
            )
          )
          .transact(t)
          .compile
          .toList
        combinedCounts <- TrainingExportRepository
          .counts(
            Filters(
              minRating = Some(2000),
              mode = Some("classic"),
              termination = Some("king_captured")
            )
          )
          .transact(t)
      yield
        assertEquals(classicOnly.map(_.dice).toSet, Set("PPP", "NNP", "BBP")) // x2 (RRQ) excluded
        assertEquals(
          capturedOnly.map(_.dice).toSet,
          Set("PPP", "RRQ", "BBP")
        ) // timeout (NNP) excluded
        assertEquals(strongCapturedClassic.map(_.dice), List("PPP"))
        assertEquals(combinedCounts, (1L, 1L))
    }

  test("filteredGameIds pages through every matching game exactly once"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _   <- resetTables
          ids <- (1 to 10).toList.traverse(_ =>
            newGame(Some(1), Some(2000), Some(2000), None, None, mode = "classic")
          )
          // One weak game that must never appear in a page.
          _ <- newGame(Some(1), Some(500), Some(500), None, None, mode = "classic")
        yield ids

      def collectAllPages(filters: Filters, batchSize: Int): IO[List[UUID]] =
        // Prepend each page (O(1)) and reverse+flatten once at the end, instead of `acc ++ batch`
        // per page, which is O(page count) per call and O(n²) overall for a `List`.
        def loop(afterId: Option[UUID], pages: List[List[UUID]]): IO[List[UUID]] =
          TrainingExportRepository
            .filteredGameIds(filters, afterId, batchSize)
            .transact(t)
            .flatMap {
              case Nil   => IO.pure(pages.reverse.flatten)
              case batch => loop(Some(batch.last), batch :: pages)
            }
        loop(None, Nil)

      for
        seeded <- seed.transact(t)
        paged  <- collectAllPages(
          Filters(minRating = Some(2000), mode = Some("classic")),
          batchSize = 3
        )
      yield
        // Together these two prove completeness AND no duplication: the weak game is excluded,
        // and every other seeded id shows up exactly once across however many pages it took.
        // (NOT checked against `paged.sorted`: Java's UUID#compareTo orders by signed long halves,
        // which disagrees with Postgres's byte-wise uuid ordering on roughly half of all random
        // UUIDs — the two orders simply aren't comparable, regardless of query correctness.)
        assertEquals(paged.size, 10)
        assertEquals(paged.toSet, seeded.toSet)
    }

  test("rowsForGames applies the same turn-level guards as rows, for an explicit id batch"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _        <- resetTables
          pStart   <- PositionsRepository.getOrCreate(fenStart)
          pAfterW  <- PositionsRepository.getOrCreate(fenAfterW)
          pNoKing  <- PositionsRepository.getOrCreate(fenNoKing)
          pPartial <- PositionsRepository.getOrCreate(fenPartial)
          game     <- newGame(Some(1), Some(2000), Some(2000), None, None)
          _        <- addTurn(game, 1, "w", "PPQ", Some(List("e2e4", "d2d4")), pStart, pAfterW)
          _        <- addTurn(game, 2, "b", "bnp", Some(Nil), pAfterW, pStart) // legal pass
          _ <- addTurn(game, 3, "w", "QQR", Some(List("h5f7", "f7e8")), pStart, pNoKing) // terminal
          _ <- addTurn(game, 4, "b", "ppp", Some(Nil), pAfterW, pAfterW) // self-loop: excluded
          _ <- addTurn(
            game,
            5,
            "w",
            "PPP",
            Some(List("d2d4")),
            pStart,
            pPartial
          ) // partial: excluded
        yield game

      for
        gameId <- seed.transact(t)
        rows   <- TrainingExportRepository.rowsForGames(List(gameId)).transact(t).compile.toList
        empty  <- TrainingExportRepository.rowsForGames(Nil).transact(t).compile.toList
      yield
        assertEquals(rows.map(_.dice).toSet, Set("PPQ", "bnp", "QQR"))
        assertEquals(empty, Nil) // an empty id batch short-circuits to no rows, not "all rows"
    }

  test("exportInBatches pages a small dataset to completion without skipping or duplicating games"):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _       <- resetTables
          pStart  <- PositionsRepository.getOrCreate(fenStart)
          pAfterW <- PositionsRepository.getOrCreate(fenAfterW)
          _       <- (1 to 7).toList.traverse_ { _ =>
            for
              game <- newGame(Some(1), Some(2000), Some(2000), None, None, mode = "classic")
              _    <- addTurn(game, 1, "w", "PPP", Some(List("e2e4")), pStart, pAfterW)
            yield ()
          }
        yield ()

      val writer = BufferedWriter(StringWriter())
      for
        _      <- seed.transact(t)
        result <- ExportTrainingDataApp.exportInBatches(
          t,
          Filters(minRating = Some(2000), mode = Some("classic")),
          batchSize = 2,
          writer
        )
      yield assertEquals(result, (7L, 7L))
    }

  test(
    "exportInBatches does not count a filter-matching game that contributes zero surviving rows"
  ):
    withContainers { pg =>
      val t    = xa(pg)
      val seed =
        for
          _       <- resetTables
          pStart  <- PositionsRepository.getOrCreate(fenStart)
          pAfterW <- PositionsRepository.getOrCreate(fenAfterW)
          // Matches the games-level filter and has one real, exportable turn.
          withRows <- newGame(Some(1), Some(2000), Some(2000), None, None, mode = "classic")
          _        <- addTurn(withRows, 1, "w", "PPP", Some(List("e2e4")), pStart, pAfterW)
          // Also matches the games-level filter, but its only turn is a no-op self-loop — zero
          // rows survive turnGuards, so it must not be counted as a "game" in the result either.
          selfLoopOnly <- newGame(Some(1), Some(2000), Some(2000), None, None, mode = "classic")
          _            <- addTurn(selfLoopOnly, 1, "w", "NNP", Some(Nil), pStart, pStart)
        yield ()

      val writer = BufferedWriter(StringWriter())
      for
        _      <- seed.transact(t)
        result <- ExportTrainingDataApp.exportInBatches(
          t,
          Filters(minRating = Some(2000), mode = Some("classic")),
          batchSize = 10,
          writer
        )
      yield assertEquals(result, (1L, 1L)) // 2 games match the filter, but only 1 contributes rows
    }

  test("parseBatchSize: absent defaults to 2000, non-positive and unparseable fail fast"):
    assertEquals(ExportTrainingDataApp.parseBatchSize(None), Right(2000))
    assertEquals(ExportTrainingDataApp.parseBatchSize(Some("500")), Right(500))
    assert(ExportTrainingDataApp.parseBatchSize(Some("0")).isLeft)
    assert(ExportTrainingDataApp.parseBatchSize(Some("-5")).isLeft)
    assert(ExportTrainingDataApp.parseBatchSize(Some("abc")).isLeft)

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

  test("parseMinRating: 0/absent disable the floor, typos and negatives fail fast"):
    assertEquals(ExportTrainingDataApp.parseMinRating(None), Right(None))
    assertEquals(ExportTrainingDataApp.parseMinRating(Some("0")), Right(None))
    assertEquals(ExportTrainingDataApp.parseMinRating(Some("2000")), Right(Some(2000)))
    assert(ExportTrainingDataApp.parseMinRating(Some("2000x")).isLeft)
    assert(ExportTrainingDataApp.parseMinRating(Some("-5")).isLeft)

  test("parseEnumFilter: blank/absent/dash disable the filter, valid values pass, typos fail fast"):
    val modes = Set("classic", "x2")
    assertEquals(ExportTrainingDataApp.parseEnumFilter(None, modes, "mode"), Right(None))
    assertEquals(ExportTrainingDataApp.parseEnumFilter(Some(""), modes, "mode"), Right(None))
    assertEquals(ExportTrainingDataApp.parseEnumFilter(Some("  "), modes, "mode"), Right(None))
    // "-" is the CLI-safe placeholder mise's task passes when a filter is meant to stay unset —
    // it must never collide with a real enum value.
    assertEquals(ExportTrainingDataApp.parseEnumFilter(Some("-"), modes, "mode"), Right(None))
    assertEquals(
      ExportTrainingDataApp.parseEnumFilter(Some("classic"), modes, "mode"),
      Right(Some("classic"))
    )
    // Case-insensitive: a hand-typed "CLASSIC" or "King_Captured" must not fail.
    assertEquals(
      ExportTrainingDataApp.parseEnumFilter(Some("CLASSIC"), modes, "mode"),
      Right(Some("classic"))
    )
    val result = ExportTrainingDataApp.parseEnumFilter(Some("clasic"), modes, "mode")
    assert(result.isLeft)
    assert(result.left.exists(_.contains("mode")))

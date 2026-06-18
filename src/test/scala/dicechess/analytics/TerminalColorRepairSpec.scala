package dicechess.analytics

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.maintenance.TerminalColorRepair
import dicechess.analytics.repository.PositionsRepository

/** Suspended regression test for issue #161.
  *
  * The 2026-06-10 legacy backfill recorded terminal king-capture positions without flipping the
  * side-to-move, so the same physical position exists under both colours and the Openings Explorer
  * shows one continuation as two rows. [[TerminalColorRepair.run]] should collapse them into one.
  *
  * Marked `.fail` until the fix lands — the fix PR implements `run` and removes the suspension.
  */
class TerminalColorRepairSpec extends CatsEffectSuite with TestContainerForAll:

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

  // Black to move, dice Bishop+Knight+Knight: the knight on b4 captures the white king on e1 via
  // b4-d3-e1. Both games below reach the SAME terminal position but recorded a different side to
  // move: the legacy game kept 'b' (the bug), the engine-replayed game flipped to 'w' (correct).
  private val startFen = "r1bqkbnr/pppppppp/8/8/1n6/2N5/PPPPPPPP/1RBQKBNR b Kkq -"
  private val afterB   =
    "r1bqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/1RBQnBNR b Kkq -" // legacy, not flipped
  private val afterW = "r1bqkbnr/pppppppp/8/8/8/2N5/PPPPPPPP/1RBQnBNR w Kkq -" // engine-correct

  private val legacyGame: java.util.UUID =
    java.util.UUID.fromString("00000000-0000-0000-0000-0000000061a1")
  private val modernGame: java.util.UUID =
    java.util.UUID.fromString("00000000-0000-0000-0000-0000000061a2")

  private def seed: ConnectionIO[Unit] =
    for
      ps <- PositionsRepository.getOrCreate(startFen)
      pb <- PositionsRepository.getOrCreate(afterB)
      pw <- PositionsRepository.getOrCreate(afterW)
      _  <- sql"""INSERT INTO games (id, source, result, termination, total_turns)
                  VALUES ($legacyGame, 'dicechess.com', -1,
                          'unknown'::game_termination_enum, 1)""".update.run
      _ <- sql"""INSERT INTO games (id, source, result, termination, total_turns)
                  VALUES ($modernGame, 'dicechess.com', -1,
                          'king_captured'::game_termination_enum, 1)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                     dice_sorted, played_moves, position_after_id)
                  VALUES ($legacyGame, 1, 'b', $ps, 'bnn',
                          ARRAY['b4d3','d3e1'], $pb)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                     dice_sorted, played_moves, position_after_id)
                  VALUES ($modernGame, 1, 'b', $ps, 'bnn',
                          ARRAY['b4d3','d3e1'], $pw)""".update.run
    yield ()

  test("repair collapses the split king-capture continuation into one row (issue #161)".fail):
    withContainers { pg =>
      val t = xa(pg)
      for
        _      <- seed.transact(t)
        before <- PositionsRepository
          .continuations(startFen, "bnn", None, None, None, 50)
          .transact(t)
        _     <- TerminalColorRepair.run.transact(t)
        after <- PositionsRepository
          .continuations(startFen, "bnn", None, None, None, 50)
          .transact(t)
        term <- sql"SELECT termination::text FROM games WHERE id = $legacyGame"
          .query[String]
          .unique
          .transact(t)
      yield
        assertEquals(before.items.size, 2) // the bug: one continuation shown as two rows
        assertEquals(after.items.size, 1)  // after repair: a single grouped continuation
        assertEquals(after.items.head.games, 2)
        assertEquals(term, "king_captured")
    }

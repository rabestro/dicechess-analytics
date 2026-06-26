package dicechess.analytics

import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import munit.CatsEffectSuite
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.maintenance.EnPassantCanonicalRepair
import dicechess.analytics.repository.PositionsRepository

/** Regression test for issue #203.
  *
  * Legacy data stored the naive FEN en-passant target, so a position reached after an uncapturable
  * double-push (e.g. `h2h4 h1h3`, where a rook lands on the e.p. square) existed both with the
  * target set and cleared, splitting one continuation into two rows.
  * [[EnPassantCanonicalRepair.run]] merges each such position into its canonical twin (creating it
  * when missing), re-points the turns / games, and deletes the orphaned legacy rows.
  */
class EnPassantCanonicalRepairSpec extends CatsEffectSuite with TestContainerForAll:

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

  private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"

  // Scenario H — uncapturable e.p. on an occupied square; the canonical '-' twin already exists.
  private val legacyH = "rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq h3"
  private val twinH   = "rnbqkbnr/pppppppp/8/8/7P/7R/PPPPPPP1/RNBQKBN1 b Qkq -"

  // Scenario A — uncapturable e.p.; the canonical twin must be created by the repair.
  private val legacyA = "rnbqkbnr/pppppppp/8/8/P7/R7/1PPPPPPP/1NBQKBNR b Kkq a3"
  private val twinA   = "rnbqkbnr/pppppppp/8/8/P7/R7/1PPPPPPP/1NBQKBNR b Kkq -"

  private val gLegacyH = UUID.fromString("00000000-0000-0000-0000-0000000203a1")
  private val gModernH = UUID.fromString("00000000-0000-0000-0000-0000000203a2")
  private val gLegacyA = UUID.fromString("00000000-0000-0000-0000-0000000203b1")

  /** Inserts a position verbatim (bypassing the now-canonicalising `Fen.normalize`) to simulate a
    * legacy row whose en-passant target was never reduced.
    */
  private def insertRaw(nf: String): ConnectionIO[Long] =
    val f = Fen.fields(nf)
    sql"""INSERT INTO positions (normalized_fen, fen_hash, piece_placement,
                                 active_color, castling, en_passant)
          VALUES ($nf, ${Fen.hash(nf)}, ${f.piecePlacement},
                  ${f.activeColor}, ${f.castling}, ${f.enPassant})
          RETURNING id""".query[Long].unique

  private def insertGame(id: UUID, result: Int, finalPos: Option[Long]): ConnectionIO[Int] =
    sql"""INSERT INTO games (id, source, result, termination, total_turns, final_position_id)
          VALUES ($id, 'dicechess.com', $result, 'king_captured'::game_termination_enum, 1,
                  $finalPos)""".update.run

  private def insertTurn(
      game: UUID,
      before: Long,
      after: Long,
      moves: List[String]
  ): ConnectionIO[Int] =
    sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                             dice_sorted, played_moves, position_after_id)
          VALUES ($game, 1, 'w', $before, 'PR', $moves, $after)""".update.run

  private def seed: ConnectionIO[Long] =
    for
      ps <- PositionsRepository.getOrCreate(start)
      lH <- insertRaw(legacyH)
      tH <- PositionsRepository.getOrCreate(twinH) // modern twin already present
      lA <- insertRaw(legacyA)
      _  <- insertGame(gLegacyH, 1, None)
      _  <- insertGame(gModernH, 1, None)
      _  <- insertGame(gLegacyA, 1, Some(lA))      // final_position_id must be re-pointed too
      _  <- insertTurn(gLegacyH, ps, lH, List("h2h4", "h1h3"))
      _  <- insertTurn(gModernH, ps, tH, List("h2h4", "h1h3"))
      _  <- insertTurn(gLegacyA, ps, lA, List("a2a4", "a1a3"))
    yield lA

  test("merges uncapturable en-passant positions: collapse, create twin, re-point, idempotent"):
    withContainers { pg =>
      val t       = xa(pg)
      def conts() = PositionsRepository.continuations(start, "PR", None, None, None, 50).transact(t)
      // batchSize = 1 forces several keyset batches over the seeded legacy positions.
      for
        _         <- seed.transact(t)
        before    <- conts()
        r1        <- EnPassantCanonicalRepair.runBatched(t, 1)
        after     <- conts()
        twinAHash <- sql"SELECT fen_hash FROM positions WHERE normalized_fen = $twinA"
          .query[Long]
          .option
          .transact(t)
        legacyGone <-
          sql"SELECT count(*) FROM positions WHERE normalized_fen IN ($legacyH, $legacyA)"
            .query[Int]
            .unique
            .transact(t)
        finalA <- sql"""SELECT p.normalized_fen FROM games g
                        JOIN positions p ON p.id = g.final_position_id
                        WHERE g.id = $gLegacyA""".query[String].unique.transact(t)
        r2 <- EnPassantCanonicalRepair.runBatched(t, 1)
      yield
        // Before: three distinct resulting positions (h3, twinH '-', a3). After: two ('-' twins).
        assertEquals(before.items.size, 3)
        assertEquals(after.items.size, 2)
        assertEquals(after.items.find(_.fen == twinH).map(_.games), Some(2))
        assertEquals(after.items.find(_.fen == twinA).map(_.games), Some(1))
        // The missing twin is created with the engine hash; legacy rows are gone.
        assertEquals(twinAHash, Some(Fen.hash(twinA)))
        assertEquals(legacyGone, 0)
        assertEquals(finalA, twinA)
        // Report, then idempotency: a second run changes nothing.
        assertEquals(r1, EnPassantCanonicalRepair.RepairReport(2, 2, 1, 0, 0, 2))
        assertEquals(r2, EnPassantCanonicalRepair.RepairReport(0, 0, 0, 0, 0, 0))
    }

  test("runBatched rejects a non-positive batch size"):
    withContainers { pg =>
      interceptIO[IllegalArgumentException](EnPassantCanonicalRepair.runBatched(xa(pg), 0)).void
    }

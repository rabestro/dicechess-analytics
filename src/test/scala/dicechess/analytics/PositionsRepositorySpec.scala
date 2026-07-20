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

import dicechess.analytics.repository.PositionsRepository

/** `PositionsRepository.getOrCreate` against a fresh PostgreSQL (testcontainers). */
class PositionsRepositorySpec extends CatsEffectSuite with TestContainerForAll:

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

  private val fen  = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
  private val fen2 = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"

  test("getOrCreate dedups by normalized FEN and persists the split fields + hash"):
    withContainers { pg =>
      val t = xa(pg)
      for
        id1    <- PositionsRepository.getOrCreate(fen).transact(t)
        id2    <- PositionsRepository.getOrCreate(fen).transact(t)  // same position → same id
        id3    <- PositionsRepository.getOrCreate(fen2).transact(t) // different → new id
        stored <- sql"""SELECT fen_hash, normalized_fen, piece_placement, active_color, en_passant
                        FROM positions WHERE id = $id1"""
          .query[(Long, String, String, String, String)]
          .unique
          .transact(t)
      yield
        assertEquals(id1, id2)
        assertNotEquals(id1, id3)
        val (hash, nf, placement, color, ep) = stored
        assertEquals(nf, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -")
        assertEquals(hash, Fen.hash(Fen.normalize(fen)))
        assertEquals(placement, "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR")
        assertEquals(color, "w")
        assertEquals(ep, "-")
    }

  // --- opening-book seeding helpers ---

  private def newGame(result: Int, rating: Int): ConnectionIO[UUID] =
    val id = UUID.randomUUID()
    sql"""INSERT INTO games (id, source, mode, result, started_at, white_rating, black_rating)
          VALUES ($id, 'test', 'classic'::game_mode_enum, $result, now(), $rating, $rating)""".update.run
      .as(id)

  /** Inserts `count` games (each a fresh single-turn game) all reaching `after` from `position`
    * after rolling `dice`.
    */
  private def seedTurns(
      color: String,
      result: Int,
      count: Int,
      rating: Int,
      position: Long,
      dice: String,
      moves: List[String],
      after: Long
  ): ConnectionIO[Unit] =
    List.fill(count)(()).traverse_ { _ =>
      newGame(result, rating).flatMap { gid =>
        sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                 dice_sorted, played_moves, position_after_id)
              VALUES ($gid, 1, $color, $position, $dice, $moves, $after)""".update.run.void
      }
    }

  private val resetTables: ConnectionIO[Unit] =
    sql"TRUNCATE opening_book_favorites, turns, games, positions RESTART IDENTITY CASCADE".update.run.void

  test(
    "openingBook picks the best mover win-rate continuation, applying strength, sample and completed-turn filters"
  ):
    withContainers { pg =>
      val t   = xa(pg)
      val p   = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val a1  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1" // flipped → complete
      val a2  = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"
      val a3  = "rnbqkbnr/pppppppp/8/8/2P5/8/PP1PPPPP/RNBQKBNR b KQkq c3 0 1"
      val inc =
        "rnbqkbnr/pppppppp/8/8/8/7P/PPPPPPP1/RNBQKBNR w KQkq - 0 1" // no flip, both kings → dropped
      val a4  = "rnbqkbnr/pppppppp/8/8/8/P7/1PPPPPPP/RNBQKBNR b KQkq - 0 1"
      for
        _    <- resetTables.transact(t)
        idP  <- PositionsRepository.getOrCreate(p).transact(t)
        idA1 <- PositionsRepository.getOrCreate(a1).transact(t)
        idA2 <- PositionsRepository.getOrCreate(a2).transact(t)
        idA3 <- PositionsRepository.getOrCreate(a3).transact(t)
        idIc <- PositionsRepository.getOrCreate(inc).transact(t)
        idA4 <- PositionsRepository.getOrCreate(a4).transact(t)
        _    <- seedTurns("w", 1, 7, 2200, idP, "BPR", List("e2e4", "f1c4"), idA1).transact(
          t
        ) // 0.7 win-rate
        _ <- seedTurns("w", -1, 3, 2200, idP, "BPR", List("e2e4", "f1c4"), idA1).transact(t)
        _ <- seedTurns("w", 1, 5, 2200, idP, "BPR", List("d2d4"), idA2).transact(t)  // 0.5 win-rate
        _ <- seedTurns("w", -1, 5, 2200, idP, "BPR", List("d2d4"), idA2).transact(t)
        _ <- seedTurns("w", 1, 10, 1000, idP, "BPR", List("g1f3"), idA3).transact(t) // 1.0 but WEAK
        _ <- seedTurns("w", 1, 10, 2200, idP, "BPR", List("h2h3"), idIc).transact(
          t
        ) // 1.0 but INCOMPLETE
        _ <- seedTurns("w", 1, 2, 2200, idP, "BPR", List("a2a4"), idA4).transact(
          t
        ) // 1.0 but LOW sample
        book <- PositionsRepository.openingBook(minGames = 5, minRating = Some(2000)).transact(t)
      yield
        assertEquals(book.get(s"${Fen.normalize(p)} BPR"), Some("e2e4,f1c4"))
        assertEquals(book.size, 1)
    }

  test(
    "openingBook ranks by the moving side's win-rate (not White's) for a Black-to-move position"
  ):
    withContainers { pg =>
      val t  = xa(pg)
      val pb = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val b1 = "rnbqkbnr/ppp1pppp/8/3p4/4P3/8/PPPP1PPP/RNBQKBNR w KQkq d6 0 2" // flipped → complete
      val b2 = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e6 0 2"
      for
        _    <- resetTables.transact(t)
        idPb <- PositionsRepository.getOrCreate(pb).transact(t)
        idB1 <- PositionsRepository.getOrCreate(b1).transact(t)
        idB2 <- PositionsRepository.getOrCreate(b2).transact(t)
        // B1 favours Black: 8 Black wins (result -1) + 2 White wins ⇒ Black win-rate 0.8.
        _ <- seedTurns("b", -1, 8, 2200, idPb, "bpr", List("d7d5"), idB1).transact(t)
        _ <- seedTurns("b", 1, 2, 2200, idPb, "bpr", List("d7d5"), idB1).transact(t)
        // B2 favours White: 8 White wins (result 1) + 2 Black wins ⇒ Black win-rate 0.2.
        _    <- seedTurns("b", 1, 8, 2200, idPb, "bpr", List("e7e5"), idB2).transact(t)
        _    <- seedTurns("b", -1, 2, 2200, idPb, "bpr", List("e7e5"), idB2).transact(t)
        book <- PositionsRepository.openingBook(minGames = 5, minRating = Some(2000)).transact(t)
      yield
        // A White-centric exporter would wrongly pick B2 (more result=1); the mover-perspective book picks B1.
        assertEquals(book.get(s"${Fen.normalize(pb)} bpr"), Some("d7d5"))
    }

  test("openingBook drops forced-pass (empty-move) turns but keeps real moves"):
    withContainers { pg =>
      val t      = xa(pg)
      val pos    = "rnbqkbnr/pppppppp/8/1N6/8/8/PPPPPPPP/R1BQKBNR b KQkq - 0 1"   // black to move
      val passed =
        "rnbqkbnr/pppppppp/8/1N6/8/8/PPPPPPPP/R1BQKBNR w KQkq - 0 2" // pass: flipped, no move
      val moved  = "r1bqkbnr/pppppppp/2n5/1N6/8/8/PPPPPPPP/R1BQKBNR w KQkq - 0 2" // a real reply
      for
        _      <- resetTables.transact(t)
        idPos  <- PositionsRepository.getOrCreate(pos).transact(t)
        idPass <- PositionsRepository.getOrCreate(passed).transact(t)
        idMove <- PositionsRepository.getOrCreate(moved).transact(t)
        // dice "bkq": forced pass — empty played_moves; must NOT reach the book
        _ <- seedTurns("b", -1, 8, 2200, idPos, "bkq", List.empty[String], idPass).transact(t)
        // dice "bbn": a real move; must reach the book
        _    <- seedTurns("b", -1, 8, 2200, idPos, "bbn", List("b8c6"), idMove).transact(t)
        book <- PositionsRepository.openingBook(minGames = 5, minRating = Some(2000)).transact(t)
      yield
        assertEquals(book.get(s"${Fen.normalize(pos)} bkq"), None)         // forced pass excluded
        assertEquals(book.get(s"${Fen.normalize(pos)} bbn"), Some("b8c6")) // real move kept
        assert(book.values.forall(_.nonEmpty), "the book must contain no empty-move entries")
    }

  // --- opening-book favorites tests ---

  test("setFavorite upserts and favoritesForPosition returns the canonical entry"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val nf  = Fen.normalize(pos)
      for
        _ <- resetTables.transact(t)
        // UI sends uppercase dice; server must store uppercase for white-to-move
        entry <- PositionsRepository
          .setFavorite(pos, "BPR", List("e2e4", "f1c4"), Some("test note"))
          .transact(t)
        favs <- PositionsRepository.favoritesForPosition(pos).transact(t)
        // Upsert: change the moves; should update
        _       <- PositionsRepository.setFavorite(pos, "bpr", List("d2d4"), None).transact(t)
        updated <- PositionsRepository.favoritesForPosition(pos).transact(t)
      yield
        assertEquals(entry.normalizedFen, nf)
        assertEquals(entry.diceSorted, "BPR") // white-to-move → uppercase
        assertEquals(entry.moves, List("e2e4", "f1c4"))
        assertEquals(entry.note, Some("test note"))
        assertEquals(favs.items.size, 1)
        assertEquals(favs.items.head.diceSorted, "BPR")
        // Second call used lowercase input but position is white-to-move → re-cased to uppercase
        assertEquals(updated.items.head.diceSorted, "BPR")
        assertEquals(updated.items.head.moves, List("d2d4"))
        assertEquals(updated.items.head.note, None)
    }

  test("setFavorite stores lowercase dice_sorted for black-to-move positions"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      for
        _     <- resetTables.transact(t)
        entry <- PositionsRepository.setFavorite(pos, "BPR", List("e7e5"), None).transact(t)
      yield
        // Black-to-move: dice must be stored lowercase regardless of what the UI sent
        assertEquals(entry.diceSorted, "bpr")
    }

  test("deleteFavorite removes the row and returns true; missing returns false"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      for
        _       <- resetTables.transact(t)
        _       <- PositionsRepository.setFavorite(pos, "BPR", List("e2e4"), None).transact(t)
        deleted <- PositionsRepository.deleteFavorite(pos, "BPR").transact(t)
        again   <- PositionsRepository.deleteFavorite(pos, "BPR").transact(t)
        favs    <- PositionsRepository.favoritesForPosition(pos).transact(t)
      yield
        assert(deleted, "first delete should return true")
        assert(!again, "second delete on missing row should return false")
        assertEquals(favs.items, Nil)
    }

  test("openingBook: curated favorite overrides the statistical best-pick for the same key"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val a1  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      val a2  = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq d3 0 1"
      for
        _    <- resetTables.transact(t)
        idP  <- PositionsRepository.getOrCreate(pos).transact(t)
        idA1 <- PositionsRepository.getOrCreate(a1).transact(t)
        idA2 <- PositionsRepository.getOrCreate(a2).transact(t)
        // Statistical best: e2e4 with win-rate 0.9
        _ <- seedTurns("w", 1, 9, 2200, idP, "BPR", List("e2e4"), idA1).transact(t)
        _ <- seedTurns("w", -1, 1, 2200, idP, "BPR", List("e2e4"), idA1).transact(t)
        // Statistical second: d2d4 with win-rate 0.5
        _ <- seedTurns("w", 1, 5, 2200, idP, "BPR", List("d2d4"), idA2).transact(t)
        _ <- seedTurns("w", -1, 5, 2200, idP, "BPR", List("d2d4"), idA2).transact(t)
        // Curated favorite overrides with a different move
        _    <- PositionsRepository.setFavorite(pos, "BPR", List("g1f3", "f1c4"), None).transact(t)
        book <- PositionsRepository.openingBook(minGames = 5, minRating = Some(2000)).transact(t)
      yield
        // Curated wins over the statistically-best e2e4
        assertEquals(book.get(s"${Fen.normalize(pos)} BPR"), Some("g1f3,f1c4"))
    }

  test("openingBook: favorite below minGames threshold is still included"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val a1  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      for
        _    <- resetTables.transact(t)
        idP  <- PositionsRepository.getOrCreate(pos).transact(t)
        idA1 <- PositionsRepository.getOrCreate(a1).transact(t)
        // Only 2 games — below minGames=5; statistical branch would drop it
        _ <- seedTurns("w", 1, 2, 2200, idP, "BPR", List("e2e4"), idA1).transact(t)
        // Curated favorite for the same key: bypasses the sample gate
        _    <- PositionsRepository.setFavorite(pos, "BPR", List("h2h4"), None).transact(t)
        book <- PositionsRepository.openingBook(minGames = 5, minRating = Some(2000)).transact(t)
      yield
        // The expert override is present even though only 2 statistical games were played
        assertEquals(book.get(s"${Fen.normalize(pos)} BPR"), Some("h2h4"))
    }

  // --- openingBookEntries tests (issue #251: stats/curation detail behind the book) ---

  test("openingBookEntries carries the winning continuation's games/wins/draws/losses/winRate"):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val a1  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      for
        _       <- resetTables.transact(t)
        idP     <- PositionsRepository.getOrCreate(pos).transact(t)
        idA1    <- PositionsRepository.getOrCreate(a1).transact(t)
        _       <- seedTurns("w", 1, 7, 2200, idP, "BPR", List("e2e4", "f1c4"), idA1).transact(t)
        _       <- seedTurns("w", -1, 3, 2200, idP, "BPR", List("e2e4", "f1c4"), idA1).transact(t)
        entries <- PositionsRepository
          .openingBookEntries(minGames = 5, minRating = Some(2000))
          .transact(t)
      yield
        assertEquals(entries.size, 1)
        val entry = entries.head
        assertEquals(entry.normalizedFen, Fen.normalize(pos))
        assertEquals(entry.diceSorted, "BPR")
        assertEquals(entry.moves, "e2e4,f1c4")
        assertEquals(entry.games, 10)
        assertEquals(entry.wins, 7)
        assertEquals(entry.draws, 0)
        assertEquals(entry.losses, 3)
        assertEquals(entry.winRate, 0.7)
        assertEquals(entry.curated, false)
        assertEquals(entry.note, None)
    }

  test(
    "openingBookEntries: curated favorite overrides the statistical entry and carries its note, no stats"
  ):
    withContainers { pg =>
      val t   = xa(pg)
      val pos = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
      val a1  = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3 0 1"
      for
        _    <- resetTables.transact(t)
        idP  <- PositionsRepository.getOrCreate(pos).transact(t)
        idA1 <- PositionsRepository.getOrCreate(a1).transact(t)
        _    <- seedTurns("w", 1, 9, 2200, idP, "BPR", List("e2e4"), idA1).transact(t)
        _    <- seedTurns("w", -1, 1, 2200, idP, "BPR", List("e2e4"), idA1).transact(t)
        _    <- PositionsRepository
          .setFavorite(pos, "BPR", List("g1f3", "f1c4"), Some("book line"))
          .transact(t)
        entries <- PositionsRepository
          .openingBookEntries(minGames = 5, minRating = Some(2000))
          .transact(t)
      yield
        assertEquals(entries.size, 1)
        val entry = entries.head
        assertEquals(entry.moves, "g1f3,f1c4")
        assertEquals(entry.curated, true)
        assertEquals(entry.note, Some("book line"))
        assertEquals(entry.games, 0)
        assertEquals(entry.winRate, 0.0)
    }

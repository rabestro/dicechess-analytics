package dicechess.analytics

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
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

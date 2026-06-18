package dicechess.analytics

import java.net.URLEncoder
import java.time.OffsetDateTime
import java.util.UUID

import cats.effect.IO
import com.dimafeng.testcontainers.PostgreSQLContainer
import com.dimafeng.testcontainers.munit.TestContainerForAll
import doobie.*
import doobie.implicits.*
import doobie.postgres.implicits.*
import io.circe.Json
import io.circe.parser.parse
import munit.CatsEffectSuite
import org.http4s.client.Client
import org.http4s.{Method, Request, Status, Uri}
import org.testcontainers.utility.DockerImageName

import dicechess.analytics.api.Routes
import dicechess.analytics.api.Protocol.VersionInfo
import dicechess.analytics.repository.PositionsRepository

/** End-to-end API tests: Flyway migrates a fresh PostgreSQL (testcontainers), data is seeded with
  * plain SQL, endpoints are exercised through the full http4s application.
  */
class ApiSpec extends CatsEffectSuite with TestContainerForAll:

  override val containerDef: PostgreSQLContainer.Def =
    PostgreSQLContainer.Def(DockerImageName.parse("postgres:18-alpine"))

  private val alice    = UUID.fromString("00000000-0000-0000-0000-00000000000a")
  private val bob      = UUID.fromString("00000000-0000-0000-0000-00000000000b")
  private val game1    = UUID.fromString("00000000-0000-0000-0000-000000000101")
  private val game2    = UUID.fromString("00000000-0000-0000-0000-000000000102")
  private val missing  = UUID.fromString("ffffffff-ffff-ffff-ffff-ffffffffffff")
  private val fenStart = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
  private val fenAfter = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -"

  private def transactor(pg: PostgreSQLContainer): Transactor[IO] =
    Transactor.fromDriverManager[IO](
      driver = "org.postgresql.Driver",
      url = pg.jdbcUrl,
      user = pg.username,
      password = pg.password,
      logHandler = None
    )

  override def afterContainersStart(pg: Containers): Unit =
    Database.migrate(DbConfig(pg.jdbcUrl, pg.username, pg.password)).unsafeRunSync()
    seed.transact(transactor(pg)).unsafeRunSync()

  private def seed: ConnectionIO[Unit] =
    val t1 = OffsetDateTime.parse("2026-06-10T12:00:00Z")
    val t2 = OffsetDateTime.parse("2026-06-01T12:00:00Z")
    for
      _ <- sql"""INSERT INTO players (id, external_id, username, player_type)
                 VALUES ($alice, 'ext-a', 'alice', 'human'), ($bob, 'ext-b', 'bob', 'bot')""".update.run
      p1 <- sql"""INSERT INTO positions (normalized_fen, fen_hash, piece_placement, active_color)
                  VALUES ($fenStart, 11, 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR', 'w')
                  RETURNING id""".query[Long].unique
      p2 <- sql"""INSERT INTO positions (normalized_fen, fen_hash, piece_placement, active_color)
                  VALUES ($fenAfter, 22, 'rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR', 'b')
                  RETURNING id""".query[Long].unique
      _ <- sql"""INSERT INTO games (id, source, white_player_id, black_player_id,
                                    white_rating, black_rating, mode, result, termination,
                                    total_turns, time_initial_sec, time_increment_sec,
                                    initial_stake_amount, final_stake_amount,
                                    white_money_delta, black_money_delta, stake_currency,
                                    started_at, metadata_json)
                 VALUES ($game1, 'dicechess.com', $alice, $bob, 1500, 1480, 'x2', 1,
                         'king_captured'::game_termination_enum, 4,
                         180, 2, 10, 20, ${BigDecimal("12.5")}, ${BigDecimal("-12.5")}, 'USD',
                         $t1, '{"tournament": "summer"}'::jsonb)""".update.run
      _ <- sql"""INSERT INTO games (id, source, black_player_id, black_rating, mode,
                                    result, total_turns, started_at)
                 VALUES ($game2, 'dicechess.com', $alice, 1450, 'classic', -1, 2, $t2)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                    dice_sorted, played_moves, position_after_id, thinking_time_ms)
                 VALUES ($game1, 1, 'w', $p1, 'NPQ', ARRAY['e2e4','g1f3'], $p2, 1500)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id, dice_sorted)
                 VALUES ($game1, 2, 'b', $p2, 'bbk')""".update.run
      _ <- sql"""INSERT INTO game_events (game_id, sequence_number, turn_number, event_type,
                                          actor_color, clock_white_ms, clock_black_ms, payload)
                 VALUES ($game1, 1, 1, 'DOUBLE_OFFER', 'w', 170000, 165000,
                         '{"bank": 100}'::jsonb)""".update.run
    yield ()

  private val testVersion = VersionInfo("dicechess-analytics-backend", "test-9.9.9", "3.8.3")

  private def withClient[A](pg: PostgreSQLContainer)(run: Client[IO] => IO[A]): IO[A] =
    val app = Routes(transactor(pg), List("http://localhost:5173"), None, testVersion).httpApp
    run(Client.fromHttpApp(app))

  private def getJson(client: Client[IO], uri: String): IO[Json] =
    client
      .run(Request[IO](Method.GET, Uri.unsafeFromString(uri)))
      .use { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String]
      }
      .map(body => parse(body).fold(throw _, identity))

  /** Items of a Page envelope `{ items, total, limit, offset }`. */
  private def itemsOf(json: Json): Vector[Json] =
    json.hcursor.downField("items").focus.flatMap(_.asArray).getOrElse(fail("expected items array"))

  private def totalOf(json: Json): Long =
    json.hcursor.get[Long]("total").fold(e => fail(s"missing total: $e"), identity)

  private def idsOf(json: Json): Vector[Either[io.circe.DecodingFailure, String]] =
    itemsOf(json).map(_.hcursor.get[String]("id"))

  test("GET / returns the welcome message"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/").map { json =>
          assertEquals(
            json.hcursor.get[String]("message"),
            Right("Welcome to Dice Chess Analytics API")
          )
          assertEquals(json.hcursor.get[String]("docs"), Right("/docs"))
        }
      }
    }

  test("GET /version reports the injected build/version info"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/version").map { json =>
          assertEquals(json.hcursor.get[String]("name"), Right("dicechess-analytics-backend"))
          assertEquals(json.hcursor.get[String]("version"), Right("test-9.9.9"))
          assertEquals(json.hcursor.get[String]("scala_version"), Right("3.8.3"))
        }
      }
    }

  test(
    "GET /api/positions/continuations groups move orderings by resulting position, filters mode"
  ):
    withContainers { pg =>
      val xa       = transactor(pg)
      val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
      val afterA   = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -"
      val afterB   = "rnbqkbnr/pppppppp/8/8/3P4/8/PPP1PPPP/RNBQKBNR b KQkq -"
      val afterC   = "rnbqkbnr/pppppppp/8/8/8/4P3/PPPP1PPP/RNBQKBNR b KQkq -"
      val gA1      = UUID.fromString("00000000-0000-0000-0000-0000000000c1")
      val gA2      = UUID.fromString("00000000-0000-0000-0000-0000000000c2")
      val gB       = UUID.fromString("00000000-0000-0000-0000-0000000000c3")
      val gX       = UUID.fromString("00000000-0000-0000-0000-0000000000c4")
      val gN       = UUID.fromString("00000000-0000-0000-0000-0000000000c5")
      val ts       = OffsetDateTime.parse("2026-06-01T00:00:00Z")

      // Two classic games reach afterA via different move orderings (must collapse to one
      // continuation), one classic game loses to afterB, one x2 game also reaches afterA, and one
      // classic game reaches afterC with NULL played_moves (must not crash → empty moves).
      val seedC =
        for
          ps <- PositionsRepository.getOrCreate(startFen)
          pa <- PositionsRepository.getOrCreate(afterA)
          pb <- PositionsRepository.getOrCreate(afterB)
          pc <- PositionsRepository.getOrCreate(afterC)
          _  <- sql"""INSERT INTO games (id, source, mode, result, started_at) VALUES
                      ($gA1, 'test', 'classic', 1, $ts), ($gA2, 'test', 'classic', 1, $ts),
                      ($gB, 'test', 'classic', -1, $ts), ($gX, 'test', 'x2', 1, $ts),
                      ($gN, 'test', 'classic', 1, $ts)""".update.run
          _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                         dice_sorted, played_moves, position_after_id) VALUES
                      ($gA1, 1, 'w', $ps, 'BPQ', ARRAY['e2e4','d1f3','f1c4'], $pa),
                      ($gA2, 1, 'w', $ps, 'BPQ', ARRAY['e2e4','f1c4','d1f3'], $pa),
                      ($gB,  1, 'w', $ps, 'BPQ', ARRAY['d2d4','c1f4','d1d3'], $pb),
                      ($gX,  1, 'w', $ps, 'BPQ', ARRAY['e2e4','d1f3','f1c4'], $pa),
                      ($gN,  1, 'w', $ps, 'BPQ', NULL, $pc)""".update.run
        yield ()

      val cleanup =
        for
          _ <- sql"DELETE FROM turns WHERE game_id IN ($gA1, $gA2, $gB, $gX, $gN)".update.run
          _ <- sql"DELETE FROM games WHERE id IN ($gA1, $gA2, $gB, $gX, $gN)".update.run
        yield ()

      val base =
        s"/api/positions/continuations?fen=${URLEncoder.encode(startFen, "UTF-8")}&dice=BPQ"

      seedC.transact(xa) >>
        withClient(pg) { client =>
          for
            classic <- getJson(client, s"$base&mode=classic")
            all     <- getJson(client, base)
            limited <- getJson(client, s"$base&mode=classic&limit=1")
          yield
            // x2 excluded; afterA collapses two orderings, afterB and afterC are single games
            assertEquals(classic.hcursor.get[Int]("total_games"), Right(4))
            val items = itemsOf(classic)
            assertEquals(items.size, 3)
            val top = items.head.hcursor
            assertEquals(top.get[Int]("games"), Right(2))
            assertEquals(top.get[Double]("win_rate"), Right(1.0))
            assertEquals(top.get[List[String]]("moves"), Right(List("e2e4", "d1f3", "f1c4")))
            // the NULL-played_moves turn yields a continuation with empty moves (no crash)
            val nullMoves = items.map(_.hcursor).find(_.get[List[String]]("moves") == Right(Nil))
            assert(nullMoves.isDefined, "expected a continuation with no recorded moves")
            assertEquals(nullMoves.get.get[Int]("games"), Right(1))
            // all modes: afterA now also counts the x2 game
            assertEquals(all.hcursor.get[Int]("total_games"), Right(5))
            assertEquals(itemsOf(all).head.hcursor.get[Int]("games"), Right(3))
            // limit truncates the returned rows but total_games stays the pre-limit total
            assertEquals(itemsOf(limited).size, 1)
            assertEquals(limited.hcursor.get[Int]("total_games"), Right(4))
        }.guarantee(cleanup.transact(xa))
    }

  test("GET /api/positions/continuations cases the dice by the side to move (black → lower-case)"):
    withContainers { pg =>
      val xa       = transactor(pg)
      val blackFen = "rnbqkbnr/pppppppp/8/8/2B1P3/5Q2/PPPP1PPP/RNB1K1NR b KQkq e3"
      val afterFen = "rnbqkb1r/pppppppp/5n2/8/2B1P3/5Q2/PPPP1PPP/RNB1K1NR w KQkq -"
      val gBlk     = UUID.fromString("00000000-0000-0000-0000-0000000000d1")
      val ts       = OffsetDateTime.parse("2026-06-01T00:00:00Z")

      // Black to move, so the stored dice_sorted is LOWER-case ('bpq'). The client sends
      // colour-agnostic piece letters ('BPQ'); the endpoint must lower-case them here.
      val seedC =
        for
          ps <- PositionsRepository.getOrCreate(blackFen)
          pa <- PositionsRepository.getOrCreate(afterFen)
          _  <- sql"""INSERT INTO games (id, source, mode, result, started_at)
                     VALUES ($gBlk, 'test', 'classic', -1, $ts)""".update.run
          _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                        dice_sorted, played_moves, position_after_id)
                     VALUES ($gBlk, 2, 'b', $ps, 'bpq', ARRAY['g8f6','d8e7','c8d7'], $pa)""".update.run
        yield ()
      val cleanup =
        for
          _ <- sql"DELETE FROM turns WHERE game_id = $gBlk".update.run
          _ <- sql"DELETE FROM games WHERE id = $gBlk".update.run
        yield ()
      val base = s"/api/positions/continuations?fen=${URLEncoder.encode(blackFen, "UTF-8")}"

      seedC.transact(xa) >>
        withClient(pg) { client =>
          for
            upper <- getJson(client, s"$base&dice=BPQ")
            lower <- getJson(client, s"$base&dice=bpq")
          yield
            // an upper-case query is lower-cased for a black-to-move position and matches
            assertEquals(upper.hcursor.get[Int]("total_games"), Right(1))
            assertEquals(itemsOf(upper).head.hcursor.get[Int]("games"), Right(1))
            // black won (result -1), and the mover is black → win rate 1.0
            assertEquals(itemsOf(upper).head.hcursor.get[Double]("win_rate"), Right(1.0))
            // a lower-case query resolves to the same data
            assertEquals(lower.hcursor.get[Int]("total_games"), Right(1))
        }.guarantee(cleanup.transact(xa))
    }

  test("GET /api/positions/continuations excludes no-op self-loop turns (rolled but not played)"):
    withContainers { pg =>
      val xa       = transactor(pg)
      val startFen = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
      val afterFen = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq -"
      val gReal    = UUID.fromString("00000000-0000-0000-0000-0000000000e1")
      val gNoop    = UUID.fromString("00000000-0000-0000-0000-0000000000e2")
      val ts       = OffsetDateTime.parse("2026-06-01T00:00:00Z")

      // One real continuation (start -> afterFen) and one no-op turn where the player rolled but
      // never moved (a draw agreed / abandoned before the move), recorded as a self-loop:
      // position_after == position. The no-op must be excluded entirely — not counted, not shown.
      val seedC =
        for
          ps <- PositionsRepository.getOrCreate(startFen)
          pa <- PositionsRepository.getOrCreate(afterFen)
          _  <- sql"""INSERT INTO games (id, source, mode, result, started_at) VALUES
                      ($gReal, 'test', 'classic', 1, $ts),
                      ($gNoop, 'test', 'classic', 0, $ts)""".update.run
          _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                         dice_sorted, played_moves, position_after_id) VALUES
                      ($gReal, 1, 'w', $ps, 'BPQ', ARRAY['e2e4','d1f3','f1c4'], $pa),
                      ($gNoop, 1, 'w', $ps, 'BPQ', NULL, $ps)""".update.run
        yield ()
      val cleanup =
        for
          _ <- sql"DELETE FROM turns WHERE game_id IN ($gReal, $gNoop)".update.run
          _ <- sql"DELETE FROM games WHERE id IN ($gReal, $gNoop)".update.run
        yield ()
      val uri =
        s"/api/positions/continuations?fen=${URLEncoder.encode(startFen, "UTF-8")}&dice=BPQ&mode=classic"

      seedC.transact(xa) >>
        withClient(pg) { client =>
          getJson(client, uri).map { json =>
            // only the real continuation counts; the no-op self-loop is excluded from total and items
            assertEquals(json.hcursor.get[Int]("total_games"), Right(1))
            val items = itemsOf(json)
            assertEquals(items.size, 1)
            assertEquals(
              items.head.hcursor.get[List[String]]("moves"),
              Right(List("e2e4", "d1f3", "f1c4"))
            )
            // no continuation resolves back to the start position
            assert(
              !items.map(_.hcursor).exists(_.get[String]("fen") == Right(startFen)),
              "a no-op self-loop must not appear as a continuation"
            )
          }
        }.guarantee(cleanup.transact(xa))
    }

  test("GET /api/positions/equity aggregates across rolls, weighting draws as half, filters mode"):
    withContainers { pg =>
      val xa = transactor(pg)
      // A position used by no other seed: equity has no dice filter, so it would otherwise pick up
      // the global seed's turn on the start position. White to move throughout.
      val equityFen = "rnbqkbnr/pppp1ppp/8/4p3/4P3/8/PPPP1PPP/RNBQKBNR w KQkq -"
      val afterFen  = "rnbqkbnr/pppp1ppp/8/4p3/4P3/5N2/PPPP1PPP/RNBQKB1R b KQkq -"
      val gW1       = UUID.fromString("00000000-0000-0000-0000-0000000000f1")
      val gW2       = UUID.fromString("00000000-0000-0000-0000-0000000000f2")
      val gD        = UUID.fromString("00000000-0000-0000-0000-0000000000f3")
      val gL        = UUID.fromString("00000000-0000-0000-0000-0000000000f4")
      val gX2       = UUID.fromString("00000000-0000-0000-0000-0000000000f5")
      val gNoop     = UUID.fromString("00000000-0000-0000-0000-0000000000f6")
      val ts        = OffsetDateTime.parse("2026-06-01T00:00:00Z")

      // Classic: two wins (different rolls), one draw, one loss → decided 4, equity (2 + 0.5)/4.
      // One x2 win is counted only in "all". One no-op self-loop (position_after == position) must
      // be excluded entirely.
      val seedC =
        for
          ps <- PositionsRepository.getOrCreate(equityFen)
          pa <- PositionsRepository.getOrCreate(afterFen)
          _  <- sql"""INSERT INTO games (id, source, mode, result, started_at) VALUES
                      ($gW1, 'test', 'classic',  1, $ts), ($gW2, 'test', 'classic',  1, $ts),
                      ($gD,  'test', 'classic',  0, $ts), ($gL,  'test', 'classic', -1, $ts),
                      ($gX2, 'test', 'x2',       1, $ts), ($gNoop,'test', 'classic',  0, $ts)""".update.run
          _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                         dice_sorted, played_moves, position_after_id) VALUES
                      ($gW1,  1, 'w', $ps, 'NPQ', ARRAY['g1f3'], $pa),
                      ($gW2,  1, 'w', $ps, 'BPQ', ARRAY['e4e5'], $pa),
                      ($gD,   1, 'w', $ps, 'BPQ', ARRAY['g1f3'], $pa),
                      ($gL,   1, 'w', $ps, 'BPQ', ARRAY['g1f3'], $pa),
                      ($gX2,  1, 'w', $ps, 'BPQ', ARRAY['g1f3'], $pa),
                      ($gNoop,1, 'w', $ps, 'BPQ', NULL, $ps)""".update.run
        yield ()
      val cleanup =
        for
          _ <-
            sql"DELETE FROM turns WHERE game_id IN ($gW1, $gW2, $gD, $gL, $gX2, $gNoop)".update.run
          _ <- sql"DELETE FROM games WHERE id IN ($gW1, $gW2, $gD, $gL, $gX2, $gNoop)".update.run
        yield ()
      val base = s"/api/positions/equity?fen=${URLEncoder.encode(equityFen, "UTF-8")}"

      seedC.transact(xa) >>
        withClient(pg) { client =>
          for
            classic <- getJson(client, s"$base&mode=classic")
            all     <- getJson(client, base)
          yield
            // classic: x2 win and the no-op self-loop are both excluded
            assertEquals(classic.hcursor.get[String]("side_to_move"), Right("w"))
            assertEquals(classic.hcursor.get[Int]("games"), Right(4))
            assertEquals(classic.hcursor.get[Int]("wins"), Right(2))
            assertEquals(classic.hcursor.get[Int]("draws"), Right(1))
            assertEquals(classic.hcursor.get[Int]("losses"), Right(1))
            assertEquals(classic.hcursor.get[Double]("win_rate"), Right(0.625))
            // all modes: the x2 win now counts → decided 5, equity (3 + 0.5)/5
            assertEquals(all.hcursor.get[Int]("games"), Right(5))
            assertEquals(all.hcursor.get[Int]("wins"), Right(3))
            assertEquals(all.hcursor.get[Double]("win_rate"), Right(0.7))
        }.guarantee(cleanup.transact(xa))
    }

  test("GET /api/positions/equity returns zeros for a position with no turns"):
    withContainers { pg =>
      withClient(pg) { client =>
        val emptyFen = "8/8/8/8/8/8/8/K6k w - -"
        getJson(client, s"/api/positions/equity?fen=${URLEncoder.encode(emptyFen, "UTF-8")}").map {
          json =>
            assertEquals(json.hcursor.get[Int]("games"), Right(0))
            assertEquals(json.hcursor.get[Int]("wins"), Right(0))
            assertEquals(json.hcursor.get[Double]("win_rate"), Right(0.0))
        }
      }
    }

  test("GET /api/games lists games ordered by started_at desc with nested players"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/api/games").map { json =>
          assertEquals(totalOf(json), 2L)
          val games = itemsOf(json)
          assertEquals(games.size, 2)
          val first = games.head.hcursor
          assertEquals(first.get[String]("id"), Right(game1.toString))
          assertEquals(first.get[String]("mode"), Right("x2"))
          assertEquals(first.get[String]("termination"), Right("king_captured"))
          assertEquals(first.get[Int]("white_rating"), Right(1500))
          assertEquals(first.get[BigDecimal]("white_money_delta"), Right(BigDecimal("12.5")))
          assertEquals(first.downField("white_player").get[String]("username"), Right("alice"))
          assertEquals(first.downField("black_player").get[String]("player_type"), Right("bot"))
          assertEquals(games(1).hcursor.get[String]("id"), Right(game2.toString))
          // game2 seeded without a termination → NOT NULL DEFAULT 'unknown'
          assertEquals(games(1).hcursor.get[String]("termination"), Right("unknown"))
        }
      }
    }

  test("GET /api/games filters by player_id and min_turns"):
    withContainers { pg =>
      withClient(pg) { client =>
        for
          byBob <- getJson(client, s"/api/games?player_id=$bob")
          byMin <- getJson(client, "/api/games?min_turns=3")
        yield
          assertEquals(itemsOf(byBob).size, 1)
          assertEquals(totalOf(byBob), 1L)
          assertEquals(itemsOf(byMin).size, 1)
          assertEquals(itemsOf(byMin).head.hcursor.get[String]("id"), Right(game1.toString))
      }
    }

  test("GET /api/games/{id} returns the full detail with turns and events"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, s"/api/games/$game1").map { json =>
          val c = json.hcursor
          assertEquals(c.get[String]("termination"), Right("king_captured"))
          assertEquals(c.get[Int]("total_turns"), Right(4))
          assertEquals(
            c.downField("metadata_json").get[String]("tournament"),
            Right("summer")
          )
          val turns = c.downField("turns").focus.flatMap(_.asArray).getOrElse(fail("no turns"))
          assertEquals(turns.size, 2)
          val turn1 = turns.head.hcursor
          assertEquals(turn1.get[Int]("turn_number"), Right(1))
          assertEquals(turn1.get[String]("position_fen"), Right(fenStart))
          assertEquals(turn1.get[String]("position_after_fen"), Right(fenAfter))
          assertEquals(turn1.get[List[String]]("played_moves"), Right(List("e2e4", "g1f3")))
          val turn2 = turns(1).hcursor
          assertEquals(turn2.get[Option[String]]("position_after_fen"), Right(None))
          val events = c.downField("events").focus.flatMap(_.asArray).getOrElse(fail("no events"))
          assertEquals(events.size, 1)
          val event = events.head.hcursor
          assertEquals(event.get[String]("event_type"), Right("DOUBLE_OFFER"))
          assertEquals(event.downField("payload").get[Int]("bank"), Right(100))
        }
      }
    }

  test("GET /api/games paginates with limit and offset"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/api/games?limit=1&offset=1").map { json =>
          val games = itemsOf(json)
          assertEquals(games.size, 1)
          assertEquals(games.head.hcursor.get[String]("id"), Right(game2.toString))
          // total reflects the full match count, independent of limit/offset
          assertEquals(totalOf(json), 2L)
        }
      }
    }

  test("GET /api/games filters by mode and result"):
    withContainers { pg =>
      withClient(pg) { client =>
        for
          x2       <- getJson(client, "/api/games?mode=x2")
          classic  <- getJson(client, "/api/games?mode=classic")
          whiteWin <- getJson(client, "/api/games?result=1")
          blackWin <- getJson(client, "/api/games?result=-1")
        yield
          assertEquals(totalOf(x2), 1L)
          assertEquals(idsOf(x2), Vector(Right(game1.toString)))
          assertEquals(idsOf(classic), Vector(Right(game2.toString)))
          assertEquals(idsOf(whiteWin), Vector(Right(game1.toString)))
          assertEquals(idsOf(blackWin), Vector(Right(game2.toString)))
      }
    }

  test("GET /api/games filters by started_at date range"):
    withContainers { pg =>
      withClient(pg) { client =>
        // game1 started 2026-06-10, game2 started 2026-06-01
        for
          from <- getJson(client, "/api/games?date_from=2026-06-05")
          to   <- getJson(client, "/api/games?date_to=2026-06-05")
        yield
          assertEquals(idsOf(from), Vector(Right(game1.toString)))
          assertEquals(idsOf(to), Vector(Right(game2.toString)))
      }
    }

  test("GET /api/games filters by max_turns and sorts by total_turns"):
    withContainers { pg =>
      withClient(pg) { client =>
        // game1 has 4 turns, game2 has 2
        for
          maxThree <- getJson(client, "/api/games?max_turns=3")
          asc      <- getJson(client, "/api/games?sort=total_turns&order=asc")
        yield
          assertEquals(idsOf(maxThree), Vector(Right(game2.toString)))
          assertEquals(idsOf(asc), Vector(Right(game2.toString), Right(game1.toString)))
      }
    }

  test("GET /api/games rejects out-of-range limit and offset with 400"):
    withContainers { pg =>
      withClient(pg) { client =>
        def statusOf(uri: String): IO[Status] =
          client.run(Request[IO](Method.GET, Uri.unsafeFromString(uri))).use(r => IO.pure(r.status))
        for
          tooLarge <- statusOf("/api/games?limit=500")
          negative <- statusOf("/api/games?offset=-1")
        yield
          assertEquals(tooLarge, Status.BadRequest)
          assertEquals(negative, Status.BadRequest)
      }
    }

  test("GET /api/games/{id} returns a FastAPI-style 404 for unknown games"):
    withContainers { pg =>
      withClient(pg) { client =>
        client.run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/games/$missing"))).use {
          response =>
            assertEquals(response.status, Status.NotFound)
            response.as[String].map { body =>
              assertEquals(
                parse(body).flatMap(_.hcursor.get[String]("detail")),
                Right("Game not found")
              )
            }
        }
      }
    }

  test("GET /api/players orders by the rating taken from the most recent game"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/api/players").map { json =>
          val players = json.asArray.getOrElse(fail("expected a JSON array"))
          assertEquals(players.size, 2)
          val first = players.head.hcursor
          // alice's latest game is game1 (white, rating 1500); game2's 1450 is older
          assertEquals(first.get[String]("username"), Right("alice"))
          assertEquals(first.get[Int]("rating_classic"), Right(1500))
          val second = players(1).hcursor
          assertEquals(second.get[String]("username"), Right("bob"))
          assertEquals(second.get[Int]("rating_classic"), Right(1480))
        }
      }
    }

  test("GET /api/players filters by username substring"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/api/players?username=ALI").map { json =>
          assertEquals(json.asArray.map(_.size), Some(1))
        }
      }
    }

  test("GET /api/players/{id} returns the player and 404 for unknown ids"):
    withContainers { pg =>
      withClient(pg) { client =>
        for
          aliceJson <- getJson(client, s"/api/players/$alice")
          status    <- client
            .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/players/$missing")))
            .use(r => IO.pure(r.status))
        yield
          assertEquals(aliceJson.hcursor.get[String]("username"), Right("alice"))
          assertEquals(status, Status.NotFound)
      }
    }

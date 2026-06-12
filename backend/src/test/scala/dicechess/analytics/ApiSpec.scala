package dicechess.analytics

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
                                    white_rating, black_rating, mode, result, total_turns,
                                    time_initial_sec, time_increment_sec,
                                    initial_stake_amount, final_stake_amount,
                                    white_money_delta, black_money_delta, stake_currency,
                                    started_at, metadata_json)
                 VALUES ($game1, 'dicechess.com', $alice, $bob, 1500, 1480, 'x2', 1, 4,
                         180, 2, 10, 20, ${BigDecimal("12.5")}, ${BigDecimal("-12.5")}, 'USD',
                         $t1, '{"tournament": "summer"}'::jsonb)""".update.run
      _ <- sql"""INSERT INTO games (id, source, black_player_id, black_rating, mode,
                                    result, total_turns, started_at)
                 VALUES ($game2, 'dicechess.com', $alice, 1450, 'classic', -1, 2, $t2)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id,
                                    dice_sorted, played_moves, position_after_id, thinking_time_ms)
                 VALUES ($game1, 1, 'w', $p1, '125', ARRAY['e2e4','g1f3'], $p2, 1500)""".update.run
      _ <- sql"""INSERT INTO turns (game_id, turn_number, active_color, position_id, dice_sorted)
                 VALUES ($game1, 2, 'b', $p2, '336')""".update.run
      _ <- sql"""INSERT INTO game_events (game_id, sequence_number, turn_number, event_type,
                                          actor_color, clock_white_ms, clock_black_ms, payload)
                 VALUES ($game1, 1, 1, 'DOUBLE_OFFER', 'w', 170000, 165000,
                         '{"bank": 100}'::jsonb)""".update.run
    yield ()

  private def withClient[A](pg: PostgreSQLContainer)(run: Client[IO] => IO[A]): IO[A] =
    val app = Routes(transactor(pg), List("http://localhost:5173")).httpApp
    run(Client.fromHttpApp(app))

  private def getJson(client: Client[IO], uri: String): IO[Json] =
    client
      .run(Request[IO](Method.GET, Uri.unsafeFromString(uri)))
      .use { response =>
        assertEquals(response.status, Status.Ok)
        response.as[String]
      }
      .map(body => parse(body).fold(throw _, identity))

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

  test("GET /api/games lists games ordered by started_at desc with nested players"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, "/api/games").map { json =>
          val games = json.asArray.getOrElse(fail("expected a JSON array"))
          assertEquals(games.size, 2)
          val first = games.head.hcursor
          assertEquals(first.get[String]("id"), Right(game1.toString))
          assertEquals(first.get[String]("mode"), Right("x2"))
          assertEquals(first.get[Int]("white_rating"), Right(1500))
          assertEquals(first.get[BigDecimal]("white_money_delta"), Right(BigDecimal("12.5")))
          assertEquals(first.downField("white_player").get[String]("username"), Right("alice"))
          assertEquals(first.downField("black_player").get[String]("player_type"), Right("bot"))
          assertEquals(games(1).hcursor.get[String]("id"), Right(game2.toString))
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
          assertEquals(byBob.asArray.map(_.size), Some(1))
          assertEquals(byMin.asArray.map(_.size), Some(1))
          assertEquals(
            byMin.asArray.flatMap(_.headOption).map(_.hcursor.get[String]("id")),
            Some(Right(game1.toString))
          )
      }
    }

  test("GET /api/games/{id} returns the full detail with turns and events"):
    withContainers { pg =>
      withClient(pg) { client =>
        getJson(client, s"/api/games/$game1").map { json =>
          val c = json.hcursor
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
          val games = json.asArray.getOrElse(fail("expected a JSON array"))
          assertEquals(games.size, 1)
          assertEquals(games.head.hcursor.get[String]("id"), Right(game2.toString))
        }
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
          status <- client
            .run(Request[IO](Method.GET, Uri.unsafeFromString(s"/api/players/$missing")))
            .use(r => IO.pure(r.status))
        yield
          assertEquals(aliceJson.hcursor.get[String]("username"), Right("alice"))
          assertEquals(status, Status.NotFound)
      }
    }

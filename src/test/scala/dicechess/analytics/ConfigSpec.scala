package dicechess.analytics

import com.comcast.ip4s.port

class ConfigSpec extends munit.FunSuite:

  test("parses the asyncpg URL form used by the Python app"):
    val result = AppConfig.parseDatabaseUrl(
      "postgresql+asyncpg://dicechess_user:secret@db:5432/dicechess_analytics"
    )
    assertEquals(
      result,
      Right(
        DbConfig("jdbc:postgresql://db:5432/dicechess_analytics", "dicechess_user", "secret")
      )
    )

  test("parses the short postgres:// form and defaults the port"):
    val result = AppConfig.parseDatabaseUrl("postgres://u:p@example.org/games")
    assertEquals(result, Right(DbConfig("jdbc:postgresql://example.org:5432/games", "u", "p")))

  test("rejects URLs without a database name"):
    assert(AppConfig.parseDatabaseUrl("postgresql://u:p@host:5432/").isLeft)

  test("rejects unsupported schemes"):
    assert(AppConfig.parseDatabaseUrl("mysql://u:p@host/db").isLeft)

  test("reports malformed URLs instead of throwing"):
    assert(AppConfig.parseDatabaseUrl("postgresql://u:p@host:5432/db with spaces").isLeft)

  test("loads defaults compatible with docker-compose when no env is set"):
    val config = AppConfig.load(Map("MOCK_AUTH" -> "true"))
    assertEquals(
      config.map(_.db.jdbcUrl),
      Right("jdbc:postgresql://localhost:5432/dicechess_analytics")
    )
    assertEquals(config.map(_.port), Right(port"8000"))
    assertEquals(config.map(_.dbPoolSize), Right(16))
    assertEquals(
      config.map(_.corsOrigins),
      Right(List("http://localhost:5173", "http://localhost:3000"))
    )

  test("parses CORS_ORIGINS as a comma-separated list"):
    val config = AppConfig.load(
      Map("MOCK_AUTH" -> "true", "CORS_ORIGINS" -> "https://a.example, https://b.example")
    )
    assertEquals(config.map(_.corsOrigins), Right(List("https://a.example", "https://b.example")))

  test("fails fast on an invalid HTTP_PORT instead of silently falling back"):
    assert(AppConfig.load(Map("MOCK_AUTH" -> "true", "HTTP_PORT" -> "99999")).isLeft)
    assert(AppConfig.load(Map("MOCK_AUTH" -> "true", "HTTP_PORT" -> "not-a-port")).isLeft)

  test("fails fast on an invalid DB_POOL_SIZE"):
    assert(AppConfig.load(Map("MOCK_AUTH" -> "true", "DB_POOL_SIZE" -> "0")).isLeft)
    assert(AppConfig.load(Map("MOCK_AUTH" -> "true", "DB_POOL_SIZE" -> "many")).isLeft)

  test("accepts a custom DB_POOL_SIZE"):
    assertEquals(
      AppConfig.load(Map("MOCK_AUTH" -> "true", "DB_POOL_SIZE" -> "32")).map(_.dbPoolSize),
      Right(32)
    )

  test("rejects load if SECRET_KEY is missing and MOCK_AUTH is false"):
    assert(AppConfig.load(Map("MOCK_AUTH" -> "false")).isLeft)

  test("allows load without SECRET_KEY if MOCK_AUTH is true"):
    assert(AppConfig.load(Map("MOCK_AUTH" -> "true")).isRight)

  test("requires the full Google OAuth config when MOCK_AUTH is false"):
    val complete = Map(
      "SECRET_KEY"           -> "s",
      "GOOGLE_CLIENT_ID"     -> "id",
      "GOOGLE_CLIENT_SECRET" -> "secret",
      "GOOGLE_REDIRECT_URI"  -> "http://localhost:8000/api/auth/callback"
    )
    assert(AppConfig.load(complete).isRight)
    assert(AppConfig.load(complete - "GOOGLE_CLIENT_ID").isLeft)
    assert(AppConfig.load(complete - "GOOGLE_CLIENT_SECRET").isLeft)
    assert(AppConfig.load(complete - "GOOGLE_REDIRECT_URI").isLeft)

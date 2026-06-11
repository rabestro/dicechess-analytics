package dicechess.analytics

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

  test("loads defaults compatible with docker-compose when no env is set"):
    val config = AppConfig.load(Map.empty)
    assertEquals(
      config.map(_.db.jdbcUrl),
      Right("jdbc:postgresql://localhost:5432/dicechess_analytics")
    )
    assertEquals(config.map(_.port), Right(8000))
    assertEquals(
      config.map(_.corsOrigins),
      Right(List("http://localhost:5173", "http://localhost:3000"))
    )

  test("parses CORS_ORIGINS as a comma-separated list"):
    val config = AppConfig.load(Map("CORS_ORIGINS" -> "https://a.example, https://b.example"))
    assertEquals(config.map(_.corsOrigins), Right(List("https://a.example", "https://b.example")))

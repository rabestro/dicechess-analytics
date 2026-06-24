package dicechess.analytics

import java.net.URI

import com.comcast.ip4s.{Host, Port}

/** Database connection settings in JDBC form. */
final case class DbConfig(jdbcUrl: String, user: String, password: String)

/** Application settings loaded from environment variables.
  *
  * Mirrors the FastAPI configuration: `DATABASE_URL` (any of the `postgres://`, `postgresql://`,
  * `postgresql+asyncpg://` forms) or the discrete `POSTGRES_*` variables, plus `CORS_ORIGINS` as a
  * comma-separated list. Invalid values fail the load instead of silently falling back.
  */
final case class AppConfig(
    db: DbConfig,
    host: Host,
    port: Port,
    corsOrigins: List[String],
    dbPoolSize: Int,
    // Bearer secret required to write via POST /api/games. None ⇒ writes are rejected
    // (closed by default); reads are unaffected.
    ingestToken: Option[String],
    // Bearer secret required to write opening-book favorites (PUT/DELETE /api/opening-book/favorites).
    // None ⇒ curation writes are rejected; the read endpoint (GET) is always public.
    curatorToken: Option[String]
)

object AppConfig:

  def load(env: Map[String, String] = sys.env): Either[String, AppConfig] =
    for
      db   <- dbConfig(env)
      host <- parseHost(env.getOrElse("HTTP_HOST", "0.0.0.0"))
      port <- parsePort(env.getOrElse("HTTP_PORT", "8000"))
      pool <- parsePoolSize(env.getOrElse("DB_POOL_SIZE", "16"))
    yield AppConfig(
      db = db,
      host = host,
      port = port,
      corsOrigins = env
        .get("CORS_ORIGINS")
        .map(_.split(',').map(_.trim).filter(_.nonEmpty).toList)
        .getOrElse(List("http://localhost:5173", "http://localhost:3000")),
      dbPoolSize = pool,
      ingestToken = env.get("INGEST_TOKEN").filter(_.nonEmpty),
      curatorToken = env.get("CURATION_TOKEN").filter(_.nonEmpty)
    )

  private def parseHost(value: String): Either[String, Host] =
    Host.fromString(value).toRight(s"Invalid HTTP_HOST: $value")

  private def parsePort(value: String): Either[String, Port] =
    value.toIntOption.flatMap(Port.fromInt).toRight(s"Invalid HTTP_PORT: $value")

  private def parsePoolSize(value: String): Either[String, Int] =
    value.toIntOption.filter(_ > 0).toRight(s"Invalid DB_POOL_SIZE: $value")

  private def dbConfig(env: Map[String, String]): Either[String, DbConfig] =
    env.get("DATABASE_URL") match
      case Some(url) => parseDatabaseUrl(url)
      case None      => Right(fromDiscreteVars(env))

  /** Accepts `postgres://user:pass@host:port/db` style URLs (including the `postgresql+asyncpg://`
    * form used by the Python app) and converts them to JDBC.
    */
  def parseDatabaseUrl(url: String): Either[String, DbConfig] =
    val normalized = url
      .replaceFirst("^postgresql\\+[a-z0-9]+://", "postgresql://")
      .replaceFirst("^postgres://", "postgresql://")
    if !normalized.startsWith("postgresql://") then Left(s"Unsupported DATABASE_URL scheme: $url")
    else
      try
        val uri              = URI("dummy://" + normalized.stripPrefix("postgresql://"))
        val userInfo         = Option(uri.getUserInfo).getOrElse("")
        val (user, password) = userInfo.split(":", 2) match
          case Array(u, p) => (u, p)
          case Array(u)    => (u, "")
          case _           => ("", "")
        val host = Option(uri.getHost).getOrElse("localhost")
        val port = if uri.getPort == -1 then 5432 else uri.getPort
        val db   = Option(uri.getPath).getOrElse("").stripPrefix("/")
        if db.isEmpty then Left(s"DATABASE_URL has no database name: $url")
        else Right(DbConfig(s"jdbc:postgresql://$host:$port/$db", user, password))
      catch case e: java.net.URISyntaxException => Left(s"Malformed DATABASE_URL: ${e.getMessage}")

  private def fromDiscreteVars(env: Map[String, String]): DbConfig =
    val host = env.getOrElse("POSTGRES_HOST", "localhost")
    val port = env.get("POSTGRES_PORT").flatMap(_.toIntOption).getOrElse(5432)
    val db   = env.getOrElse("POSTGRES_DB", "dicechess_analytics")
    DbConfig(
      jdbcUrl = s"jdbc:postgresql://$host:$port/$db",
      user = env.getOrElse("POSTGRES_USER", "dicechess_user"),
      password = env.getOrElse("POSTGRES_PASSWORD", "dicechess_password")
    )

ThisBuild / organization := "lv.id.jc"
ThisBuild / version      := "0.1.0-SNAPSHOT"
ThisBuild / scalaVersion := "3.8.3"

ThisBuild / description := "Dice Chess Analytics backend: REST API over the games database."

// The engine artifact lives in GitHub Packages, which requires authentication
// even for public packages (read:packages scope).
ThisBuild / resolvers += "GitHub Packages (dicechess-engine)" at
  "https://maven.pkg.github.com/rabestro/dicechess-engine-scala"

// Credentials for that resolver. `credentials` is an sbt *setting*, so it is evaluated
// on every load — even for offline tasks like `compile` or `clean` with everything
// cached. We therefore keep it free of network calls: GitHub Packages validates only the
// token (the password) and accepts any non-empty username, so there is no need to look
// up the account name. CI exports GITHUB_TOKEN; locally we read it from the gh CLI, which
// returns the token from the OS keychain without touching the network (works offline, and
// the token never lands in a file or a shell profile).
def ghValue(envVar: String, ghArgs: String*): Option[String] =
  sys.env
    .get(envVar)
    .filter(_.nonEmpty)
    .orElse(scala.util.Try(scala.sys.process.Process("gh" +: ghArgs).!!.trim).toOption)
    .filter(_.nonEmpty)

ThisBuild / credentials ++= (for {
  token <- ghValue("GITHUB_TOKEN", "auth", "token")
  user = sys.env.get("GITHUB_ACTOR").filter(_.nonEmpty).getOrElse("git")
} yield Credentials("GitHub Package Registry", "maven.pkg.github.com", user, token)).toSeq

val DiceChessEngineVersion    = "1.2.6"
val ZeroAllocHashingVersion   = "0.16"
val Http4sVersion             = "0.23.30"
val TapirVersion              = "1.11.25"
val DoobieVersion             = "1.0.0-RC9"
val FlywayVersion             = "11.8.2"
val PostgresDriverVersion     = "42.7.7"
val LogbackVersion            = "1.5.18"
val MunitVersion              = "1.3.0"
val MunitCatsEffectVersion    = "2.1.0"
val TestcontainersVersion     = "0.43.0"
val TestcontainersJavaVersion = "1.21.3"
val DockerJavaVersion         = "3.7.1"

lazy val root = (project in file("."))
  .enablePlugins(JavaAppPackaging, BuildInfoPlugin)
  .settings(
    name := "dicechess-analytics-backend",
    // BuildInfo bakes name/version/scalaVersion at compile time; the running API
    // reports the effective version (APP_VERSION env override) at GET /version.
    buildInfoKeys    := Seq[BuildInfoKey](name, version, scalaVersion),
    buildInfoPackage := "dicechess.analytics",
    libraryDependencies ++= Seq(
      // Game rules: the engine is the single source of truth
      "lv.id.jc" %% "dicechess-engine-scala" % DiceChessEngineVersion,

      // Position hashing: xxHash64, bit-compatible with the historical fen_hash values
      "net.openhft" % "zero-allocation-hashing" % ZeroAllocHashingVersion,

      // HTTP server & typed endpoints (Swagger UI served at /docs)
      "org.http4s"                  %% "http4s-ember-server"     % Http4sVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-http4s-server"     % TapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-json-circe"        % TapirVersion,
      "com.softwaremill.sttp.tapir" %% "tapir-swagger-ui-bundle" % TapirVersion,

      // Database access & migrations
      "org.tpolecat"  %% "doobie-core"                % DoobieVersion,
      "org.tpolecat"  %% "doobie-hikari"              % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres"            % DoobieVersion,
      "org.tpolecat"  %% "doobie-postgres-circe"      % DoobieVersion,
      "org.flywaydb"   % "flyway-database-postgresql" % FlywayVersion,
      "org.postgresql" % "postgresql"                 % PostgresDriverVersion,

      // Logging
      "ch.qos.logback" % "logback-classic" % LogbackVersion % Runtime,

      // Testing: real PostgreSQL via testcontainers
      "org.scalameta" %% "munit"                           % MunitVersion           % Test,
      "org.typelevel" %% "munit-cats-effect"               % MunitCatsEffectVersion % Test,
      "org.http4s"    %% "http4s-client"                   % Http4sVersion          % Test,
      "com.dimafeng"  %% "testcontainers-scala-munit"      % TestcontainersVersion  % Test,
      "com.dimafeng"  %% "testcontainers-scala-postgresql" % TestcontainersVersion  % Test,
      // The scala wrapper pins testcontainers-java 1.20.x with docker-java 3.4.x, which
      // speaks Docker API 1.32 — rejected by Docker 29+ daemons (min 1.41). Force newer.
      "org.testcontainers"     % "testcontainers"                % TestcontainersJavaVersion % Test,
      "org.testcontainers"     % "postgresql"                    % TestcontainersJavaVersion % Test,
      "com.github.docker-java" % "docker-java-api"               % DockerJavaVersion         % Test,
      "com.github.docker-java" % "docker-java-transport-zerodep" % DockerJavaVersion         % Test
    ),
    // Strict flags as in the engine, except -Yexplicit-nulls and -language:strictEquality:
    // this module glues Java libraries (JDBC, Flyway, Hikari) where both become impractical.
    scalacOptions ++= Seq(
      "-Werror",
      "-Wunused:all",
      "-deprecation",
      "-feature",
      "-explain"
    ),
    coverageExcludedFiles    := ".*Main\\.scala;.*BuildInfo\\.scala",
    coverageMinimumStmtTotal := 80,
    coverageFailOnMinimum    := true,
    Test / fork              := true,
    // docker-java does not negotiate the API version and defaults to 1.32, which
    // Docker 29+ daemons reject (minimum 1.41). 1.43 is supported by Docker 24+ (2023).
    Test / javaOptions += "-Dapi.version=1.43"
  )

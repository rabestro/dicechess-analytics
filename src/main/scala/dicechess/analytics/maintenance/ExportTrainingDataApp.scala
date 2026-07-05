package dicechess.analytics.maintenance

import java.io.{BufferedWriter, OutputStreamWriter, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.GZIPOutputStream

import cats.effect.{ExitCode, IO, IOApp, Resource}
import doobie.implicits.*

import dicechess.analytics.repository.TrainingExportRepository
import dicechess.analytics.repository.TrainingExportRepository.TrainingRow
import dicechess.analytics.{AppConfig, Database}

/** Exports ML training rows for the EV model to a gzip-compressed CSV file.
  *
  * Usage: `ExportTrainingDataApp [outputPath] [minRating]`
  *   - `outputPath` (default `training_data.csv.gz`).
  *   - `minRating` (default 0 = everything): keep only games where BOTH players are at least this
  *     strong. Unrated games (NULL rating) are excluded when the filter is active.
  *
  * One row per completed, outcome-labeled turn (the explorer's semantics — see
  * [[dicechess.analytics.repository.TrainingExportRepository]]). The export streams through a
  * server-side cursor, so memory stays constant regardless of table size.
  */
object ExportTrainingDataApp extends IOApp:

  private[analytics] val Header =
    "game_id,turn_number,fen,dice,side,moves,result,termination,mode,source," +
      "white_rating,black_rating,white_type,black_type"

  /** RFC 4180: quote a field only when it contains a comma, quote, or line break. */
  private def csvField(value: String): String =
    if value.exists(c => c == ',' || c == '"' || c == '\n' || c == '\r') then
      "\"" + value.replace("\"", "\"\"") + "\""
    else value

  private[analytics] def csvLine(r: TrainingRow): String =
    List(
      r.gameId.toString,
      r.turnNumber.toString,
      r.fen,
      r.dice,
      r.side,
      r.moves,
      r.result.toString,
      r.termination,
      r.mode,
      r.source,
      r.whiteRating.fold("")(_.toString),
      r.blackRating.fold("")(_.toString),
      r.whiteType.getOrElse(""),
      r.blackType.getOrElse("")
    ).map(csvField).mkString(",")

  private def gzipWriter(path: Path): Resource[IO, PrintWriter] =
    Resource.fromAutoCloseable(IO.blocking {
      val gz = GZIPOutputStream(Files.newOutputStream(path))
      PrintWriter(BufferedWriter(OutputStreamWriter(gz, UTF_8), 1 << 20))
    })

  def run(args: List[String]): IO[ExitCode] =
    val outputPath = args.headOption.getOrElse("training_data.csv.gz")
    val minRating  = args.lift(1).flatMap(_.toIntOption).filter(_ > 0)

    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      // Surface the target DB (never the password) so the dataset is not exported from the wrong database by mistake.
      _ <- IO.println(
        s"Exporting training data from ${config.db.jdbcUrl} (user=${config.db.user}); " +
          s"minRating=${minRating.fold("none")(_.toString)} -> $outputPath ..."
      )
      written <- Database.transactor(config.db, 4).use { xa =>
        gzipWriter(Path.of(outputPath)).use { out =>
          for
            _       <- IO.blocking(out.println(Header))
            written <- TrainingExportRepository
              .rows(minRating)
              .transact(xa)
              .chunks
              .evalMap(chunk =>
                IO.blocking(chunk.foreach(row => out.println(csvLine(row)))).as(chunk.size.toLong)
              )
              .compile
              .foldMonoid
            (turns, games) <- TrainingExportRepository.counts(minRating).transact(xa)
            _              <- IO.println(s"Filters match $turns turns across $games games.")
          yield written
        }
      }
      _ <- IO.println(s"Wrote $written training rows to $outputPath")
    yield ExitCode.Success

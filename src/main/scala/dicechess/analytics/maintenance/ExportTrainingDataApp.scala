package dicechess.analytics.maintenance

import java.io.{BufferedWriter, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.UUID
import java.util.zip.GZIPOutputStream

import scala.concurrent.duration.*

import cats.effect.{ExitCode, IO, IOApp, Resource}
import doobie.Transactor
import doobie.implicits.*

import dicechess.analytics.repository.TrainingExportRepository
import dicechess.analytics.repository.TrainingExportRepository.{Filters, TrainingRow}
import dicechess.analytics.{AppConfig, Database}

/** Exports ML training rows for the EV model to a gzip-compressed CSV file.
  *
  * Usage: `ExportTrainingDataApp [outputPath] [minRating] [mode] [termination] [batchSize]`
  *   - `outputPath` (default `training_data.csv.gz`).
  *   - `minRating` (default 0 = everything): keep only games where BOTH players are at least this
  *     strong. Unrated games (NULL rating) are excluded when the filter is active.
  *   - `mode` (default/`-` = both, case-insensitive): restrict to one `games.mode` value (`classic` |
  *     `x2`).
  *   - `termination` (default/`-` = all, case-insensitive): restrict to one `games.termination`
  *     value (`king_captured` | `timeout` | `resign` | `draw_agreement` | `double_declined` |
  *     `unknown`) — e.g. `king_captured` for turns from decisive, over-the-board finishes only.
  *     Pass `-` to leave `mode` unset while still setting `termination` (or vice versa).
  *   - `batchSize` (default 2000): games per page. The export pages through `games.id`
  *     ([[TrainingExportRepository.filteredGameIds]]) instead of running one query over the whole
  *     filter — see that object's doc for why a single query over a wide filter is unsafe.
  *
  * One row per completed, outcome-labeled turn (the explorer's semantics — see
  * [[dicechess.analytics.repository.TrainingExportRepository]]). Each batch streams through a
  * server-side cursor, so memory stays constant regardless of table size; a short pause between
  * batches spreads the read load over time instead of one sustained burst.
  */
object ExportTrainingDataApp extends IOApp:

  private val PauseBetweenBatches = 250.millis

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

  // A plain BufferedWriter, not PrintWriter: PrintWriter swallows IOExceptions (disk full would
  // silently truncate the dataset while the run still reports success); the writer must throw.
  private def gzipWriter(path: Path): Resource[IO, BufferedWriter] =
    Resource.fromAutoCloseable(IO.blocking {
      val gz = GZIPOutputStream(Files.newOutputStream(path))
      BufferedWriter(OutputStreamWriter(gz, UTF_8), 1 << 20)
    })

  private def writeLine(out: BufferedWriter, line: String): Unit =
    out.write(line)
    out.newLine()

  private val ValidModes        = Set("classic", "x2")
  private val ValidTerminations =
    Set("king_captured", "timeout", "resign", "draw_agreement", "double_declined", "unknown")

  /** `0` (the mise default) disables the floor; anything unparseable or negative fails fast — a
    * typo must not silently export the full dataset instead of the intended slice.
    */
  private[analytics] def parseMinRating(arg: Option[String]): Either[String, Option[Int]] =
    arg match
      case None    => Right(None)
      case Some(s) =>
        s.toIntOption match
          case Some(0)          => Right(None)
          case Some(n) if n > 0 => Right(Some(n))
          case _                => Left(s"minRating must be a non-negative integer, got: '$s'")

  /** Blank, absent, or `-` means unfiltered (case-insensitive otherwise); anything else must be one
    * of `valid`, or fail fast — a typo must not silently export zero rows or the wrong slice.
    *
    * `-` is the CLI-safe placeholder for "skip this filter": `mise run db:export-training` builds
    * one space-joined string for `sbt runMain`, where an actually-empty argument collapses into the
    * surrounding whitespace and silently shifts every later positional argument one slot to the
    * left — `-` is always a distinct, non-empty token, so it can't be swallowed that way.
    */
  private[analytics] def parseEnumFilter(
      arg: Option[String],
      valid: Set[String],
      name: String
  ): Either[String, Option[String]] =
    arg.map(_.trim.toLowerCase).filter(v => v.nonEmpty && v != "-") match
      case None                         => Right(None)
      case Some(v) if valid.contains(v) => Right(Some(v))
      case Some(v) => Left(s"$name must be one of ${valid.toList.sorted.mkString(", ")}, got: '$v'")

  /** Fails fast on a non-positive/unparseable batch size — same reasoning as `minRating`: a typo
    * silently falling back to "no batching" would resurrect the exact problem pagination exists to
    * avoid.
    */
  private[analytics] def parseBatchSize(arg: Option[String]): Either[String, Int] =
    arg match
      case None    => Right(2000)
      case Some(s) =>
        s.toIntOption
          .filter(_ > 0)
          .toRight(s"batchSize must be a positive integer, got: '$s'")

  /** Pages through every game matching `filters` in batches of `batchSize`, writing each batch's
    * rows before fetching the next. Returns `(rows written, games covered)`.
    */
  private[analytics] def exportInBatches(
      xa: Transactor[IO],
      filters: Filters,
      batchSize: Int,
      out: BufferedWriter
  ): IO[(Long, Long)] =
    def loop(afterId: Option[UUID], rowsSoFar: Long, gamesSoFar: Long): IO[(Long, Long)] =
      TrainingExportRepository.filteredGameIds(filters, afterId, batchSize).transact(xa).flatMap {
        case Nil      => IO.pure((rowsSoFar, gamesSoFar))
        case batchIds =>
          for
            written <- TrainingExportRepository
              .rowsForGames(batchIds)
              .transact(xa)
              .chunks
              .evalMap(chunk =>
                IO.blocking(chunk.foreach(row => writeLine(out, csvLine(row))))
                  .as(chunk.size.toLong)
              )
              .compile
              .foldMonoid
            totalRows  = rowsSoFar + written
            totalGames = gamesSoFar + batchIds.size
            _ <- IO.println(
              s"  batch of ${batchIds.size} games -> $written rows " +
                s"(running total: $totalRows rows, $totalGames games)"
            )
            _    <- IO.sleep(PauseBetweenBatches)
            next <- loop(Some(batchIds.last), totalRows, totalGames)
          yield next
      }
    loop(None, 0L, 0L)

  def run(args: List[String]): IO[ExitCode] =
    val outputPath = args.headOption.getOrElse("training_data.csv.gz")

    for
      minRating <- IO.fromEither(
        parseMinRating(args.lift(1)).left.map(msg => IllegalArgumentException(msg))
      )
      mode <- IO.fromEither(
        parseEnumFilter(args.lift(2), ValidModes, "mode").left.map(msg =>
          IllegalArgumentException(msg)
        )
      )
      termination <- IO.fromEither(
        parseEnumFilter(args.lift(3), ValidTerminations, "termination").left
          .map(msg => IllegalArgumentException(msg))
      )
      batchSize <- IO.fromEither(
        parseBatchSize(args.lift(4)).left.map(msg => IllegalArgumentException(msg))
      )
      filters = Filters(minRating, mode, termination)
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      // Surface the target DB (never the password) so the dataset is not exported from the wrong database by mistake.
      _ <- IO.println(
        s"Exporting training data from ${config.db.jdbcUrl} (user=${config.db.user}); " +
          s"minRating=${minRating.fold("none")(_.toString)}, mode=${mode.getOrElse("any")}, " +
          s"termination=${termination.getOrElse("any")}, batchSize=$batchSize -> $outputPath ..."
      )
      result <- Database.transactor(config.db, 4).use { xa =>
        gzipWriter(Path.of(outputPath)).use { out =>
          IO.blocking(writeLine(out, Header)) *> exportInBatches(xa, filters, batchSize, out)
        }
      }
      (written, games) = result
      _ <- IO.println(s"Wrote $written training rows across $games games to $outputPath")
    yield ExitCode.Success

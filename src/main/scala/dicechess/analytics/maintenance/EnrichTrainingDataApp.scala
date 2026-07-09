package dicechess.analytics.maintenance

import java.io.{BufferedReader, BufferedWriter, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import cats.effect.{ExitCode, IO, IOApp, Resource}
import cats.syntax.all.*
import fs2.Stream

import dicechess.engine.domain.{Color, FenParser}
import dicechess.engine.search.RichFeatures

/** Enriches an already-exported training CSV (see [[ExportTrainingDataApp]]) with the engine's
  * [[RichFeatures]] columns, appending them to every row.
  *
  * Usage: `EnrichTrainingDataApp [inputPath] [outputPath] [parallelism]`
  *   - `inputPath` (default `training_data.csv.gz`): a gzip CSV produced by the exporter.
  *   - `outputPath` (default `training_data_rich.csv.gz`).
  *   - `parallelism` (default = CPU count): rows enriched concurrently.
  *
  * Why here, and why not in `features.py`: the model's Python feature code can only see material
  * (it has no move generator), so positional signal — mobility, king safety — has to come from the
  * engine. Computing it here, through the same [[RichFeatures]] the search evaluates, is what keeps
  * training and serving on one implementation instead of two that can drift. This is a pure CSV→CSV
  * pass with no database; it is the embarrassingly-parallel, CPU-bound step meant to run over the
  * full dataset on a many-core machine.
  *
  * The reader pulls lines sequentially (one source, cheap), the per-row feature extraction fans out
  * across `parallelism` fibers, and writes are serialized back in input order — so the output rows
  * line up with the input and a re-run is byte-stable.
  *
  * Row parsing splits on `,` directly: the exporter never quotes a field (no exported column
  * contains a comma — FENs and move lists use spaces), so a plain split round-trips exactly. A row
  * whose field count or FEN does not parse fails the whole run rather than silently dropping data.
  */
object EnrichTrainingDataApp extends IOApp:

  private val DefaultInput  = "training_data.csv.gz"
  private val DefaultOutput = "training_data_rich.csv.gz"

  /** The columns [[enrichRow]] reads, resolved from the header by name so a future column reorder
    * can't silently shift them. `width` pins the expected field count for every data row.
    */
  private[analytics] final case class Columns(fen: Int, side: Int, width: Int)

  private[analytics] def parseHeader(header: String): Either[String, Columns] =
    val cols                                     = header.split(",", -1).toList
    def index(name: String): Either[String, Int] =
      cols.indexOf(name) match
        case -1 => Left(s"training CSV header is missing the '$name' column: $header")
        case i  => Right(i)
    (index("fen"), index("side")).mapN((fen, side) => Columns(fen, side, cols.length))

  /** The enriched header: the original columns plus [[RichFeatures.columnNames]]. */
  private[analytics] def enrichedHeader(header: String): String =
    (header :: RichFeatures.columnNames).mkString(",")

  private[analytics] def parseColor(side: String): Either[String, Color] =
    side.trim match
      case "w"   => Right(Color.White)
      case "b"   => Right(Color.Black)
      case other => Left(s"unexpected side value '$other' (expected 'w' or 'b')")

  /** The exporter writes a 4-field FEN (placement, active color, castling, en passant); the engine
    * parser also wants the half/full-move clocks. They don't affect the dice-independent
    * [[RichFeatures]], so append neutral defaults when they're absent, leaving any longer FEN
    * as-is.
    */
  private[analytics] def padFen(fen: String): String =
    if fen.trim.split(' ').length == 4 then s"$fen 0 1" else fen

  /** Appends the [[RichFeatures]] values (in [[RichFeatures.columnNames]] order) to `line`, or a
    * message describing why the row is unusable.
    */
  private[analytics] def enrichRow(columns: Columns, line: String): Either[String, String] =
    val fields = line.split(",", -1)
    if fields.length != columns.width then
      Left(s"row has ${fields.length} fields, expected ${columns.width}: $line")
    else
      (parseColor(fields(columns.side)), FenParser.parse(padFen(fields(columns.fen)))).mapN {
        (color, state) =>
          val features = RichFeatures.extract(state, color).iterator.map(_.toString).mkString(",")
          s"$line,$features"
      }

  private def gzipReader(path: Path): Resource[IO, BufferedReader] =
    Resource.fromAutoCloseable(IO.blocking {
      BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(path)), UTF_8), 1 << 20)
    })

  // A plain BufferedWriter, not PrintWriter: PrintWriter swallows IOExceptions (a full disk would
  // silently truncate the dataset while the run still reports success); the writer must throw.
  private def gzipWriter(path: Path): Resource[IO, BufferedWriter] =
    Resource.fromAutoCloseable(IO.blocking {
      BufferedWriter(
        OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(path)), UTF_8),
        1 << 20
      )
    })

  private def writeLine(out: BufferedWriter, line: String): IO[Unit] =
    IO.blocking { out.write(line); out.newLine() }

  private[analytics] def parseParallelism(arg: Option[String]): Either[String, Int] =
    arg match
      case None    => Right(Runtime.getRuntime.availableProcessors())
      case Some(s) =>
        s.toIntOption.filter(_ > 0).toRight(s"parallelism must be a positive integer, got: '$s'")

  /** Streams data rows through [[enrichRow]] (fanned out over `parallelism`), writing each result
    * in input order. Blank lines (e.g. a trailing newline) carry no row and are skipped; any other
    * unparseable row raises. Returns the number of rows written.
    */
  private def enrichRows(
      in: BufferedReader,
      out: BufferedWriter,
      columns: Columns,
      parallelism: Int
  ): IO[Long] =
    Stream
      .repeatEval(IO.blocking(Option(in.readLine())))
      .unNoneTerminate
      .filter(_.nonEmpty)
      .parEvalMap(parallelism)(line =>
        IO.fromEither(enrichRow(columns, line).leftMap(IllegalArgumentException(_)))
      )
      .evalMap(writeLine(out, _))
      .compile
      .count

  def run(args: List[String]): IO[ExitCode] =
    val inputPath  = args.headOption.getOrElse(DefaultInput)
    val outputPath = args.lift(1).getOrElse(DefaultOutput)
    for
      parallelism <- IO.fromEither(
        parseParallelism(args.lift(2)).leftMap(IllegalArgumentException(_))
      )
      _ <- IO.println(
        s"Enriching $inputPath -> $outputPath with ${RichFeatures.columnNames.size} " +
          s"RichFeatures columns (parallelism=$parallelism) ..."
      )
      written <- (gzipReader(Path.of(inputPath)), gzipWriter(Path.of(outputPath))).tupled.use {
        (in, out) =>
          for
            header <- IO
              .blocking(Option(in.readLine()))
              .flatMap(IO.fromOption(_)(IllegalArgumentException(s"$inputPath is empty")))
            columns <- IO.fromEither(parseHeader(header).leftMap(IllegalArgumentException(_)))
            _       <- writeLine(out, enrichedHeader(header))
            count   <- enrichRows(in, out, columns, parallelism)
          yield count
      }
      _ <- IO.println(s"Wrote $written enriched rows to $outputPath")
    yield ExitCode.Success

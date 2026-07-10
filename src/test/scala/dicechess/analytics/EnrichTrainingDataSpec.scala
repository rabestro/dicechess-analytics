package dicechess.analytics

import java.io.{BufferedReader, InputStreamReader, OutputStreamWriter}
import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}
import java.util.zip.{GZIPInputStream, GZIPOutputStream}

import scala.util.Using

import cats.effect.IO
import munit.CatsEffectSuite

import dicechess.analytics.maintenance.EnrichTrainingDataApp
import dicechess.analytics.maintenance.EnrichTrainingDataApp.Columns
import dicechess.analytics.maintenance.ExportTrainingDataApp
import dicechess.engine.search.{KcpFeatures, RichFeatures}

/** [[EnrichTrainingDataApp]] — pure row/header logic plus one end-to-end gzip round-trip. No
  * database: enrichment is a CSV→CSV pass, so it needs no container.
  */
class EnrichTrainingDataSpec extends CatsEffectSuite:

  // The real exporter header keeps the test's column layout in lock-step with production.
  private val header    = ExportTrainingDataApp.Header
  private val baseWidth = header.split(",", -1).length
  private val columns   = EnrichTrainingDataApp.parseHeader(header).fold(fail(_), identity)
  private val rich      = EnrichTrainingDataApp.featureSet("rich").fold(fail(_), identity)
  private val kcp       = EnrichTrainingDataApp.featureSet("kcp").fold(fail(_), identity)

  private def mobilityIndex   = baseWidth + RichFeatures.columnNames.indexOf("mobility_diff")
  private def kingSafetyIndex = baseWidth + RichFeatures.columnNames.indexOf("king_safety_diff")

  // A 14-field row (exporter layout): fen at index 2, side at index 4. FENs are 4-field, as exported.
  private def row(fen: String, side: String): String =
    s"g,1,$fen,PQR,$side,e2e4,1,king_captured,classic,test,2000,2000,human,human"

  private val startRow = row("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -", "w")
  private val queenRow = row("4k3/8/8/8/3Q4/8/8/4K3 w - -", "w") // White queen vs lone Black king

  test("parseHeader resolves fen and side by name and pins the row width"):
    assertEquals(columns, Columns(fen = 2, side = 4, width = baseWidth))

  test("parseHeader fails when a needed column is absent"):
    assert(EnrichTrainingDataApp.parseHeader("game_id,turn_number,dice,side").isLeft)

  test("enrichedHeader appends the RichFeatures columns after the originals"):
    assertEquals(
      EnrichTrainingDataApp.enrichedHeader(header, rich),
      header + "," + RichFeatures.columnNames.mkString(",")
    )

  test("parseColor maps w/b and rejects anything else"):
    assert(EnrichTrainingDataApp.parseColor("w").isRight)
    assert(EnrichTrainingDataApp.parseColor("b").isRight)
    assert(EnrichTrainingDataApp.parseColor("x").isLeft)

  test("padFen adds neutral clocks to a 4-field FEN and leaves a full one untouched"):
    assertEquals(
      EnrichTrainingDataApp.padFen("4k3/8/8/8/8/8/8/4K3 w - -"),
      "4k3/8/8/8/8/8/8/4K3 w - - 0 1"
    )
    assertEquals(
      EnrichTrainingDataApp.padFen("4k3/8/8/8/8/8/8/4K3 w - - 5 9"),
      "4k3/8/8/8/8/8/8/4K3 w - - 5 9"
    )

  test("parseParallelism defaults on absent/blank and validates otherwise"):
    assert(EnrichTrainingDataApp.parseParallelism(None).isRight)
    assert(
      EnrichTrainingDataApp.parseParallelism(Some("   ")).isRight
    ) // a shell wrapper's empty arg
    assertEquals(EnrichTrainingDataApp.parseParallelism(Some("4")), Right(4))
    assert(EnrichTrainingDataApp.parseParallelism(Some("0")).isLeft)
    assert(EnrichTrainingDataApp.parseParallelism(Some("nope")).isLeft)

  test("enrichRow appends columnNames-many values and preserves the original row"):
    val enriched = EnrichTrainingDataApp.enrichRow(columns, rich, startRow).fold(fail(_), identity)
    assert(enriched.startsWith(startRow + ","))
    val fields = enriched.split(",", -1)
    assertEquals(fields.length, baseWidth + RichFeatures.columnNames.length)
    // Start position is positionally symmetric: both positional diffs are zero.
    assertEquals(fields(mobilityIndex).toFloat, 0f)
    assertEquals(fields(kingSafetyIndex).toFloat, 0f)

  test("enrichRow gets mobility_diff sign right for a lopsided position"):
    val fields =
      EnrichTrainingDataApp
        .enrichRow(columns, rich, queenRow)
        .fold(fail(_), identity)
        .split(",", -1)
    assert(fields(mobilityIndex).toFloat > 0f, "White (queen vs lone king) must have more mobility")

  test("enrichRow rejects a row with the wrong field count"):
    assert(EnrichTrainingDataApp.enrichRow(columns, rich, "too,few,fields").isLeft)

  test("enrichRow rejects an unparseable FEN"):
    assert(EnrichTrainingDataApp.enrichRow(columns, rich, row("not-a-fen", "w")).isLeft)

  // --- end-to-end gzip round-trip -------------------------------------------------------------

  private def writeGzip(path: Path, lines: List[String]): IO[Unit] =
    IO.blocking {
      Using.resource(OutputStreamWriter(GZIPOutputStream(Files.newOutputStream(path)), UTF_8)) {
        writer => lines.foreach(line => writer.write(line + "\n"))
      }
    }

  private def readGzip(path: Path): IO[List[String]] =
    IO.blocking {
      Using.resource(
        BufferedReader(InputStreamReader(GZIPInputStream(Files.newInputStream(path)), UTF_8))
      )(reader => Iterator.continually(reader.readLine()).takeWhile(_ != null).toList)
    }

  private def tempFiles: IO[(Path, Path)] =
    IO.blocking {
      val in  = Files.createTempFile("enrich-in", ".csv.gz")
      val out = Files.createTempFile("enrich-out", ".csv.gz")
      in.toFile.deleteOnExit()
      out.toFile.deleteOnExit()
      (in, out)
    }

  test("run enriches every row, keeps input order, and augments the header"):
    for
      (in, out) <- tempFiles
      _         <- writeGzip(in, List(header, startRow, queenRow))
      _         <- EnrichTrainingDataApp.run(List(in.toString, out.toString, "2"))
      lines     <- readGzip(out)
    yield
      assertEquals(lines.head, EnrichTrainingDataApp.enrichedHeader(header, rich))
      assertEquals(lines.length, 3)
      assert(lines(1).startsWith(startRow + ","), "first data row preserved and in order")
      assert(lines(2).startsWith(queenRow + ","), "second data row preserved and in order")
      assert(lines(2).split(",", -1)(mobilityIndex).toFloat > 0f)

  test("run fails fast on an empty input file"):
    for
      (in, out) <- tempFiles
      _         <- writeGzip(in, Nil)
      result    <- EnrichTrainingDataApp.run(List(in.toString, out.toString)).attempt
    yield assert(result.isLeft, "empty input must raise, not produce an empty output")

  test("run fails fast when the header lacks a required column"):
    for
      (in, out) <- tempFiles
      _         <- writeGzip(in, List("game_id,turn_number,dice", "g,1,PQR"))
      result    <- EnrichTrainingDataApp.run(List(in.toString, out.toString)).attempt
    yield assert(result.isLeft)

  test("run fails fast on a malformed data row"):
    for
      (in, out) <- tempFiles
      _         <- writeGzip(in, List(header, "too,few,fields"))
      result    <- EnrichTrainingDataApp.run(List(in.toString, out.toString)).attempt
    yield assert(result.isLeft)

  test("run refuses to write its output over its own input"):
    for
      (in, _) <- tempFiles
      _       <- writeGzip(in, List(header, startRow))
      result  <- EnrichTrainingDataApp.run(List(in.toString, in.toString)).attempt
    yield assert(result.isLeft, "writing over the input would truncate it before any row is read")

  test("featureSet resolves rich/kcp, defaults to rich, rejects the unknown"):
    assertEquals(EnrichTrainingDataApp.featureSet("").map(_.name), Right("rich"))
    assertEquals(EnrichTrainingDataApp.featureSet("KCP").map(_.name), Right("kcp"))
    assert(EnrichTrainingDataApp.featureSet("bogus").isLeft)

  test("enrichRow with the kcp set appends KcpFeatures columns and king_capture_danger fires"):
    // Black rook e2 bears on the White (mover) king e1 — the mover's king is capturable.
    val threatened = row("4k3/8/8/8/8/8/4r3/4K3 w - -", "w")
    val fields     =
      EnrichTrainingDataApp
        .enrichRow(columns, kcp, threatened)
        .fold(fail(_), identity)
        .split(",", -1)
    assertEquals(fields.length, baseWidth + KcpFeatures.columnNames.length)
    val dangerIndex = baseWidth + KcpFeatures.columnNames.indexOf("king_capture_danger")
    assert(fields(dangerIndex).toFloat > 0f, "the mover's king is under a capturable threat")

  test("run with featureSet=kcp writes the KcpFeatures header and columns"):
    for
      (in, out) <- tempFiles
      _         <- writeGzip(in, List(header, startRow))
      _         <- EnrichTrainingDataApp.run(List(in.toString, out.toString, "2", "kcp"))
      lines     <- readGzip(out)
    yield
      assertEquals(lines.head, EnrichTrainingDataApp.enrichedHeader(header, kcp))
      assertEquals(lines(1).split(",", -1).length, baseWidth + KcpFeatures.columnNames.length)

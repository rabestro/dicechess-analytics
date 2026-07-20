package dicechess.analytics.maintenance

import java.io.{File, PrintWriter}
import java.nio.charset.StandardCharsets.UTF_8

import cats.effect.{ExitCode, IO, IOApp}
import doobie.implicits.*

import dicechess.analytics.{AppConfig, Database}
import dicechess.analytics.repository.PositionsRepository

/** Regenerates the opening-book visual catalog docs page from the current book + corpus stats
  * (issue #251).
  *
  * Usage: `GenerateOpeningBookCatalogApp [minGames] [minRating]` — same eligibility knobs and
  * defaults as [[ExportOpeningBookApp]], so the catalog reflects the same book an export with the
  * same arguments would produce. Writes `docs/src/content/docs/opening-book-catalog.md`, a
  * committed artifact meant to be regenerated (and diff-reviewed) in the same PR as a book
  * regeneration.
  */
object GenerateOpeningBookCatalogApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val minGames   = args.headOption.flatMap(_.toIntOption).getOrElse(100)
    val minRating  = args.lift(1).flatMap(_.toIntOption).getOrElse(2000)
    val ratingArg  = Option.when(minRating > 0)(minRating)
    val outputFile = File("docs/src/content/docs/opening-book-catalog.md")

    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      _      <- IO.println(
        s"Generating opening-book catalog from ${config.db.jdbcUrl} (user=${config.db.user}); " +
          s"minGames=$minGames, minRating=${ratingArg.fold("none")(_.toString)} -> $outputFile ..."
      )
      entries <- Database.transactor(config.db, 4).use { xa =>
        PositionsRepository.openingBookEntries(minGames, ratingArg).transact(xa)
      }
      page = OpeningBookCatalogGenerator.render(entries)
      _ <- IO.blocking {
        outputFile.getParentFile.mkdirs()
        val pw = PrintWriter(outputFile, UTF_8)
        try pw.print(page)
        finally pw.close()
      }
      _ <- IO.println(s"Wrote ${entries.size} opening-book catalog entries to $outputFile")
    yield ExitCode.Success

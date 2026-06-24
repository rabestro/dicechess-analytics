package dicechess.analytics.maintenance

import java.nio.charset.StandardCharsets.UTF_8
import java.nio.file.{Files, Path}

import cats.effect.{ExitCode, IO, IOApp}
import doobie.implicits.*
import io.circe.Json

import dicechess.analytics.{AppConfig, Database}
import dicechess.analytics.repository.PositionsRepository

/** Exports the opening book to a JSON file consumed by the engine's `OpeningBookBot`.
  *
  * Usage: `ExportOpeningBookApp [minGames] [minRating] [outputPath]`
  *   - `minGames` (default 100): minimum games a continuation must have, after filtering, to be
  *     eligible.
  *   - `minRating` (default 2000; pass `0` to disable): keep only games where BOTH players are at
  *     least this strong, so the book reflects strong play rather than the whole population.
  *   - `outputPath` (default `opening_book.json`).
  *
  * Each entry is `PositionsRepository.openingBook`'s canonical key → comma-separated moves, i.e.
  * exactly what `dicechess-engine`'s `OpeningBook.key` produces. Output keys are sorted for stable
  * diffs.
  */
object ExportOpeningBookApp extends IOApp:

  def run(args: List[String]): IO[ExitCode] =
    val minGames   = args.headOption.flatMap(_.toIntOption).getOrElse(100)
    val minRating  = args.lift(1).flatMap(_.toIntOption).getOrElse(2000)
    val outputPath = args.lift(2).getOrElse("opening_book.json")
    val ratingArg  = Option.when(minRating > 0)(minRating)

    for
      config <- IO.fromEither(AppConfig.load().left.map(msg => IllegalArgumentException(msg)))
      // Surface the target DB (never the password) so the book is not exported from the wrong database by mistake.
      _ <- IO.println(
        s"Exporting opening book from ${config.db.jdbcUrl} (user=${config.db.user}); " +
          s"minGames=$minGames, minRating=${ratingArg.fold("none")(_.toString)} -> $outputPath ..."
      )
      book <- Database.transactor(config.db, 4).use { xa =>
        PositionsRepository.openingBook(minGames, ratingArg).transact(xa)
      }
      json = Json.fromFields(
        book.toList.sortBy(_._1).map((key, moves) => key -> Json.fromString(moves))
      )
      _ <- IO.blocking(Files.write(Path.of(outputPath), json.spaces2.getBytes(UTF_8)))
      _ <- IO.println(s"Wrote ${book.size} opening-book entries to $outputPath")
    yield ExitCode.Success

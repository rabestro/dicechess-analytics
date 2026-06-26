package dicechess.analytics

import java.nio.charset.StandardCharsets.UTF_8

import net.openhft.hashing.LongHashFunction

import dicechess.engine.movegen.Dfen

/** Position identity helpers.
  *
  * A position is identified by its *normalized* FEN — the first four FEN fields (placement, active
  * color, castling, en passant). The halfmove and fullmove counters are dropped: they do not change
  * the tactical identity of a position, and on some sources they are unreliable. The en-passant
  * field is canonicalised to the X-FEN form (a target is kept only when it can actually be
  * captured) so positions that differ only by an uncapturable en-passant target — common when a
  * Dice Chess turn double-pushes mid-turn — share one identity.
  */
object Fen:

  /** The four normalized-FEN fields, as stored in `positions`. */
  final case class Fields(
      piecePlacement: String,
      activeColor: String,
      castling: String,
      enPassant: String
  )

  /** The canonical normalized FEN: the first four FEN fields with the en-passant target reduced to
    * its X-FEN form (kept only when the side to move can actually capture it).
    *
    * Delegates to the engine's [[dicechess.engine.movegen.Dfen]] so `normalized_fen` stays
    * byte-identical to the engine's `OpeningBook.key`. Falls back to the plain first-four-fields
    * form for inputs the engine cannot parse (short or malformed FENs).
    */
  def normalize(fen: String): String =
    Dfen.normalize(fen).getOrElse(plainFields(fen))

  /** The first four FEN fields, padding with `-` if fewer are present — the fallback when `fen` is
    * not a parseable position.
    *
    * Splitting on `\s+` and dropping empties tolerates leading or consecutive whitespace (unlike
    * Python's `str.split()`, Java's `split` keeps empty tokens).
    */
  private def plainFields(fen: String): String =
    val parts = fen.split("\\s+").filter(_.nonEmpty).take(4)
    (parts ++ Array.fill(4 - parts.length)("-")).mkString(" ")

  /** Signed xxHash64 (seed 0) of the normalized FEN.
    *
    * Bit-compatible with the historical `fen_hash` data (originally produced by Python's
    * `xxhash.xxh64(...).intdigest()`): PostgreSQL `bigint` is the JVM `Long`, so the digest's raw
    * 64 bits map onto the column directly with no unsigned-to-signed conversion step.
    */
  def hash(normalizedFen: String): Long =
    LongHashFunction.xx().hashBytes(normalizedFen.getBytes(UTF_8))

  /** Split a normalized FEN into its stored columns, padding with `-` to four fields so it stays
    * safe even if handed a non-normalized string.
    */
  def fields(normalizedFen: String): Fields =
    val p = normalizedFen.split(" ").padTo(4, "-")
    Fields(p(0), p(1), p(2), p(3))

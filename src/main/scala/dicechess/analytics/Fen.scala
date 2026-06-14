package dicechess.analytics

import java.nio.charset.StandardCharsets.UTF_8

import net.openhft.hashing.LongHashFunction

/** Position identity helpers.
  *
  * A position is identified by its *normalized* FEN — the first four FEN fields (placement, active
  * color, castling, en passant). The halfmove and fullmove counters are dropped: they do not change
  * the tactical identity of a position, and on some sources they are unreliable.
  */
object Fen:

  /** The four normalized-FEN fields, as stored in `positions`. */
  final case class Fields(
      piecePlacement: String,
      activeColor: String,
      castling: String,
      enPassant: String
  )

  /** Keep the first four FEN fields, padding with `-` if fewer are present.
    *
    * Splitting on `\s+` and dropping empties tolerates leading or consecutive whitespace (unlike
    * Python's `str.split()`, Java's `split` keeps empty tokens).
    */
  def normalize(fen: String): String =
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

  /** Split a normalized FEN (always four fields, see [[normalize]]) into its stored columns. */
  def fields(normalizedFen: String): Fields =
    val p = normalizedFen.split(" ")
    Fields(p(0), p(1), p(2), p(3))

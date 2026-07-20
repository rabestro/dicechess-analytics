package dicechess.analytics.maintenance

import java.net.URLEncoder

import dicechess.analytics.Fen
import dicechess.analytics.repository.PositionsRepository.OpeningBookEntry

/** Renders the opening book — with the corpus stats that justified each pick — as a visual
  * Starlight catalog page: a board diagram, the rolled dice, and the booked continuation for every
  * entry, grouped by side to move. Without this, a stale or surprising book entry is invisible
  * until a bot plays it (issue #251).
  *
  * Board images and the `@generated` marker follow the same idiom as the engine repo's
  * `DocGenerator.scala` / `KingCaptureDocGenerator.scala` — a Lichess FEN-gif embed, no local board
  * rendering. A Dice Chess turn is a chain of up to 3 micro-moves though, and Lichess's export has
  * no arrow support (only a single-move `lastMove` square highlight) — so each micro-move's arrow
  * is drawn ourselves as a thin, transparent SVG overlay on top of the (still Lichess-rendered)
  * board image, color-coded by hop order so a multi-move turn reads as a sequence.
  */
object OpeningBookCatalogGenerator:

  /** Lichess renders a clean, marginless 720x720 board (90px/square, verified against a sample
    * export) — coordinate labels are drawn inside the corner squares, not in a separate margin.
    */
  private val boardPx  = 720.0
  private val squarePx = boardPx / 8

  /** One arrow color per hop order (green, orange, blue), cycling if a turn ever exceeds 3 hops. */
  private val arrowColors = Vector("#1a9c1a", "#e08b00", "#1a6fe0")

  /** Pixel center of `square` (e.g. "e4") on the 720x720 board, oriented for `activeColor` —
    * mirrors the 180-degree rotation Lichess applies for `color=black`. `None` for an unparseable
    * square.
    */
  private def squareCenter(square: String, activeColor: String): Option[(Double, Double)] =
    if square.length < 2 then None
    else
      val fileIdx = square.charAt(0) - 'a'
      val rank    = square.charAt(1) - '0'
      if fileIdx < 0 || fileIdx > 7 || rank < 1 || rank > 8 then None
      else if activeColor == "b" then
        Some(((7 - fileIdx + 0.5) * squarePx, (rank - 1 + 0.5) * squarePx))
      else Some(((fileIdx + 0.5) * squarePx, (8 - rank + 0.5) * squarePx))

  /** One `<marker>` arrowhead per color, referenced by the lines below (an SVG `marker` cannot
    * inherit its line's stroke color, so each color needs its own).
    */
  private def arrowMarkerDefs: String =
    arrowColors.zipWithIndex
      .map { case (color, i) =>
        s"""<marker id="arrowhead$i" markerWidth="7" markerHeight="6" refX="7" refY="3" orient="auto">
      <polygon points="0 0, 7 3, 0 6" fill="$color" />
    </marker>"""
      }
      .mkString("\n")

  /** The turn's micro-move arrows as an absolutely-positioned SVG overlay (empty string if none of
    * the moves parse to board squares), meant to sit inside a `position: relative` wrapper around
    * the board `<img>`.
    */
  private def arrowsOverlay(entry: OpeningBookEntry, activeColor: String): String =
    val lines =
      entry.moves.split(",").filter(_.length >= 4).zipWithIndex.flatMap { case (move, hop) =>
        for
          from <- squareCenter(move.substring(0, 2), activeColor)
          to   <- squareCenter(move.substring(2, 4), activeColor)
        yield
          val colorIdx = hop % arrowColors.length
          val color    = arrowColors(colorIdx)
          s"""<line x1="${from._1}" y1="${from._2}" x2="${to._1}" y2="${to._2}" """ +
            s"""stroke="$color" stroke-width="4" stroke-linecap="round" opacity="0.85" """ +
            s"""marker-end="url(#arrowhead$colorIdx)" />"""
      }
    if lines.isEmpty then ""
    else
      val box = boardPx.toInt
      s"""
    <svg viewBox="0 0 $box $box" style="position: absolute; inset: 0; width: 100%; height: 100%; pointer-events: none;">
      <defs>
$arrowMarkerDefs
      </defs>
      ${lines.mkString("\n      ")}
    </svg>"""

  private def diceToSymbol(piece: Char): String = piece.toUpper match
    case 'P' => "⚀ Pawn"
    case 'N' => "⚁ Knight"
    case 'B' => "⚂ Bishop"
    case 'R' => "⚃ Rook"
    case 'Q' => "⚄ Queen"
    case 'K' => "⚅ King"
    case _   => piece.toString

  private def diceStr(diceSorted: String): String = diceSorted.map(diceToSymbol).mkString(", ")

  private def movesStr(moves: String): String =
    moves.split(",").filter(_.nonEmpty).map(m => s"<code>$m</code>").mkString(", ")

  /** Lichess board-image URL for a 4-field normalized FEN (placement, active color, castling, en
    * passant) — padded with placeholder halfmove/fullmove counters to a syntactically valid 6-field
    * FEN, since `normalized_fen` (unlike the engine's DFEN) carries no clock fields.
    */
  private def boardImageUrl(normalizedFen: String, activeColor: String): String =
    val encoded = URLEncoder.encode(s"$normalizedFen 0 1".replace(' ', '_'), "UTF-8")
    val color   = if activeColor == "b" then "black" else "white"
    s"https://lichess1.org/export/fen.gif?fen=$encoded&color=$color&theme=brown&piece=cburnett"

  /** "N games · P% win rate (WW / DD / LL)" for a statistical entry, or a curated badge + note
    * (never fabricated stats) for a curated favorite.
    */
  private def statsLine(entry: OpeningBookEntry): String =
    if entry.curated then
      val noteSuffix = entry.note.filter(_.nonEmpty).map(n => s" — $n").getOrElse("")
      s"<strong>Curated favorite</strong>$noteSuffix"
    else
      val winRatePct = f"${entry.winRate * 100}%.1f"
      s"${entry.games} games &middot; $winRatePct% win rate " +
        s"(${entry.wins}W / ${entry.draws}D / ${entry.losses}L)"

  private def renderEntry(entry: OpeningBookEntry): String =
    val activeColor = Fen.fields(entry.normalizedFen).activeColor
    val imgUrl      = boardImageUrl(entry.normalizedFen, activeColor)
    val arrows      = arrowsOverlay(entry, activeColor)
    s"""<div style="display: flex; flex-direction: row; gap: 24px; align-items: start; margin-bottom: 30px; flex-wrap: wrap;">
  <div style="flex: 1; min-width: 300px;">
    <ul style="list-style-type: disc; padding-left: 20px; margin-bottom: 0;">
      <li style="margin-bottom: 8px;"><strong>Dice Rolled:</strong> ${diceStr(
        entry.diceSorted
      )}</li>
      <li style="margin-bottom: 8px;"><strong>Booked Continuation:</strong> ${movesStr(
        entry.moves
      )}</li>
      <li style="margin-bottom: 8px;"><strong>Why:</strong> ${statsLine(entry)}</li>
      <li style="margin-bottom: 0;"><strong>FEN:</strong> <code>${entry.normalizedFen}</code></li>
    </ul>
  </div>
  <div style="flex: 0 0 280px; width: 280px; min-width: 280px; margin: 0 auto; position: relative;">
    <img src="$imgUrl" alt="Board Position" style="width: 100%; display: block; border-radius: 8px; box-shadow: 0 4px 10px rgba(0,0,0,0.15); border: 1px solid rgba(0,0,0,0.08);" />$arrows
  </div>
</div>

---

"""

  private def renderGroup(title: String, entries: List[OpeningBookEntry]): String =
    val sb = StringBuilder()
    sb.append(s"## $title\n\n")
    if entries.isEmpty then sb.append("*No booked entries.*\n\n")
    else
      entries.sortBy(e => (e.diceSorted, e.normalizedFen)).foreach(e => sb.append(renderEntry(e)))
    sb.toString

  /** Renders the full catalog page — Starlight frontmatter, the `@generated` marker, and the
    * White-to-move / Black-to-move groups — ready to be written verbatim to
    * `docs/src/content/docs/opening-book-catalog.md`.
    */
  def render(entries: List[OpeningBookEntry]): String =
    val (white, black) = entries.partition(e => Fen.fields(e.normalizedFen).activeColor != "b")
    val sb             = StringBuilder()
    sb.append("---\n")
    sb.append("title: Opening Book Catalog\n")
    sb.append(
      "description: Visual catalog of the exported opening book — board, dice, continuation and the corpus stats behind every pick.\n"
    )
    sb.append("---\n\n")
    sb.append("<!-- @generated by GenerateOpeningBookCatalogApp - do not edit directly -->\n\n")
    sb.append(
      s"${entries.size} booked position${if entries.size == 1 then "" else "s"}. Regenerate with " +
        "`mise run docs:generate-book-catalog`.\n\n"
    )
    sb.append(renderGroup("White to Move", white))
    sb.append(renderGroup("Black to Move", black))
    sb.toString

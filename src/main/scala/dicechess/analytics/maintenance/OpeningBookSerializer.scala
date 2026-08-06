package dicechess.analytics.maintenance

import java.nio.charset.StandardCharsets.UTF_8

object OpeningBookSerializer:

  /** Serializes the opening book to TSV bytes in UTF-8, preserving key sorting. Each line contains
    * a canonical key and its comma-separated continuations, separated by a tab.
    */
  def serializeTsvBytes(book: Map[String, String]): Array[Byte] =
    book.toList.sortBy(_._1).map((key, moves) => s"$key\t$moves").mkString("\n").getBytes(UTF_8)

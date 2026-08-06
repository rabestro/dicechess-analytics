package dicechess.analytics.maintenance

import java.nio.charset.StandardCharsets.UTF_8
import munit.FunSuite

class OpeningBookSerializerSpec extends FunSuite:

  test("serializeTsvBytes sorts keys and formats as key\\tmoves in UTF-8"):
    val book = Map(
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR" -> "e2e4,f1c4",
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0"   -> "a2a3"
    )
    val bytes = OpeningBookSerializer.serializeTsvBytes(book)
    val str   = new String(bytes, UTF_8)

    val expected = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0\ta2a3\n" +
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - BPR\te2e4,f1c4"

    assertEquals(str, expected)

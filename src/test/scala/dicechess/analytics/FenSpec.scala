package dicechess.analytics

class FenSpec extends munit.FunSuite:

  test("normalize keeps the first four fields"):
    assertEquals(
      Fen.normalize("rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"),
      "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -"
    )

  test("normalize tolerates leading and consecutive whitespace"):
    assertEquals(Fen.normalize("  8/8/8/8/8/8/8/8   w  -   -  0 1"), "8/8/8/8/8/8/8/8 w - -")

  test("normalize pads a short FEN to four fields"):
    assertEquals(Fen.normalize("8/8/8/8/8/8/8/8 w"), "8/8/8/8/8/8/8/8 w - -")

  test("fields split the normalized FEN"):
    val f = Fen.fields("rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq e3")
    assertEquals(f.piecePlacement, "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR")
    assertEquals(f.activeColor, "b")
    assertEquals(f.castling, "KQkq")
    assertEquals(f.enPassant, "e3")

  test("fields defensively pads a short input to four parts"):
    val f = Fen.fields("8/8/8/8/8/8/8/8 w")
    assertEquals(f.castling, "-")
    assertEquals(f.enPassant, "-")

  // Regression guard: the hash MUST stay bit-compatible with the 141k positions originally
  // hashed by Python's `xxhash.xxh64(...).intdigest()`. These pairs were taken from production.
  test("fenHash is bit-compatible with the existing data"):
    val known = List(
      6344906133039437859L  -> "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
      7660317489635500655L  -> "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR w KQkq e3",
      -1729048361218078578L -> "rnbqkbnr/pppppppp/8/8/2B1P3/8/PPPP1PPP/RNBQK1NR w KQkq e3",
      -5606592683697872391L -> "rnbqkbr1/pppppppp/7n/8/P1B1P3/R4Q2/1PPP1PPP/1NB1K1NR b Kq a3"
    )
    known.foreach((expected, nf) => assertEquals(Fen.hash(nf), expected))

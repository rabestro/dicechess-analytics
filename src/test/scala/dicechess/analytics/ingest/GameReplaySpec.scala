package dicechess.analytics.ingest

class GameReplaySpec extends munit.FunSuite:

  private val start = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  private def board(fen: String): String = fen.split(" ")(0)
  private def color(fen: String): String = fen.split(" ")(1)

  test("replays a real opening turn and derives the resulting position"):
    // Captured game: White rolls Pawn/Knight/Queen and plays b1c3, e2e4, d1f3.
    val turns = List(TurnInput(dice = List(1, 2, 5), moves = List("b1c3", "e2e4", "d1f3")))
    GameReplay.replay(start, turns) match
      case Right(game) =>
        assertEquals(game.turns.size, 1)
        val after = game.turns.head.afterFen
        assertEquals(board(after), "rnbqkbnr/pppppppp/8/8/4P3/2N2Q2/PPPP1PPP/R1B1KBNR")
        assertEquals(color(after), "b") // the turn has passed to Black
        assertEquals(board(game.turns.head.beforeFen), board(start))
      case Left(err) => fail(s"expected a valid replay, got $err")

  test("rejects an illegal move sequence"):
    val turns = List(TurnInput(dice = List(1, 2, 5), moves = List("a1a4")))
    GameReplay.replay(start, turns) match
      case Left(ReplayError.IllegalTurn(0, played, _)) => assertEquals(played, List("a1a4"))
      case other                                       => fail(s"expected IllegalTurn, got $other")

  test("accepts a partial legal sequence as the last turn"):
    val turns = List(TurnInput(dice = List(1, 5, 5), moves = List("d2d4", "d1d3")))
    GameReplay.replay(start, turns) match
      case Right(game) =>
        assertEquals(game.turns.size, 1)
        val after = game.turns.head.afterFen
        assertEquals(board(after), "rnbqkbnr/pppppppp/8/8/3P4/3Q4/PPP1PPPP/RNB1KBNR")
        assertEquals(color(after), "b")
      case Left(err) => fail(s"expected a valid replay, got $err")

  test("rejects a partial legal sequence if it is not the last turn"):
    val turns = List(
      TurnInput(dice = List(1, 5, 5), moves = List("d2d4", "d1d3")),
      TurnInput(dice = List(1, 2, 3), moves = List("b8c6"))
    )
    GameReplay.replay(start, turns) match
      case Left(ReplayError.IllegalTurn(0, played, _)) => assertEquals(played, List("d2d4", "d1d3"))
      case other                                       => fail(s"expected IllegalTurn, got $other")

  test("accepts an empty turn as a pass when no legal move exists"):
    // Kings only; rolling pawn dice — nothing can move, so the turn is a pass.
    val kingsOnly = "7k/8/8/8/8/8/8/K7 w - - 0 1"
    GameReplay.replay(kingsOnly, List(TurnInput(dice = List(1, 1, 1), moves = List.empty))) match
      case Right(game) =>
        assertEquals(game.turns.size, 1)
        assertEquals(color(game.turns.head.afterFen), "b")
      case Left(err) => fail(s"expected a pass, got $err")

  test("rejects an unknown die value"):
    GameReplay.replay(start, List(TurnInput(dice = List(7), moves = List.empty))) match
      case Left(ReplayError.UnknownDie(0, 7)) => ()
      case other                              => fail(s"expected UnknownDie, got $other")

  test("reports an invalid initial FEN"):
    GameReplay.replay("not a fen", List.empty) match
      case Left(ReplayError.InvalidInitialFen(_, _)) => ()
      case other => fail(s"expected InvalidInitialFen, got $other")

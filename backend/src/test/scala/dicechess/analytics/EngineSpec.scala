package dicechess.analytics

import dicechess.engine.domain.FenParser

/** Smoke test for the engine artifact: proves that `lv.id.jc:dicechess-engine-scala_3` resolves
  * from GitHub Packages and its core API works in this project.
  */
class EngineSpec extends munit.FunSuite:

  private val startPosition = "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"

  test("engine parses the standard start position"):
    assert(FenParser.parse(startPosition).isRight, "start position must parse")

  test("engine round-trips a DFEN with a dice pool"):
    val dfen         = "rnbqkbnr/pppppppp/8/8/4P3/8/PPPP1PPP/RNBQKBNR b KQkq - 0 1 pnb"
    val roundTripped = FenParser.parse(dfen).map(FenParser.serialize)
    assertEquals(roundTripped, Right(dfen))

  test("engine rejects garbage input"):
    assert(FenParser.parse("not a fen at all").isLeft)

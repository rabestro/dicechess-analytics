package dicechess.analytics.ingest

import dicechess.engine.domain.*
import dicechess.engine.search.TurnGenerator

/** One turn's input: the rolled dice (piece kinds, 1 = pawn … 6 = king) and the UCI micro-moves
  * played.
  */
final case class TurnInput(dice: List[Int], moves: List[String])

/** A replayed turn — the board FEN before and after it. Positions are recorded at turn boundaries.
  */
final case class ReplayedTurn(beforeFen: String, afterFen: String)

/** A fully replayed, engine-validated game. */
final case class ReplayedGame(initialFen: String, turns: List[ReplayedTurn]):
  def finalFen: String = turns.lastOption.map(_.afterFen).getOrElse(initialFen)

/** Why a replay was rejected. */
enum ReplayError:
  case InvalidInitialFen(fen: String, reason: String)
  case UnknownDie(turnIndex: Int, value: Int)
  case IllegalTurn(turnIndex: Int, played: List[String], legal: List[List[String]])

/** Replays a game through the engine to validate it and derive each turn's positions.
  *
  * Every Dice Chess rule (legality, the Maximum Micro-moves Rule, dice consumption including
  * castling, king capture) stays in the engine: a turn's played sequence is matched against the
  * complete legal turn paths from [[TurnGenerator]], and the resulting positions are taken from the
  * engine's own state. The backend re-derives positions rather than trusting the writer.
  */
object GameReplay:

  def replay(
      initialFen: String,
      turns: List[TurnInput],
      termination: Option[String] = None
  ): Either[ReplayError, ReplayedGame] =
    FenParser.parse(initialFen) match
      case Left(reason)   => Left(ReplayError.InvalidInitialFen(initialFen, reason))
      case Right(initial) =>
        val start: Either[ReplayError, (GameState, List[ReplayedTurn])] = Right(
          (initial, List.empty)
        )
        val turnsSize = turns.size
        turns.zipWithIndex
          .foldLeft(start) {
            case (Left(err), _)                       => Left(err)
            case (Right((state, acc)), (turn, index)) =>
              val isLast           = index == turnsSize - 1
              val isPartialAllowed =
                isLast && termination.exists(t => t == "timeout" || t == "draw_agreement")
              replayTurn(state, turn, index, isPartialAllowed)
                .map((next, replayed) => (next, replayed :: acc))
          }
          .map((_, acc) => ReplayedGame(FenParser.serialize(initial), acc.reverse))

  private def replayTurn(
      state: GameState,
      turn: TurnInput,
      index: Int,
      isPartialAllowed: Boolean
  ): Either[ReplayError, (GameState, ReplayedTurn)] =
    turn.dice.find(die => PieceType.fromDice(die).isEmpty) match
      case Some(bad) => Left(ReplayError.UnknownDie(index, bad))
      case None      =>
        val beforeFen  = FenParser.serialize(state)
        val withDice   = state.withDicePool(turn.dice)
        val legalPaths = TurnGenerator.generateAllLegalTurnPaths(withDice)

        val applied: Option[GameState] =
          if legalPaths.isEmpty then
            // No legal move with these dice: the turn is a pass.
            Option.when(turn.moves.isEmpty)(withDice)
          else
            val pathsWithUci = legalPaths.map(path => (path, path.map(uci)))
            val exactMatch   =
              pathsWithUci.find { case (_, uciPath) => uciPath == turn.moves }.map(_._1)

            exactMatch
              .orElse {
                Option
                  .when(isPartialAllowed && turn.moves.nonEmpty)(
                    pathsWithUci
                      .find { case (_, uciPath) => uciPath.startsWith(turn.moves) }
                      .map { case (path, _) => path.take(turn.moves.size) }
                  )
                  .flatten
              }
              .map(path => path.foldLeft(withDice)((current, move) => current.makeMove(move)))

        applied match
          case None => Left(ReplayError.IllegalTurn(index, turn.moves, legalPaths.map(_.map(uci))))
          case Some(state) =>
            val ended = state.endTurn()
            Right((ended, ReplayedTurn(beforeFen, FenParser.serialize(ended))))

  private def uci(move: Move): String =
    move.fromSquare.toNotation + move.toSquare.toNotation +
      move.promotionPieceType.map(_.asNotation).getOrElse("")

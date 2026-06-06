import { getPieceFromFen, PIECE_TO_UNICODE } from '../utils/fenUtils';

export interface BotMoveHistoryState {
  fen: string;
  active_color: 'w' | 'b';
  dices: { value: string; allowed: boolean; used: boolean }[];
  gameMoveHistoryMove: { from: string; to: string; promotion: string } | null;
  leftTime?: { [playerId: string]: number };
}

export interface TurnBlock {
  turnNumber: number;
  whiteMoves: { index: number; text: string; pieceIcon: string }[];
  blackMoves: { index: number; text: string; pieceIcon: string }[];
  events: any[];
}

export interface TurnRecord {
  turn_number: number;
  active_color: 'w' | 'b';
  dice_sorted: string;
  played_moves: string[];
  position_fen: string;
  position_after_fen: string;
  thinking_time_ms: number | null;
}

export class PlayWithBotHistory {
  historyMap = $state<Record<string, BotMoveHistoryState>>({});
  currentMoveIndex = $state<number>(0);
  maxMoveIndex = $state<number>(0);

  turnHistory = $state<TurnRecord[]>([]);
  currentTurnRecord = $state<Partial<TurnRecord> | null>(null);

  initializeNewGame(initialFen: string) {
    this.currentMoveIndex = 0;
    this.maxMoveIndex = 0;
    this.turnHistory = [];
    this.currentTurnRecord = null;
    this.historyMap = {
      '0': {
        fen: initialFen,
        active_color: 'w',
        dices: [],
        gameMoveHistoryMove: null,
      }
    };
  }

  clear() {
    this.historyMap = {};
    this.currentMoveIndex = 0;
    this.maxMoveIndex = 0;
    this.turnHistory = [];
    this.currentTurnRecord = null;
  }

  get canGoToStart(): boolean {
    return this.currentMoveIndex > 0;
  }

  get canGoToEnd(): boolean {
    return this.currentMoveIndex < this.maxMoveIndex;
  }

  recordMove(
    nextFen: string,
    activeColor: 'w' | 'b',
    dices: { value: string; allowed: boolean; used: boolean }[],
    move: { from: string; to: string; promotion: string } | null
  ): number {
    const moveIndex = this.maxMoveIndex + 1;
    const newState: BotMoveHistoryState = {
      fen: nextFen,
      active_color: activeColor,
      dices: $state.snapshot(dices),
      gameMoveHistoryMove: move,
    };

    this.historyMap[String(moveIndex)] = newState;
    this.currentMoveIndex = moveIndex;
    this.maxMoveIndex = moveIndex;
    return moveIndex;
  }

  updateStateInHistory(update: Partial<BotMoveHistoryState>) {
    const state = this.historyMap[String(this.maxMoveIndex)];
    if (state) {
      Object.assign(state, update);
    }
  }

  historyBlocks = $derived.by<TurnBlock[]>(() => {
    const blocks: TurnBlock[] = [];
    let currentTurnBlock: TurnBlock | null = null;
    let prevState: BotMoveHistoryState | null = null;

    try {
      for (let i = 0; i <= this.maxMoveIndex; i++) {
        const state = this.historyMap[String(i)];
        if (!state) continue;

        const activeColor = state.active_color || 'w';
        const isNewTurn = !currentTurnBlock || (prevState && activeColor === 'w' && prevState.active_color === 'b');

        if (isNewTurn) {
          currentTurnBlock = {
            turnNumber: blocks.length + 1,
            whiteMoves: [],
            blackMoves: [],
            events: []
          };
          blocks.push(currentTurnBlock);
        }

        if (state.gameMoveHistoryMove) {
          const fen = prevState?.fen ?? state.fen;
          const from = state.gameMoveHistoryMove.from;
          const to = state.gameMoveHistoryMove.to;
          const pieceChar = from ? getPieceFromFen(fen, from) : null;
          const pieceIcon = pieceChar ? PIECE_TO_UNICODE[pieceChar] : '';

          let text: string;
          if (!from || !to) {
            text = 'PASS';
          } else {
            text = `${from.toLowerCase()}-${to.toLowerCase()}`;
          }

          const moveData = {
            index: i,
            text,
            pieceIcon: pieceIcon || ''
          };

          if (currentTurnBlock) {
            if (activeColor === 'w') {
              currentTurnBlock.whiteMoves.push(moveData);
            } else {
              currentTurnBlock.blackMoves.push(moveData);
            }
          }
        }

        prevState = state;
      }
    } catch (e) {
      console.error('Error parsing play-with-bot history blocks', e);
      return blocks;
    }
    return blocks;
  });
}

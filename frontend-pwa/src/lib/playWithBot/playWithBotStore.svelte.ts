import { ApiClient } from '../api/apiClient';
import { getPieceFromFen, deriveChessgroundDests, buildDfen, getDieValue } from '../utils/fenUtils';
import * as DiceChessEngine from '@rabestro/dicechess-engine';
import type { Key } from '@lichess-org/chessground/types';
import { PlayWithBotBot } from './playWithBotBot';
import { PlayWithBotDice, type DieState } from './playWithBotDice.svelte';
import { PlayWithBotHistory, type TurnRecord, type BotMoveHistoryState } from './playWithBotHistory.svelte';

let DiceChess = (DiceChessEngine as any).DiceChess;

export type GameStatus = 'idle' | 'rolling' | 'playing' | 'bot_thinking' | 'victory' | 'defeat' | 'draw';

export class PlayWithBotStore {
  gameStatus = $state<GameStatus>('idle');
  gameEndReason = $state<'mate' | 'timeout' | 'resign' | 'agreement' | null>(null);
  currentBoardFen = $state<string>('rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1');
  activeColor = $state<'w' | 'b'>('w');
  playerColor = $state<'w' | 'b'>('w');
  botAlgorithm = $state<string>('greedy');
  botColor = $derived((this.playerColor === 'w' ? 'b' : 'w') as 'w' | 'b');
  pendingPromotion = $state<{ orig: string, dest: string, color: 'w' | 'b', availablePieces: string[], dieIndex: number } | null>(null);

  // Time Control States
  timeLimit = $state<number | null>(null); // in minutes
  whiteTimeLeft = $state<number>(0);
  blackTimeLeft = $state<number>(0);
  playerTimeLeft = $derived(this.playerColor === 'w' ? this.whiteTimeLeft : this.blackTimeLeft);
  botTimeLeft = $derived(this.playerColor === 'w' ? this.blackTimeLeft : this.whiteTimeLeft);

  private timerIntervalId: any = null;
  private lastTickTimestamp: number = 0;
  startTime = $state<string>('');

  // Composed Engines
  history = new PlayWithBotHistory();
  dice = new PlayWithBotDice();
  bot = new PlayWithBotBot();

  // Delegates
  get currentMoveIndex() { return this.history.currentMoveIndex; }
  set currentMoveIndex(v) { this.history.currentMoveIndex = v; }
  get maxMoveIndex() { return this.history.maxMoveIndex; }
  set maxMoveIndex(v) { this.history.maxMoveIndex = v; }
  get historyMap() { return this.history.historyMap; }
  set historyMap(v) { this.history.historyMap = v; }
  get turnHistory() { return this.history.turnHistory; }
  set turnHistory(v) { this.history.turnHistory = v; }
  get currentTurnRecord() { return this.history.currentTurnRecord; }
  set currentTurnRecord(v) { this.history.currentTurnRecord = v; }
  get historyBlocks() { return this.history.historyBlocks; }
  get canGoToStart() { return this.history.canGoToStart; }
  get canGoToEnd() { return this.history.canGoToEnd; }

  get currentDice() { return this.dice.currentDice; }
  set currentDice(v) { this.dice.currentDice = v; }
  get isAnimatingRoll() { return this.dice.isAnimatingRoll; }
  set isAnimatingRoll(v) { this.dice.isAnimatingRoll = v; }
  get availableDiceValues() { return this.dice.availableDiceValues; }

  private readonly initialFen = 'rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1';

  constructor() {
    this.bot.initializeWorker();
  }

  handleFirstMove(): void {
    this.setMoveIndex(0);
  }

  handleLastMove(): void {
    this.setMoveIndex(this.history.maxMoveIndex);
  }

  handlePreviousTurn(): void {
    if (this.history.currentMoveIndex > 0) {
      this.setMoveIndex(this.history.currentMoveIndex - 1);
    }
  }

  handleNextTurn(): void {
    if (this.history.currentMoveIndex < this.history.maxMoveIndex) {
      this.setMoveIndex(this.history.currentMoveIndex + 1);
    }
  }

  startNewGame(colorPref: 'white' | 'black' | 'random' = 'white', algo: string = 'greedy', timeLimitMin: number | null = null) {
    this.botAlgorithm = algo;
    this.gameEndReason = null;
    this.stopTimer();

    if (colorPref === 'random') {
      this.playerColor = Math.random() < 0.5 ? 'w' : 'b';
    } else {
      this.playerColor = colorPref === 'white' ? 'w' : 'b';
    }

    this.activeColor = 'w';
    this.currentBoardFen = this.initialFen;
    this.currentDice = [];
    this.startTime = new Date().toISOString();

    this.history.initializeNewGame(this.initialFen);

    this.timeLimit = timeLimitMin;
    if (this.timeLimit !== null) {
      this.whiteTimeLeft = this.timeLimit * 60 * 1000;
      this.blackTimeLeft = this.timeLimit * 60 * 1000;
      this.startTimer();
    } else {
      this.whiteTimeLeft = 0;
      this.blackTimeLeft = 0;
    }

    if (this.playerColor === 'b') {
      this.gameStatus = 'bot_thinking';
      setTimeout(() => {
        this.botTurn();
      }, 500);
    } else {
      this.gameStatus = 'rolling';
    }
  }

  startTimer() {
    if (this.timeLimit === null) return;
    this.stopTimer();
    this.lastTickTimestamp = Date.now();
    this.timerIntervalId = setInterval(() => {
      if (this.pendingPromotion !== null) {
        this.lastTickTimestamp = Date.now();
        return;
      }

      const now = Date.now();
      const delta = now - this.lastTickTimestamp;
      this.lastTickTimestamp = now;

      if (this.activeColor === 'w') {
        this.whiteTimeLeft = Math.max(0, this.whiteTimeLeft - delta);
        if (this.whiteTimeLeft === 0) {
          this.handleTimeout('w');
        }
      } else {
        this.blackTimeLeft = Math.max(0, this.blackTimeLeft - delta);
        if (this.blackTimeLeft === 0) {
          this.handleTimeout('b');
        }
      }
    }, 100);
  }

  stopTimer() {
    if (this.timerIntervalId) {
      clearInterval(this.timerIntervalId);
      this.timerIntervalId = null;
    }
  }

  private checkTimeout(): boolean {
    if (this.timeLimit === null) return false;

    const currentTime = this.activeColor === 'w' ? this.whiteTimeLeft : this.blackTimeLeft;
    if (currentTime <= 0) {
      this.handleTimeout(this.activeColor);
      return true;
    }
    return false;
  }

  private handleTimeout(color: 'w' | 'b') {
    if (['victory', 'defeat', 'draw'].includes(this.gameStatus)) {
      return;
    }

    this.stopTimer();
    this.gameEndReason = 'timeout';

    const playerTimedOut = color === this.playerColor;
    if (playerTimedOut) {
      this.gameStatus = 'defeat';
    } else {
      this.gameStatus = 'victory';
    }

    if (this.currentTurnRecord) {
      this.currentTurnRecord.position_after_fen = this.currentBoardFen;
      this.turnHistory.push(this.currentTurnRecord as TurnRecord);
      this.currentTurnRecord = null;
    }

    this.saveGameRecord(playerTimedOut ? -1 : 1);
  }

  endSession() {
    this.stopTimer();
    this.gameStatus = 'idle';
    this.currentDice = [];
    this.currentBoardFen = this.initialFen;
    this.history.clear();
    this.bot.terminateWorker();
  }

  canUserRoll = $derived(this.gameStatus === 'rolling' && this.activeColor === this.playerColor && !this.isAnimatingRoll);

  async rollDice() {
    if (!this.canUserRoll) return;

    this.isAnimatingRoll = true;
    const rolled = this.dice.generateRandomDice(this.activeColor);
    this.currentDice = rolled;

    // Simulate roll animation delay
    await new Promise((resolve) => setTimeout(resolve, 800));
    this.isAnimatingRoll = false;

    if (this.gameStatus !== 'rolling' || this.activeColor !== this.playerColor) return;

    const allVals = rolled.map(d => getDieValue(d));
    let hasAtLeastOneLegalMove = false;
    try {
      const uciMoves = DiceChess.getLegalUciMoves(buildDfen(this.currentBoardFen, allVals)) || [];
      if (uciMoves.length > 0) {
        hasAtLeastOneLegalMove = true;
      }
    } catch (e) {
      console.error('Error calculating legal moves for initial roll', e);
    }

    this.currentDice = rolled;
    this.currentTurnRecord = {
      turn_number: this.turnHistory.length + 1,
      active_color: this.playerColor,
      dice_sorted: allVals.join(''),
      played_moves: [],
      position_fen: this.currentBoardFen,
      position_after_fen: this.currentBoardFen,
      thinking_time_ms: null,
    };

    if (this.maxMoveIndex === 0 && this.historyMap['0'] && this.historyMap['0'].dices?.length === 0) {
      this.updateStateInHistory({
        dices: structuredClone(rolled),
      });
    } else {
      const rollIndex = this.maxMoveIndex + 1;
      const rollState: BotMoveHistoryState = {
        fen: this.currentBoardFen,
        active_color: this.playerColor,
        dices: structuredClone(rolled),
        gameMoveHistoryMove: null,
      };
      this.historyMap[String(rollIndex)] = rollState;
      this.currentMoveIndex = rollIndex;
      this.maxMoveIndex = rollIndex;
    }

    if (!hasAtLeastOneLegalMove) {
      this.gameStatus = 'bot_thinking';
      if (this.toggleActiveColorInFen()) {
        this.updateStateInHistory({ fen: this.currentBoardFen });
        setTimeout(() => {
          this.activeColor = this.botColor;
          this.botTurn();
        }, 1200);
      } else {
        this.endSession();
      }
    } else {
      this.gameStatus = 'playing';
    }
  }

  legalMovesDests = $derived.by<Map<Key, Key[]>>(() => {
    if (this.gameStatus !== 'playing' || this.activeColor !== this.playerColor) {
      return new Map();
    }

    const availableDice = this.availableDiceValues;
    if (availableDice.length === 0) {
      return new Map();
    }

    try {
      const uciMoves = DiceChess.getLegalUciMoves(buildDfen(this.currentBoardFen, availableDice)) || [];
      return deriveChessgroundDests(uciMoves);
    } catch (e) {
      console.error('Error calculating legal moves', e);
      return new Map();
    }
  });

  handleBoardMove(orig: string, dest: string, promotionPiece?: string) {
    if (this.gameStatus !== 'playing' || this.activeColor !== this.playerColor) return;

    const piece = getPieceFromFen(this.currentBoardFen, orig);
    if (!piece) return;

    const dieVal = getDieValue(piece);
    const dieIndex = this.currentDice.findIndex(
      (d) => d.allowed && !d.used && getDieValue(d) === dieVal
    );

    if (dieIndex === -1) {
      console.error(`Could not find a valid matching die for piece ${piece}`);
      return;
    }

    this.dice.markUsed(dieIndex);

    // Handle Promotion Selection requirement
    const isPawn = piece.toLowerCase() === 'p';
    const isPromotion = isPawn && (dest[1] === '8' || dest[1] === '1');

    if (isPromotion && !promotionPiece) {
      // If capturing king, auto-promote to Queen to bypass popup
      const targetPiece = getPieceFromFen(this.currentBoardFen, dest);
      if (targetPiece && targetPiece.toLowerCase() === 'k') {
        this.completeMoveLogic(orig, dest, 'q', dieIndex);
        return;
      }

      const availableDice = this.currentDice
        .filter((d, i) => d.allowed && (!d.used || i === dieIndex))
        .map((d) => getDieValue(d));

      let availablePieces = ['q', 'r', 'b', 'n'];
      try {
        const legalMoves: string[] = DiceChess.getLegalUciMoves(buildDfen(this.currentBoardFen, availableDice)) || [];
        const movePrefix = orig + dest;
        const apiPromos = legalMoves
          .filter(m => m.startsWith(movePrefix) && m.length === 5)
          .map(m => m[4].toLowerCase());
        if (apiPromos.length > 0) {
          availablePieces = Array.from(new Set(apiPromos));
        }
      } catch(e) {
        console.error('Error getting promotions', e);
      }

      this.pendingPromotion = { orig, dest, color: this.playerColor, availablePieces, dieIndex };
      return;
    }

    this.completeMoveLogic(orig, dest, promotionPiece, dieIndex);
  }

  cancelPromotion() {
    if (!this.pendingPromotion) return;
    this.dice.revertUse(this.pendingPromotion.dieIndex);
    this.pendingPromotion = null;

    // Reset board representation to snap piece back
    const currentFen = this.currentBoardFen;
    this.currentBoardFen = '';
    setTimeout(() => {
      this.currentBoardFen = currentFen;
    }, 0);
  }

  completePromotion(piece: string) {
    if (!this.pendingPromotion) return;
    const { orig, dest, dieIndex } = this.pendingPromotion;
    this.pendingPromotion = null;
    this.completeMoveLogic(orig, dest, piece, dieIndex);
  }

  private handleCastlingDieConsumption(orig: string, dest: string, piece: string): void {
    if (piece.toLowerCase() === 'k' && Math.abs(orig.charCodeAt(0) - dest.charCodeAt(0)) === 2) {
      const rookDieVal = getDieValue('r');
      const rookDieIndex = this.currentDice.findIndex(
        (d) => d.allowed && !d.used && getDieValue(d) === rookDieVal
      );
      if (rookDieIndex !== -1) {
        this.dice.markUsed(rookDieIndex);
      }
    }
  }

  private completeMoveLogic(orig: string, dest: string, promotionStr: string | undefined, dieIndex: number) {
    const oldBoardFen = this.currentBoardFen;
    const availableDice = this.currentDice
      .filter((d, i) => d.allowed && (!d.used || i === dieIndex))
      .map((d) => getDieValue(d));

    const nextBoardFenRaw = DiceChess.applyMove(buildDfen(this.currentBoardFen, availableDice), orig, dest, promotionStr);
    if (!nextBoardFenRaw) {
      console.error(`Engine rejected move ${orig}-${dest}`);
      this.dice.revertUse(dieIndex);
      return;
    }

    const nextBoardFen = nextBoardFenRaw.split(/\s+/).slice(0, 6).join(' ');
    this.currentBoardFen = nextBoardFen;

    const pieceChar = getPieceFromFen(oldBoardFen, orig);
    if (pieceChar) {
      this.handleCastlingDieConsumption(orig, dest, pieceChar);
    }

    if (this.currentTurnRecord) {
      this.currentTurnRecord.played_moves?.push(orig + dest + (promotionStr || ''));
      this.currentTurnRecord.position_after_fen = nextBoardFen;
    }

    const destPiece = getPieceFromFen(oldBoardFen, dest);
    const isVictory = destPiece?.toLowerCase() === 'k';

    const moveIndex = this.maxMoveIndex + 1;
    const newState: BotMoveHistoryState = {
      fen: nextBoardFen,
      active_color: this.playerColor,
      dices: $state.snapshot(this.currentDice),
      gameMoveHistoryMove: {
        from: orig,
        to: dest,
        promotion: promotionStr?.toUpperCase() || 'NONE',
      },
    };

    this.historyMap[String(moveIndex)] = newState;
    this.currentMoveIndex = moveIndex;
    this.maxMoveIndex = moveIndex;

    if (isVictory) {
      this.stopTimer();
      this.gameEndReason = 'mate';
      if (this.currentTurnRecord) {
        this.currentTurnRecord.position_after_fen = this.currentBoardFen;
        this.turnHistory.push(this.currentTurnRecord as TurnRecord);
        this.currentTurnRecord = null;
      }
      setTimeout(() => {
        this.gameStatus = 'victory';
        this.saveGameRecord(this.playerColor === 'w' ? 1 : -1);
      }, 500);
      return;
    }

    const hasRemainingMoves = this.legalMovesDests.size > 0;
    if (!hasRemainingMoves) {
      this.gameStatus = 'bot_thinking';
      if (this.toggleActiveColorInFen()) {
        this.updateStateInHistory({ fen: this.currentBoardFen });
        setTimeout(() => {
          this.activeColor = this.botColor;
          this.botTurn();
        }, 800);
      } else {
        this.endSession();
      }
    }
  }

  async botTurn() {
    if (this.gameStatus !== 'bot_thinking' || this.activeColor === this.playerColor) return;

    this.isAnimatingRoll = true;
    const rolled = this.dice.generateRandomDice(this.activeColor);
    this.currentDice = rolled;

    await new Promise((resolve) => setTimeout(resolve, 800));
    this.isAnimatingRoll = false;

    this.startTimer();

    if (this.activeColor === this.playerColor) return;

    const allVals = rolled.map(d => getDieValue(d));
    let botHasMoves = false;
    try {
      const uciMoves = DiceChess.getLegalUciMoves(buildDfen(this.currentBoardFen, allVals)) || [];
      if (uciMoves.length > 0) {
        botHasMoves = true;
      }
    } catch (e) {
      console.error('Error calculating Bot legal moves', e);
    }

    this.currentDice = rolled;
    this.currentTurnRecord = {
      turn_number: this.turnHistory.length + 1,
      active_color: this.botColor,
      dice_sorted: allVals.join(''),
      played_moves: [],
      position_fen: this.currentBoardFen,
      position_after_fen: this.currentBoardFen,
      thinking_time_ms: null,
    };

    const rollIndex = this.maxMoveIndex + 1;
    const rollState: BotMoveHistoryState = {
      fen: this.currentBoardFen,
      active_color: this.botColor,
      dices: structuredClone(rolled),
      gameMoveHistoryMove: null,
    };
    this.historyMap[String(rollIndex)] = rollState;
    this.currentMoveIndex = rollIndex;
    this.maxMoveIndex = rollIndex;

    if (!botHasMoves) {
      await new Promise((resolve) => setTimeout(resolve, 1000));
      this.gameStatus = 'rolling';
      if (this.toggleActiveColorInFen()) {
        this.activeColor = this.playerColor;
        this.updateStateInHistory({ fen: this.currentBoardFen });
        this.currentDice = [];
      } else {
        this.endSession();
      }
      return;
    }

    const availableDice = this.availableDiceValues;
    if (availableDice.length === 0) return;

    // Get bot moves from Worker
    const botMoves = await this.bot.selectBestMove(this.currentBoardFen, availableDice, this.botAlgorithm);
    if (this.checkTimeout()) return;

    for (const move of botMoves) {
      await new Promise((resolve) => setTimeout(resolve, 800));
      if ((this.gameStatus as string) === 'defeat' || (this.gameStatus as string) === 'victory') return;
      if (this.checkTimeout()) return;

      const movingPiece = getPieceFromFen(this.currentBoardFen, move.from);
      if (!movingPiece) continue;

      const dieVal = getDieValue(movingPiece);
      const botDieIndex = this.currentDice.findIndex(
        (d) => d.allowed && !d.used && getDieValue(d) === dieVal
      );

      if (botDieIndex !== -1) {
        this.dice.markUsed(botDieIndex);
      }

      const prevBoard = this.currentBoardFen;
      const dfenBefore = buildDfen(
        this.currentBoardFen,
        this.currentDice
          .filter((d, i) => d.allowed && (!d.used || i === botDieIndex))
          .map((d) => getDieValue(d))
      );

      const nextBoardFenRaw = DiceChess.applyMove(dfenBefore, move.from, move.to, move.promotion || undefined);
      if (!nextBoardFenRaw) {
        console.error(`Engine rejected bot move ${move.from}-${move.to}`);
        break;
      }

      const nextBoardFen = nextBoardFenRaw.split(/\s+/).slice(0, 6).join(' ');
      this.currentBoardFen = nextBoardFen;

      this.handleCastlingDieConsumption(move.from, move.to, movingPiece);

      if (this.currentTurnRecord) {
        this.currentTurnRecord.played_moves?.push(move.from + move.to + (move.promotion || ''));
        this.currentTurnRecord.position_after_fen = nextBoardFen;
      }

      const destPiece = getPieceFromFen(prevBoard, move.to);
      const isKingCaptured = destPiece?.toLowerCase() === 'k';

      const moveIndex = this.maxMoveIndex + 1;
      const newState: BotMoveHistoryState = {
        fen: nextBoardFen,
        active_color: this.botColor,
        dices: $state.snapshot(this.currentDice),
        gameMoveHistoryMove: {
          from: move.from,
          to: move.to,
          promotion: move.promotion || 'NONE',
        },
      };

      this.historyMap[String(moveIndex)] = newState;
      this.currentMoveIndex = moveIndex;
      this.maxMoveIndex = moveIndex;

      if (isKingCaptured) {
        this.stopTimer();
        this.gameEndReason = 'mate';
        if (this.currentTurnRecord) {
          this.currentTurnRecord.position_after_fen = this.currentBoardFen;
          this.turnHistory.push(this.currentTurnRecord as TurnRecord);
          this.currentTurnRecord = null;
        }
        await new Promise((resolve) => setTimeout(resolve, 500));
        this.gameStatus = 'defeat';
        this.saveGameRecord(this.playerColor === 'w' ? -1 : 1);
        return;
      }
    }

    this.gameStatus = 'rolling';
    if (this.toggleActiveColorInFen()) {
      this.activeColor = this.playerColor;
      this.updateStateInHistory({ fen: this.currentBoardFen });
      this.currentDice = [];
    } else {
      this.endSession();
    }
  }

  setMoveIndex(index: number) {
    if (index < 0 || index > this.maxMoveIndex) return;
    this.currentMoveIndex = index;
    const state = this.historyMap[String(index)];
    if (state) {
      this.currentBoardFen = state.fen;
      this.activeColor = state.active_color;
      this.currentDice = $state.snapshot(state.dices || []);
    }
  }

  resignGame() {
    if (this.gameStatus === 'idle' || ['victory', 'defeat', 'draw'].includes(this.gameStatus)) return;
    this.stopTimer();
    this.gameEndReason = 'resign';
    this.gameStatus = 'defeat';
    if (this.currentTurnRecord) {
      this.currentTurnRecord.position_after_fen = this.currentBoardFen;
      this.turnHistory.push(this.currentTurnRecord as TurnRecord);
      this.currentTurnRecord = null;
    }
    this.saveGameRecord(this.playerColor === 'w' ? -1 : 1);
  }

  offerDraw() {
    if (this.gameStatus !== 'playing') return;
    this.stopTimer();
    this.gameEndReason = 'agreement';
    this.gameStatus = 'draw';
    if (this.currentTurnRecord) {
      this.currentTurnRecord.position_after_fen = this.currentBoardFen;
      this.turnHistory.push(this.currentTurnRecord as TurnRecord);
      this.currentTurnRecord = null;
    }
    this.saveGameRecord(0);
  }

  private toggleActiveColorInFen(): boolean {
    const nextFen = DiceChess.endTurn(this.currentBoardFen);
    if (nextFen) {
      this.currentBoardFen = nextFen.split(/\s+/).slice(0, 6).join(' ');
      this.activeColor = this.activeColor === 'w' ? 'b' : 'w';

      if (this.currentTurnRecord) {
        this.currentTurnRecord.position_after_fen = this.currentBoardFen;
        this.turnHistory.push(this.currentTurnRecord as TurnRecord);
        this.currentTurnRecord = null;
      }
      return true;
    }
    return false;
  }

  private updateStateInHistory(update: Partial<BotMoveHistoryState>) {
    const state = this.historyMap[String(this.maxMoveIndex)];
    if (state) {
      Object.assign(state, update);
    }
  }

  private getBotAlgorithmName(): string {
    const presets: Record<string, string> = {
      random: 'Random',
      greedy: 'Greedy',
    };
    return presets[this.botAlgorithm.toLowerCase()] || 'Greedy';
  }

  private async saveGameRecord(result: number) {
    try {
      const botName = `Bot (${this.getBotAlgorithmName()})`;
      const whitePlayer = this.playerColor === 'w'
        ? { username: 'Anonymous', player_type: 'human' }
        : { username: botName, player_type: 'bot' };

      const blackPlayer = this.playerColor === 'b'
        ? { username: 'Anonymous', player_type: 'human' }
        : { username: botName, player_type: 'bot' };

      const body = {
        source: 'local',
        mode: 'classic',
        result,
        termination: this.gameEndReason || 'normal',
        started_at: this.startTime,
        white_player: whitePlayer,
        black_player: blackPlayer,
        turns: this.turnHistory.map((t) => ({
          turn_number: t.turn_number,
          active_color: t.active_color,
          dice_sorted: t.dice_sorted,
          played_moves: t.played_moves,
          position_fen: t.position_fen,
          position_after_fen: t.position_after_fen,
          thinking_time_ms: t.thinking_time_ms
        }))
      };

      await ApiClient.post('/games', body);
      console.log('Game successfully saved to database');
    } catch (e) {
      console.error('Failed to save game to database', e);
    }
  }
}

export const playWithBotStore = new PlayWithBotStore();

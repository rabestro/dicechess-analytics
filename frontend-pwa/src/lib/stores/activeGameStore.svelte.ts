import { ApiClient } from '../api/apiClient';

export interface TurnData {
  turn_number: number;
  active_color: string;
  dice_sorted: string;
  played_moves: string[];
  thinking_time_ms: number | null;
  position_fen: string;
}

export interface GameDetail {
  id: string;
  source: string;
  mode: string;
  result: number;
  total_turns: number;
  white_player: { username: string };
  black_player: { username: string };
  turns: TurnData[];
}

class ActiveGameStore {
  game = $state<GameDetail | null>(null);
  loading = $state(false);
  currentTurnIndex = $state(0);

  async loadGame(id: string) {
    this.loading = true;
    try {
      this.game = await ApiClient.get<GameDetail>(`/games/${id}`);
      this.currentTurnIndex = 0;
    } catch (e) {
      console.error('Failed to load game', e);
    } finally {
      this.loading = false;
    }
  }

  closeGame() {
    this.game = null;
  }

  get currentFen() {
    if (!this.game || this.game.turns.length === 0) return 'start';
    return this.game.turns[this.currentTurnIndex].position_fen;
  }

  get currentDice() {
    if (!this.game || this.game.turns.length === 0) return null;
    return this.game.turns[this.currentTurnIndex].dice_sorted;
  }

  nextTurn() {
    if (this.game && this.currentTurnIndex < this.game.turns.length - 1) {
      this.currentTurnIndex++;
    }
  }

  prevTurn() {
    if (this.currentTurnIndex > 0) {
      this.currentTurnIndex--;
    }
  }
}

export const activeGameStore = new ActiveGameStore();

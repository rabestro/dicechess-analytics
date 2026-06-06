import { ApiClient } from '../api/apiClient';

export interface GamePlayer {
  username: string;
}

export interface GameItem {
  id: string;
  result: number;
  white_player: GamePlayer;
  black_player: GamePlayer;
  total_turns: number;
}

class GameListStore {
  games = $state<GameItem[]>([]);
  loading = $state(false);

  async fetchGames() {
    this.loading = true;
    try {
      this.games = await ApiClient.get<GameItem[]>('/games', { limit: 20 });
    } catch (e) {
      console.error('Failed to fetch games', e);
    } finally {
      this.loading = false;
    }
  }
}

export const gameListStore = new GameListStore();

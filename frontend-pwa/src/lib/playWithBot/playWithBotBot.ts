import { buildDfen } from '../utils/fenUtils';
import * as DiceChessEngine from '@rabestro/dicechess-engine';

let DiceChess = (DiceChessEngine as any).DiceChess;

export function setBotDiceChessInstance(instance: any) {
  DiceChess = instance;
}

export function resetBotDiceChessInstance() {
  DiceChess = (DiceChessEngine as any).DiceChess;
}

export interface BotMove {
  from: string;
  to: string;
  promotion: string | null;
}

type WorkerRequest = {
  type: 'getBestMove';
  payload: {
    dfen: string;
    options: { algorithm: string };
  };
};

type WorkerResponse = {
  type: 'getBestMove';
  payload: { moves: BotMove[] };
};

type WorkerError = {
  type: 'error';
  payload: { message: string };
};

type WorkerMessage = WorkerResponse | WorkerError;

export class PlayWithBotBot {
  private worker: Worker | null = null;

  initializeWorker(): void {
    if (typeof Worker === 'undefined' || typeof window === 'undefined') {
      this.worker = null;
      return;
    }
    try {
      this.worker = new Worker(new URL('./playWithBot.worker.ts', import.meta.url), {
        type: 'module'
      });
      this.worker.onerror = (error) => {
        console.error('Web Worker error:', error);
        this.worker = null;
      };
    } catch (e) {
      console.error('Failed to initialize Web Worker for bot calculations', e);
      this.worker = null;
    }
  }

  terminateWorker(): void {
    if (this.worker) {
      this.worker.terminate();
      this.worker = null;
    }
  }

  async selectBestMove(
    fen: string,
    diceValues: number[],
    algorithm: string
  ): Promise<BotMove[]> {
    const dfen = buildDfen(fen, diceValues);

    if (this.worker) {
      return this.selectBestMoveWithWorker(dfen, algorithm);
    }

    return this.selectBestMoveFallback(dfen, algorithm);
  }

  private async selectBestMoveWithWorker(
    dfen: string,
    algorithm: string
  ): Promise<BotMove[]> {
    return new Promise((resolve) => {
      if (!this.worker) {
        resolve([]);
        return;
      }

      const request: WorkerRequest = {
        type: 'getBestMove',
        payload: {
          dfen,
          options: { algorithm }
        }
      };

      const messageHandler = (event: MessageEvent<WorkerMessage>) => {
        if (event.data.type === 'getBestMove') {
          this.worker?.removeEventListener('message', messageHandler);
          resolve(event.data.payload.moves);
        } else if (event.data.type === 'error') {
          this.worker?.removeEventListener('message', messageHandler);
          console.error(`Error in Web Worker during bot move calculation: ${event.data.payload.message}`);
          resolve([]);
        }
      };

      this.worker.addEventListener('message', messageHandler);
      this.worker.postMessage(request);
    });
  }

  private async selectBestMoveFallback(
    dfen: string,
    algorithm: string
  ): Promise<BotMove[]> {
    try {
      const result = DiceChess.getBestMove(dfen, { algorithm });
      return result?.moves || [];
    } catch (e) {
      console.error(`Error getting bot moves with algorithm: ${algorithm}`, e);
      return [];
    }
  }
}

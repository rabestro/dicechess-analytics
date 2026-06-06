import { getDieValue } from '../utils/fenUtils';

export interface DieState {
  value: string;
  allowed: boolean;
  used: boolean;
}

export class PlayWithBotDice {
  currentDice = $state<DieState[]>([]);
  isAnimatingRoll = $state<boolean>(false);

  get availableDiceValues(): number[] {
    return this.currentDice
      .filter((d) => d.allowed && !d.used)
      .map((d) => getDieValue(d));
  }

  clear() {
    this.currentDice = [];
    this.isAnimatingRoll = false;
  }

  generateRandomDice(activeColor: 'w' | 'b'): DieState[] {
    const indexToPiece = ['p', 'n', 'b', 'r', 'q', 'k'];
    const rolled: DieState[] = [];
    for (let i = 0; i < 3; i++) {
      let val = indexToPiece[Math.floor(Math.random() * 6)];
      if (activeColor === 'w') {
        val = val.toUpperCase();
      }
      rolled.push({
        value: val,
        allowed: true,
        used: false,
      });
    }
    return rolled;
  }

  markUsed(dieIndex: number) {
    if (this.currentDice[dieIndex]) {
      this.currentDice[dieIndex].used = true;
    }
  }

  revertUse(dieIndex: number) {
    if (this.currentDice[dieIndex]) {
      this.currentDice[dieIndex].used = false;
    }
  }
}

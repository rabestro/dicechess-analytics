import type { Key } from '@lichess-org/chessground/types';

export const PIECE_TO_UNICODE: Record<string, string> = {
    'K': '♔\uFE0E', 'Q': '♕\uFE0E', 'R': '♖\uFE0E', 'B': '♗\uFE0E', 'N': '♘\uFE0E', 'P': '♙\uFE0E',
    'k': '♚\uFE0E', 'q': '♛\uFE0E', 'r': '♜\uFE0E', 'b': '♝\uFE0E', 'n': '♞\uFE0E', 'p': '♟\uFE0E'
};

export const DICE_TO_CHAR_MAP: Record<number, string> = {
    1: 'P',
    2: 'N',
    3: 'B',
    4: 'R',
    5: 'Q',
    6: 'K'
};

export function buildDfen(fen: string, diceVals: number[], activeColor: 'w' | 'b' = 'w'): string {
    const parts = fen.trim().split(/\s+/);
    // Pad to 6 fields if necessary
    if (parts.length < 2) parts.push('w');
    if (parts.length < 3) parts.push('-');
    if (parts.length < 4) parts.push('-');
    if (parts.length < 5) parts.push('0');
    if (parts.length < 6) parts.push('1');
    const paddedFen = parts.join(' ');
    if (!diceVals || diceVals.length === 0) return paddedFen;
    const diceChars = diceVals.map(d => {
        const ch = DICE_TO_CHAR_MAP[d] || '';
        return activeColor === 'b' ? ch.toLowerCase() : ch;
    }).join('');
    return `${paddedFen} ${diceChars}`;
}

export function getPieceFromFen(fen: string, square: string): string | null {
    if (!fen || !square) return null;
    const parts = fen.split(' ');
    if (parts.length === 0) return null;
    const board = parts[0].split('/');
    if (board.length !== 8) return null;

    const fileIndex = square.toLowerCase().charCodeAt(0) - 'a'.charCodeAt(0);
    const rankIndex = 8 - parseInt(square[1]);
    if (rankIndex < 0 || rankIndex > 7 || fileIndex < 0 || fileIndex > 7) return null;

    const rankStr = board[rankIndex];
    let col = 0;
    for (const char of rankStr) {
        if (/\d/.test(char)) {
            col += parseInt(char);
            if (col > fileIndex) return null;
        } else {
            if (col === fileIndex) return char;
            col++;
        }
    }
    return null;
}

export function getFenBoardPart(fen: string | null | undefined): string | null {
    if (!fen) {
        return null;
    }

    const [boardPart] = fen.trim().split(/\s+/);
    return boardPart || null;
}

export function isOpponentKingAbsent(fenBoardPart: string, trainingSubjectColor: 'w' | 'b'): boolean {
    const opponentKing = trainingSubjectColor === 'w' ? 'k' : 'K';
    return !fenBoardPart.includes(opponentKing);
}

export function normalizeFenForPuzzleValidation(fen: string): string {
    const normalizedFields = fen.trim().split(/\s+/);
    if (normalizedFields.length < 4) {
        throw new Error('Invalid FEN: expected at least 4 fields for puzzle validation.');
    }

    return normalizedFields.slice(0, 4).join(' ');
}

export function deriveChessgroundDests(uciMoves: string[]): Map<Key, Key[]> {
    const dests = new Map<Key, Key[]>();
    for (const uci of uciMoves) {
        if (uci.length < 4) continue;
        const orig = uci.substring(0, 2) as Key;
        const dest = uci.substring(2, 4) as Key;

        if (!dests.has(orig)) {
            dests.set(orig, []);
        }
        const list = dests.get(orig)!;
        if (!list.includes(dest)) {
            list.push(dest);
        }
    }
    return dests;
}

export const DICE_MAP: Record<string, number> = {
    'p': 1, 'P': 1,
    'n': 2, 'N': 2,
    'b': 3, 'B': 3,
    'r': 4, 'R': 4,
    'q': 5, 'Q': 5,
    'k': 6, 'K': 6
};

export function getDieValue(die: { value: string } | string): number {
    const val = typeof die === 'string' ? die : die.value;
    return DICE_MAP[val] || parseInt(val);
}

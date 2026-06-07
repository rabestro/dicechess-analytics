# System Architecture & Database Design

This document details the database schema, data models, and the position deduplication strategy used in `dicechess-analytics`.

---

## Entity Relationship (ER) Diagram

The system uses a highly normalized PostgreSQL schema designed to allow rapid analytics on moves and positions. Below is the Mermaid representation of the tables and their relations:

```mermaid
erDiagram
    players {
        uuid id PK
        string external_id UK
        string username
        string player_type
        jsonb metadata_json
        datetime created_at
        datetime updated_at
    }

    positions {
        bigint id PK
        string normalized_fen UK
        bigint fen_hash IX
        string piece_placement IX
        string active_color
        string castling
        string en_passant
    }

    games {
        uuid id PK
        string source
        uuid white_player_id FK
        uuid black_player_id FK
        integer white_rating
        integer black_rating
        string mode
        smallint result
        string termination
        bigint initial_position_id FK
        bigint final_position_id FK
        smallint total_turns
        datetime started_at IX
        jsonb metadata_json
        datetime created_at
    }

    turns {
        bigint id PK
        uuid game_id FK
        smallint turn_number
        string active_color
        bigint position_id FK
        string dice_sorted
        array_string played_moves
        bigint position_after_id FK
        integer thinking_time_ms
    }

    game_events {
        bigint id PK
        uuid game_id FK
        smallint sequence_number
        smallint turn_number
        string event_type
        string actor_color
        integer clock_white_ms
        integer clock_black_ms
        jsonb payload
    }

    players ||--o{ games : "plays as white/black"
    positions ||--o{ games : "starts/ends at"
    games ||--o{ turns : "contains"
    positions ||--o{ turns : "before/after state"
    games ||--o{ game_events : "emits"
```

---

## Data Models Specification

### 1. Players (`players`)
Stores player profiles. The player can be either a human user or an AI bot.
*   `player_type`: Can be `"human"` or `"bot"`.
*   Ratings are dynamic and calculated at runtime, so they are excluded from this table schema.

### 2. Positions (`positions`)
Represents unique chess board layouts.
*   **FEN Hashing**: Positions are deduplicated using a normalized FEN string (see details below).
*   `piece_placement`: The first part of the FEN string, representing piece layouts on the board (e.g. `rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR`).

### 3. Games (`games`)
Aggregates metadata for a single chess match.
*   `result`: Can be `1` (White wins), `-1` (Black wins), or `0` (Draw).
*   `termination`: Reason the game concluded (e.g., `"mate"`, `"resign"`, `"draw"`, `"timeout"`).

### 4. Turns (`turns`)
Records every turn of the game. A single turn consists of a dice roll and up to 3 micro-moves.
*   `dice_sorted`: The rolled dice values sorted alphabetically (e.g. `"125"` for Pawn, Knight, Queen).
*   `played_moves`: An array of micro-moves made during the turn (e.g. `["e2e4", "g1f3"]`).

### 5. Game Events (`game_events`)
A granular ledger logging the raw stream of events for a game. It is used for full game state reconstruction and chat logs.

---

## Position Deduplication Strategy

To prevent storing the same board layout multiple times (which would quickly bloat the database), `dicechess-analytics` normalizes and hashes FEN strings before inserting them.

### FEN Normalization
A standard chess FEN string contains 6 fields:
`[piece placement] [active color] [castling rights] [en passant target] [halfmove clock] [fullmove number]`

For deduplication, the halfmove clock and fullmove number are stripped, since they do not change the tactical properties of the position. The normalized FEN only contains the first 4 fields:
```python
def normalize_fen(fen_str: str) -> str:
    parts = fen_str.split()
    while len(parts) < 4:
        parts.append("-")
    return " ".join(parts[0:4])
```

### xxHash64 Signed Integer Digests
We compute the hash of the normalized FEN using `xxhash64`, which is extremely fast and has very low collision rates. Since PostgreSQL's `bigint` is a signed 64-bit integer (`-2^63` to `2^63 - 1`), the unsigned 64-bit digest from xxhash must be converted into a signed range:

```python
def get_fen_hash(normalized_fen: str) -> int:
    import xxhash
    digest = xxhash.xxh64(normalized_fen).intdigest()
    # Convert unsigned 64-bit uint to signed 64-bit bigint
    if digest >= 2**63:
        digest -= 2**64
    return digest
```

### Resolution Flow
When saving a turn or game, the backend follows a "get-or-create" loop:
1.  **Normalize FEN**: Strip move counts.
2.  **Lookup by Normalized FEN**: Query the `positions` table using the unique `normalized_fen` field.
3.  **Insert if Missing**: If the position is not in the database, calculate its signed `xxhash64` hash and insert the new `Position` record.
4.  **Reference ID**: Use the resolved position ID as the foreign key in `turns` and `games`.

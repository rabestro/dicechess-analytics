---
title: API Specification
description: REST API endpoints provided by the dicechess-analytics backend.
---

This document details the REST API endpoints provided by the `dicechess-analytics` backend.

---

## Interactive Documentation

The OpenAPI specification is generated from the [Tapir](https://tapir.softwaremill.com/)
endpoint definitions (`src/main/scala/dicechess/analytics/api/Endpoints.scala`),
so it can never drift from the implementation. Browse it locally at:

- **Swagger UI**: [http://localhost:8000/docs](http://localhost:8000/docs)

---

## Write Endpoints

`dicechess-analytics` restricts game insertion to trusted sources. The ingestion API is not public and uses a different authentication model.

See the [Game Ingestion](../ingestion) page for the `POST /api/games` endpoint specification.

---

## Games Endpoint (`/api/games`)

### 1. List and Filter Games

Retrieves a paginated list of games, with optional player and turn filters.

- **HTTP Method**: `GET`
- **Route**: `/api/games`
- **Query Parameters**:
  - `player_id` (UUID, optional): Filter games played by a specific player (either as White or Black).
  - `min_turns` (integer, optional): Filter games containing at least this number of turns.
  - `color` (`w` | `b`, optional): The focal player's colour. **Requires `player_id`** (returns `400` otherwise).
  - `opponent_type` (`human` | `bot`, optional): Opponent type (relative to `player_id`).
  - `opponent_id` (UUID, optional): A specific opponent (relative to `player_id`).
  - `stake` (`free` | `low` | `medium` | `high`, optional): Stake tier on the pot (`free` = 0/null, `low` = 1–20, `medium` = 21–200, `high` = > 200).
  - `limit` (integer, default: `50`, max: `200`): Limit the number of games returned.
- **Success Response** (`200 OK`):
  - **Type**: `Array[GameSummary]`
  - **Example Payload**:
    ```json
    [
      {
        "id": "e0bb7d6c-48c9-4b67-bd1c-1bf501ea897a",
        "source": "local",
        "mode": "classic",
        "result": 1,
        "time_initial_sec": 60,
        "time_increment_sec": 2,
        "initial_stake_amount": 200,
        "final_stake_amount": 400,
        "white_money_delta": 400.0,
        "black_money_delta": -400.0,
        "stake_currency": "GOLD",
        "total_turns": 16,
        "started_at": "2026-06-06T12:30:00Z",
        "white_player": {
          "id": "d13cb5fa-5f90-449e-b9ef-0a563abde12a",
          "username": "Anonymous",
          "player_type": "human",
          "rating_classic": null
        },
        "black_player": {
          "id": "c88f98ec-7bf5-45cd-a9bb-5d18ea3abfe1",
          "username": "Bot (Greedy)",
          "player_type": "bot",
          "rating_classic": null
        }
      }
    ]
    ```

### 2. Get Game Details

Retrieves full details of a specific game by its UUID, including all turns and board positions.

- **HTTP Method**: `GET`
- **Route**: `/api/games/{game_id}`
- **Path Parameters**:
  - `game_id` (UUID, required): The unique identifier of the game.
- **Success Response** (`200 OK`):
  - **Type**: `GameDetail`
  - **Example Payload**:
    ```json
    {
      "id": "e0bb7d6c-48c9-4b67-bd1c-1bf501ea897a",
      "source": "local",
      "mode": "classic",
      "result": 1,
      "time_initial_sec": 60,
      "time_increment_sec": 2,
      "initial_stake_amount": 200,
      "final_stake_amount": 400,
      "white_money_delta": 400.0,
      "black_money_delta": -400.0,
      "stake_currency": "GOLD",
      "total_turns": 2,
      "started_at": "2026-06-06T12:30:00Z",
      "metadata_json": {},
      "white_player": {
        "id": "d13cb5fa-5f90-449e-b9ef-0a563abde12a",
        "username": "Anonymous",
        "player_type": "human",
        "rating_classic": null
      },
      "black_player": {
        "id": "c88f98ec-7bf5-45cd-a9bb-5d18ea3abfe1",
        "username": "Bot (Greedy)",
        "player_type": "bot",
        "rating_classic": null
      },
      "turns": [
        {
          "turn_number": 1,
          "active_color": "w",
          "dice_sorted": "125",
          "played_moves": ["e2e4", "g1f3"],
          "thinking_time_ms": 3500,
          "position_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1"
        }
      ]
    }
    ```
- **Error Response** (`404 Not Found`): If the game with the given UUID does not exist.

---

## Players Endpoint (`/api/players`)

### 1. List Players

Retrieves a list of players matching the optional username search criteria.

- **HTTP Method**: `GET`
- **Route**: `/api/players`
- **Query Parameters**:
  - `username` (string, optional): Substring filter on usernames (case-insensitive).
  - `limit` (integer, default: `50`, max: `100`): Limit the number of players returned.
- **Success Response** (`200 OK`):
  - **Type**: `Array[PlayerSummary]`
  - **Example Payload**:
    ```json
    [
      {
        "id": "d13cb5fa-5f90-449e-b9ef-0a563abde12a",
        "username": "Anonymous",
        "player_type": "human",
        "rating_classic": 1500
      }
    ]
    ```

### 2. Get Player Details

Retrieves profile metadata for a specific player.

- **HTTP Method**: `GET`
- **Route**: `/api/players/{player_id}`
- **Path Parameters**:
  - `player_id` (UUID, required): The unique identifier of the player.
- **Success Response** (`200 OK`):
  - **Type**: `PlayerSummary`
  - **Example Payload**:
    ```json
    {
      "id": "d13cb5fa-5f90-449e-b9ef-0a563abde12a",
      "username": "Anonymous",
      "player_type": "human",
      "rating_classic": 1500
    }
    ```
- **Error Response** (`404 Not Found`): If the player with the given UUID does not exist.

### 3. Get Player Statistics

Aggregate win/loss/draw statistics for a single player across all of their games. Outcomes are
from the player's perspective: a win is the player on the winning side (White with `result = 1`,
Black with `result = -1`), mirrored for losses, with `result = 0` a draw. Undecided games
(`result = null`) are counted in `games` but excluded from the win-rate denominator. The win-rate
convention matches `/api/positions/equity`: `win_rate = (wins + 0.5·draws) / decided`, where
`decided = wins + draws + losses` (`0.0` when nothing is decided).

- **HTTP Method**: `GET`
- **Route**: `/api/players/{player_id}/stats`
- **Path Parameters**:
  - `player_id` (UUID, required): The unique identifier of the player.
- **Query Parameters** (all optional; the **counts and history bounds reflect the filter**, while identity — `username`, `player_type`, `rating_classic`, `rating_x2` — does **not**):
  - `mode` — `classic` | `x2`.
  - `color` — `w` | `b` (the focal player's colour).
  - `opponent_type` — `human` | `bot`.
  - `opponent_id` (UUID) — a specific opponent.
  - `stake` — tier `free` | `low` | `medium` | `high` on `initial_stake_amount` (the pot = 2× the site bet): `free` = `0`/`null`, `low` = `1–20` (bet 1–10), `medium` = `21–200` (bet 25–100), `high` = `> 200` (bet 300+).
  - `date_from` / `date_to` — start-date range, inclusive.
- **Success Response** (`200 OK`):
  - **Type**: `PlayerStats`
  - **Fields**:
    - `games` — total games played, including undecided ones.
    - `wins`, `draws`, `losses`, `decided` — outcome counts from the player's perspective; `decided = wins + draws + losses`.
    - `win_rate` — `(wins + 0.5·draws) / decided`; `0.0` when no game is decided.
    - `as_white`, `as_black` — games played per colour.
    - `first_game`, `last_game` — `started_at` of the player's earliest and latest games (`null` when the player has no games).
    - `rating_classic`, `rating_x2` — rating snapshot from the player's most recent game in each mode (`null` when the player has no game in that mode); a player carries an independent rating per mode.
  - **Example Payload**:

    ```json
    {
      "id": "d13cb5fa-5f90-449e-b9ef-0a563abde12a",
      "username": "Anonymous",
      "player_type": "human",
      "games": 1284,
      "wins": 712,
      "draws": 23,
      "losses": 545,
      "decided": 1280,
      "win_rate": 0.5652,
      "as_white": 640,
      "as_black": 644,
      "first_game": "2024-01-03T18:22:00Z",
      "last_game": "2026-06-20T09:14:00Z",
      "rating_classic": 1500,
      "rating_x2": 1463
    }
    ```

  - An existing player with no games returns zeroed counts and `null` ratings/dates.
- **Error Response** (`404 Not Found`): If the player with the given UUID does not exist.

### 4. Get Player Breakdowns

Win-rate breakdowns for a player across categorical dimensions, plus the average number of moves — over the **same filtered slice** as the stats endpoint (the identity ratings are not part of this response).

- **HTTP Method**: `GET`
- **Route**: `/api/players/{player_id}/breakdowns`
- **Path Parameters**:
  - `player_id` (UUID, required).
- **Query Parameters**: identical to the stats endpoint (`mode`, `color`, `opponent_type`, `opponent_id`, `stake`, `date_from`, `date_to`).
- **Success Response** (`200 OK`):
  - **Type**: `PlayerBreakdowns`
  - **Fields**:
    - `by_color`, `by_mode`, `by_opponent_type` — lists of `{ key, games, wins, draws, losses, win_rate }` from the player's perspective (`key` is `w`/`b`, `classic`/`x2`, `human`/`bot`); `win_rate = (wins + 0.5·draws)/decided`.
    - `avg_turns` — mean `total_turns` over the filtered games (`null` when none match).
  - **Example Payload**:

    ```json
    {
      "by_color": [
        { "key": "w", "games": 26883, "wins": 16419, "draws": 228, "losses": 10236, "win_rate": 0.615 },
        { "key": "b", "games": 25443, "wins": 13482, "draws": 235, "losses": 11726, "win_rate": 0.535 }
      ],
      "by_mode": [
        { "key": "classic", "games": 32855, "wins": 19025, "draws": 359, "losses": 13471, "win_rate": 0.585 },
        { "key": "x2", "games": 19471, "wins": 10876, "draws": 104, "losses": 8491, "win_rate": 0.561 }
      ],
      "by_opponent_type": [
        { "key": "human", "games": 47886, "wins": 26877, "draws": 463, "losses": 20546, "win_rate": 0.566 },
        { "key": "bot", "games": 4440, "wins": 3024, "draws": 0, "losses": 1416, "win_rate": 0.681 }
      ],
      "avg_turns": 16.2
    }
    ```
- **Error Response** (`404 Not Found`): If the player with the given UUID does not exist.

### 5. Get Rating History

A player's rating over time — one point per active day (the rating after that day's last game), per mode. Rating is a per-mode, point-in-time property, so this endpoint honours **only `mode` and the date range** (colour / opponent / stake do not shape a rating curve).

- **HTTP Method**: `GET`
- **Route**: `/api/players/{player_id}/rating-history`
- **Path Parameters**:
  - `player_id` (UUID, required).
- **Query Parameters**:
  - `mode` (`classic` | `x2`, optional): omit to get both series.
  - `date_from` / `date_to`: start-date range, inclusive.
- **Success Response** (`200 OK`):
  - **Type**: `RatingHistory` — `{ classic: RatingPoint[], x2: RatingPoint[] }`, each `RatingPoint` being `{ date, rating }`, ordered by date. A series is empty when the player has no rated game in that mode (or it was filtered out); unrated games are excluded.
  - **Example Payload**:

    ```json
    {
      "classic": [
        { "date": "2026-05-17", "rating": 3053 },
        { "date": "2026-05-30", "rating": 3127 },
        { "date": "2026-06-19", "rating": 3035 }
      ],
      "x2": [{ "date": "2026-05-15", "rating": 3119 }]
    }
    ```

- **Error Response** (`404 Not Found`): If the player with the given UUID does not exist.

---

## Positions Endpoint (`/api/positions`)

Read-only analytics over the deduplicated `positions` table and the `turns` that pass through
them. Win rates are always from the **side to move's** perspective, with draws counted as half a
win: `win_rate = (wins + 0.5·draws) / decided`, where `decided = wins + draws + losses`. This is
the cubeless-equity-equivalent win probability.

### 1. Position Equity (pre-roll)

Returns the win probability for the side to move **before** any dice are rolled — aggregated over
every turn played from the position, regardless of the roll. This is the metric for doubling-cube
decisions: in a doubling game a player may offer to double the stake *before* rolling, so the
number that matters is the pre-roll equity, not a per-roll win rate.

- **HTTP Method**: `GET`
- **Route**: `/api/positions/equity`
- **Query Parameters**:
  - `fen` (string, required): Position FEN. Normalized server-side (move clocks ignored).
  - `mode` (string, optional): `classic` or `x2`. Omit for all modes.
- **Success Response** (`200 OK`):
  - **Type**: `PositionEquity`
  - **Example Payload**:

    ```json
    {
      "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
      "side_to_move": "w",
      "games": 154192,
      "wins": 83940,
      "draws": 1318,
      "losses": 68934,
      "win_rate": 0.5487
    }
    ```

  - `games` is the total matched turns; `win_rate` is computed over the decided games
    (`wins + draws + losses`). No-op self-loop turns are excluded (as in continuations). A position
    with no decided games returns zeros.

The pre-roll equity equals the per-roll continuation win rates averaged over all rolls, each
weighted by that roll's decided-game count — so it stays consistent with the continuations below.

**Doubling guidance** (side-to-move win probability):

| Win probability | Read |
| --------------- | ---- |
| `< 25%`         | Behind — opponent is in their doubling window |
| `25–60%`        | Hold — no double yet |
| `60–75%`        | Doubling window — offer the double; opponent should take |
| `> 75%`         | Too good — opponent should drop |

The opponent's cubeless take point is `25%`: they should accept a double while their own win
probability exceeds it (i.e. while the doubler is below `75%`).

### 2. Continuations (per-roll)

Returns how players continued from a position after a specific roll, grouped by the resulting
position (so permutations of the micro-moves collapse) and ranked by frequency.

- **HTTP Method**: `GET`
- **Route**: `/api/positions/continuations`
- **Query Parameters**:
  - `fen` (string, required): Starting position FEN (normalized server-side).
  - `dice` (string, required): Roll as sorted piece letters, e.g. `BPQ` (cased to the side to move
    server-side).
  - `mode` (string, optional): `classic` or `x2`. Omit for all modes.
  - `limit` (integer, default: `50`, max: `200`): Max continuations returned (`total_games` stays
    the pre-limit total).
- **Success Response** (`200 OK`):
  - **Type**: `PositionContinuations`
  - **Example Payload**:

    ```json
    {
      "fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq -",
      "dice": "BPQ",
      "total_games": 4254,
      "items": [
        {
          "fen": "rnbqk1nr/pppp1ppp/8/2b1p3/2B1P3/5N2/PPPP1PPP/RNBQK2R b KQkq -",
          "moves": ["e2e4", "d1f3", "f1c4"],
          "games": 3680,
          "wins": 2520,
          "draws": 68,
          "losses": 1092,
          "win_rate": 0.694
        }
      ]
    }
    ```

  - An empty `moves` list is a legal pass (the roll hit only pieces that cannot move). No-op turns
    (rolled but never played) are excluded server-side.

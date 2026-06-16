---
title: Game Ingestion
description: The authenticated write path for submitting completed games to the analytics backend.
---

`POST /api/games` is the single write endpoint of the backend. A **trusted writer**
submits one completed, source-agnostic game; the backend validates it against the
Dice Chess engine and persists it. The endpoint is **idempotent** on the game `id`,
so re-submitting the same game is safe.

The read API (browsing games and players) is public; ingestion is not. See the
[API Specification](/dicechess-analytics/api-specification) for the read endpoints.

---

## Authentication

The endpoint is protected by a bearer token. The backend reads its expected secret
from the `INGEST_TOKEN` environment variable and compares it in constant time.

- **If `INGEST_TOKEN` is unset, every write is rejected** (closed by default).
- Requests must carry `Authorization: Bearer <token>`.
- A missing or non-matching token yields `401 Unauthorized`.

```http
POST /api/games HTTP/1.1
Authorization: Bearer <INGEST_TOKEN>
Content-Type: application/json
```

---

## Engine validation

Every submitted game is **replayed move-by-move through the Dice Chess engine**
before anything is written. For each turn the backend:

1. Parses the starting position (`initial_fen`, DFEN).
2. Loads the rolled `dice` into the position's dice pool.
3. Asks the engine to enumerate all legal turn paths and confirms the submitted
   `moves` form one of them (this enforces every rule: legality, the Maximum
   Micro-moves Rule, dice consumption, king capture).

If any turn fails to validate, the whole request is rejected with `422` and **nothing
is persisted**. The engine — not the writer — is the source of truth for legality.

**Partial Terminal Turns:**
If a game ends mid-turn, the final turn may contain fewer micro-moves than the rolled dice allow. The backend gracefully handles this specifically for `timeout`, `draw_agreement`, and `resign` terminations: for the **last turn only**, if the played sequence is a strict prefix of a valid legal path, it is accepted and the partial turn is persisted. If such a partial sequence appears in any non-terminal turn, or under a different termination reason, the game is rejected.

---

## Request body

`Content-Type: application/json`. All field names are `snake_case` on the wire.

| Field | Type | Notes |
| --- | --- | --- |
| `id` | UUID | The source's game id and the primary key (idempotency). **Required.** |
| `source` | string | Origin label of the game. **Required.** |
| `mode` | string | `classic` or `x2`. **Required.** |
| `result` | int? | `1` white win, `-1` black win, `0` draw. |
| `termination` | string? | `king_captured`, `timeout`, `resign`, `draw_agreement`, `double_declined`, `unknown`. |
| `started_at` | datetime? | ISO-8601 with offset. |
| `time_initial_sec` | int? | Base clock. |
| `time_increment_sec` | int? | Increment per turn. |
| `initial_stake_amount` | int? | Stake at the start. |
| `final_stake_amount` | int? | Stake at the end. |
| `white_money_delta` | decimal? | Net change for White. |
| `black_money_delta` | decimal? | Net change for Black. |
| `stake_currency` | string? | Currency label of the stake. |
| `white_player` | Player? | See below. Resolved to a `players` row by `external_id`. |
| `black_player` | Player? | See below. |
| `initial_fen` | string | Starting position in DFEN. **Required.** |
| `turns` | Turn[] | Ordered list of turns. **Required.** |
| `events` | Event[] | Non-move events (doubling, draw offers). |

**Player**

| Field | Type | Notes |
| --- | --- | --- |
| `external_id` | string | Stable id from the source; upsert key. **Required.** |
| `username` | string? | Display name. |
| `player_type` | string? | `human` or `bot`. |
| `rating` | int? | Rating at game time. |

**Turn**

| Field | Type | Notes |
| --- | --- | --- |
| `turn_number` | int | 1-based. **Required.** |
| `active_color` | string | `w` or `b`. **Required.** |
| `dice` | int[] | Rolled dice; `1` pawn … `6` king. **Required.** |
| `moves` | string[] | UCI micro-moves played this turn. **Required.** |
| `thinking_time_ms` | int? | Time spent on the turn. |
| `fen_after` | string? | Position after the turn (informational). |

**Event**

| Field | Type | Notes |
| --- | --- | --- |
| `sequence_number` | int | Order within the game. **Required.** |
| `turn_number` | int? | Turn the event belongs to. |
| `event_type` | string | Event type enum. **Required.** |
| `actor_color` | string? | `w` or `b`. |
| `clock_white_ms` | int? | White's clock at the event. |
| `clock_black_ms` | int? | Black's clock at the event. |
| `payload` | object? | Free-form JSON details. |

### Example

```json
{
  "id": "00000000-0000-0000-0000-0000000000b1",
  "source": "import",
  "mode": "classic",
  "result": 1,
  "termination": "king_captured",
  "time_initial_sec": 300,
  "time_increment_sec": 5,
  "white_player": { "external_id": "ext-w", "username": "alice", "player_type": "human", "rating": 1500 },
  "black_player": { "external_id": "ext-b", "username": "bob", "player_type": "bot", "rating": 1480 },
  "initial_fen": "rnbqkbnr/pppppppp/8/8/8/8/PPPPPPPP/RNBQKBNR w KQkq - 0 1",
  "turns": [
    { "turn_number": 1, "active_color": "w", "dice": [1, 2, 5], "moves": ["b1c3", "e2e4", "d1f3"], "thinking_time_ms": 3500 }
  ],
  "events": []
}
```

---

## Responses

| Status | Meaning |
| --- | --- |
| `201 Created` | The game was new and has been persisted. |
| `200 OK` | A game with this `id` already existed; nothing changed (idempotent re-ingest). |
| `401 Unauthorized` | Missing or invalid bearer token. |
| `422 Unprocessable Entity` | The game failed engine replay (e.g. an illegal move). Nothing is persisted. |

Success bodies carry whether the request created the game:

```json
{ "id": "00000000-0000-0000-0000-0000000000b1", "created": true }
```

Error bodies use the standard shape:

```json
{ "detail": "Turn 1: illegal move sequence [a1a4]" }
```

---

## Idempotency

The `id` is the primary key. The first successful submission inserts the game, its
turns, and its events; any later submission of the same `id` is a no-op that returns
`200`. Writers can therefore retry safely without creating duplicates.

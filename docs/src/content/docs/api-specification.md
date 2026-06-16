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

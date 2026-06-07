# Dice Chess Analytics

Welcome to the **Dice Chess Analytics** documentation.

`dicechess-analytics` is a high-performance analytical backend and data pipeline designed for **Dice Chess**—a dynamic chess variant combining classic chess strategy with random dice events. This backend system is built to ingest, normalize, and analyze millions of games, positions, and player stats using **FastAPI** and **PostgreSQL**.

---

## Key Features

*   **Fast API Ingestion**: High-performance REST endpoints for saving completed games, turns, and player profiles.
*   **Position Deduplication**: A unique hashing schema utilizing `xxhash64` and FEN normalization to map millions of turns to a minimal set of distinct chessboard positions.
*   **ETL Pipeline**: A built-in ETL importer to easily migrate massive datasets from local SQLite archives to production-ready PostgreSQL databases.
*   **Relational Game History**: Granular database models storing players, games, turns, rolled dice, and sequential game events.

---

## System Components

The project consists of three main components:

1.  **FastAPI Backend API**: Serves the REST API for listing and retrieving players and game records. It provides automated Swagger (`/docs`) and ReDoc (`/redoc`) specs.
2.  **PostgreSQL Database**: A highly indexed schema designed for complex query performance (calculating win rates, move frequencies, and position analysis).
3.  **ETL Pipeline (`importer.py`)**: A command-line utility for importing large batches of game files, resolving positions, and committing records transactionally.

---

## What is Dice Chess?

**Dice Chess** is a chess variant where a turn consists of:

1.  **Dice Roll**: The active player rolls three standard six-sided dice. The numbers correspond to chess pieces:
    *   **1**: Pawn ♙
    *   **2**: Knight ♘
    *   **3**: Bishop ♗
    *   **4**: Rook ♖
    *   **5**: Queen ♕
    *   **6**: King ♔
2.  **Micro-moves**: The player can make up to three moves in a single turn using the pieces shown on the rolled dice. For example, if a player rolls `1, 2, 4`, they can make a pawn move, a knight move, and a rook move in any order.
3.  **End Turn**: The turn ends after 3 moves, or if the player has no further legal moves, or if they capture the opponent's King (declaring checkmate).

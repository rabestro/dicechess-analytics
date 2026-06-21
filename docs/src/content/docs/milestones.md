---
title: Milestones & Roadmap
description: Development roadmap and milestone targets for the dicechess-analytics project.
---

This page outlines the development roadmap and milestone targets for the `dicechess-analytics` project.

---

## Roadmap Overview

The development of `dicechess-analytics` is structured into progressive phases focusing first on data ingestion and deduplication, followed by analytics generation, real-time metrics streaming, and finally performance tuning and production readiness.

```mermaid
gantt
    title Dice Chess Analytics Roadmap
    dateFormat  YYYY-MM-DD
    section Backend & Schema
    v0.1 - Foundation & Local Setup         :done,    des1, 2026-06-01, 2026-06-07
    v0.2 - Ingestion API & Scala Rewrite      :done,    des2, 2026-06-08, 2026-06-21
    v0.3 - Position Analytics & Deduplication :active,  des3, 2026-06-22, 2026-07-05
    section Analytics & Features
    v0.4 - Aggregate Metrics & Views         :         des4, 2026-07-06, 2026-07-19
    v0.5 - Real-Time Dashboard & WebSockets  :         des5, 2026-07-20, 2026-08-02
    section Quality & Production
    v0.6 - Cache Layer & Query Tuning        :         des6, 2026-08-03, 2026-08-16
    v1.0 - Production Readiness & CI/CD      :         des7, 2026-08-17, 2026-08-30
```

---

## Detailed Milestones

### v0.1 - Foundation & Local Setup ✅

- **Objective**: Establish core infrastructure, local development workflows, and the database schema.
- **Delivered**:
  - PostgreSQL schema (`players`, `games`, `turns`, `game_events`, `positions`).
  - One-time import of the historical archive: 140k+ games from the frozen `dicechess-lab` SQLite database.
  - Local database container config (`docker-compose.yaml`).
  - Documentation site using Astro/Starlight.
  - Command tasks configured in `mise.toml`.

### v0.2 - Ingestion API & Scala Rewrite ✅

- **Objective**: Rewrite the backend in Scala 3 and implement the transactional game saving API.
- **Deliverables**:
  - Read-parity Scala 3 backend (http4s + Tapir + Doobie + Flyway) — ✅ in production.
  - MUnit + testcontainers test suite running against real PostgreSQL — ✅.
  - `POST /api/games` endpoint ingesting live game/turn results, with every game validated
    by `dicechess-engine-scala` before persisting.

### v0.3 - Position Analytics & Deduplication

- **Objective**: Deduplicate unique board states using FEN normalization and construct position analytics endpoints.
- **Deliverables**:
  - Position deduplication logic using FEN normalization via the engine.
  - xxhash64 signed bigint hash mapping in PostgreSQL.
  - Optimize index parameters for fast position queries.
  - `GET /api/positions/{fen_hash}/analytics` endpoints returning win/draw rates, play frequency, and common continuation moves.

### v0.4 - Aggregate Metrics & Materialized Views

- **Objective**: Generate complex historical aggregates and optimize long-running queries.
- **Deliverables**:
  - Per-player aggregate stats endpoint `GET /api/players/{player_id}/stats` (win/loss/draw, win-rate, per-colour and per-mode breakdown) — the backend foundation of the analytics-UI **Players** section.
  - Player rating history graphs and opening explorer stats endpoints.
  - Use PostgreSQL Materialized Views for heavy analytical queries.
  - Game search endpoints supporting complex multi-column filters (ratings, termination type, openings, dates).

### v0.5 - Real-Time Dashboard & WebSockets

- **Objective**: Enable real-time metrics visualization and WebSocket streaming.
- **Deliverables**:
  - WebSocket endpoint to stream live games and turns.
  - Real-time system dashboard monitoring ingestion rate and active database connections.

### v0.6 - Cache Layer & Query Tuning

- **Objective**: Performance optimizations under concurrent read/write loads.
- **Deliverables**:
  - Caching layer for position analytics and popular queries.
  - Database query optimization using `EXPLAIN ANALYZE` and composite indexes.
  - Load testing the ingestion server to handle peak concurrent traffic.

### v1.0 - Production Readiness & CI/CD

- **Objective**: Finalize configuration for cloud deployment and automated workflows.
- **Deliverables**:
  - Optimized multi-arch (amd64/arm64) production images.
  - GitHub Actions CI/CD workflows for automated formatting, testing, and deployment.
  - Structured logging setup and monitoring metrics.

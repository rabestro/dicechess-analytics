---
title: Domain Conventions
description: Non-obvious field semantics and data-encoding gotchas that the schema alone doesn't reveal.
---

The [Architecture & Schema](../architecture) page shows the _shape_ of the data.
This page captures the conventions you **cannot** infer from a column's type or name. Each item
below has already caused a real bug — keep them in mind when querying or building on the data.

## Dice — `turns.dice_sorted`

A roll is three dice, each a **piece type** (pawn, knight, bishop, rook, queen, king). It is stored
as the sorted piece letters, **cased by the side to move**:

- White to move → **upper-case**, e.g. `BPQ` (bishop, pawn, queen).
- Black to move → **lower-case**, e.g. `bpq`.

:::caution[The case encodes the mover]
A query keyed on `dice_sorted` must case the code to the **queried position's** side to move —
`BPQ` does **not** match a black-to-move position (which stores `bpq`). The position-continuations
endpoint derives the case from the position's `active_color`; clients send colour-agnostic letters.
:::

A small, recent slice of ingested games stores the dice as **digits** (`135`) instead of letters — a
separate inconsistency tracked in [#128](https://github.com/rabestro/dicechess-analytics/issues/128).

## Time control — `games.time_initial_sec`

Both `time_initial_sec` and `time_increment_sec` strictly contain values in **seconds**.
So a `3+0` game will be stored as `(180, 0)` and a `1+1` game as `(60, 1)`.

## Result and win rate

- `games.result` is from **White's perspective**: `1` White win, `-1` Black win, `0` draw, `NULL`
  unknown/unfinished.
- A continuation's **win rate** is computed for the **side to move** at that position
  (`turns.active_color` combined with `result`). It is therefore White's rate from the start, but it
  flips as you drill into deeper, black-to-move positions.

## Stakes — `GOLD`

`stake_currency` is an **in-game currency** (`GOLD`), not an ISO 4217 code, and amounts are whole
numbers. A stake of **`0` means a tournament game** — surface it as "no stake" rather than `0 GOLD`.

## Engine strength

The bundled Dice Chess engine validates legality but plays **greedily and weakly** — it is _not_ a
"best move" oracle. The analytical reference for "what is good" is the **empirical win rate of strong
human players**, not the engine and not the bots (which are weak as well).

## Positions

Positions are de-duplicated by `normalized_fen` (board + side to move + castling + en passant).
Analytics group continuations by the **resulting position** (`turns.position_after_id`), so different
micro-move orderings that reach the same position collapse into a single line.

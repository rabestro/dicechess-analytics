-- Index the foreign-key columns that reference positions(id).
--
-- Deleting or updating a positions row makes PostgreSQL verify every referencing FK; on an
-- unindexed child column that verification is a sequential scan of the whole child table per parent
-- row. positions has four referencing columns and only turns.position_id was indexed (V1), so the
-- TerminalColorRepair (#161) orphan-deletion of ~112k positions degenerated into a per-row scan of
-- turns (2.8M) and games (185k) and hung. Index the remaining three. See issue #165.
--
-- Plain (non-CONCURRENT) CREATE INDEX: it runs inside Flyway's migration transaction and briefly
-- takes a SHARE lock (blocks writes, not reads) on each table while building — instant on fresh
-- databases, a one-off pause on the populated production table.

CREATE INDEX ix_turns_position_after_id ON turns (position_after_id);
CREATE INDEX ix_games_initial_position_id ON games (initial_position_id);
CREATE INDEX ix_games_final_position_id ON games (final_position_id);

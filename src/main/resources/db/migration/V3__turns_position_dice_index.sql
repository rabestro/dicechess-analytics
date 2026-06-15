-- Position explorer: continuations are looked up by the starting position
-- (turns.position_id) and the dice roll (turns.dice_sorted), then grouped by the
-- resulting position. This composite index covers that lookup on the 2.2M-row
-- turns table.
--
-- NB: a plain (non-CONCURRENTLY) build is intentional. CREATE INDEX CONCURRENTLY
-- hangs under Flyway here — it waits on Flyway's own concurrent schema-history
-- transaction and never completes. On this table the plain build takes only a
-- few seconds behind a brief lock, which is acceptable for an analytics DB.
CREATE INDEX ix_turns_position_dice ON turns (position_id, dice_sorted);

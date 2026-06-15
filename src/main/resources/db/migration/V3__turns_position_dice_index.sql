-- Position explorer: continuations are looked up by the starting position
-- (turns.position_id) and the dice roll (turns.dice_sorted), then grouped by the
-- resulting position. This composite index covers that lookup on the 2.2M-row
-- turns table.
CREATE INDEX ix_turns_position_dice ON turns (position_id, dice_sorted);

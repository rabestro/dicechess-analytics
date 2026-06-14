-- Convert games.mode and games.termination from free VARCHAR to Postgres enums,
-- matching the integrity guarantee of the existing game_event_type_enum.
--
-- Existing data casts cleanly: mode is already {classic, x2} and termination is
-- uniformly 'unknown'. Writers map their source's end-reason to these values
-- (e.g. a king-capture ending -> king_captured); the set is documented in the
-- architecture guide.

CREATE TYPE game_mode_enum AS ENUM (
    'classic',
    'x2'
);

CREATE TYPE game_termination_enum AS ENUM (
    'king_captured',   -- the Dice Chess win: the opponent's king was captured
    'timeout',         -- a player's clock reached zero
    'resign',          -- a player resigned
    'draw_agreement',  -- draw agreed by both players
    'double_declined', -- a doubling offer was declined (ends the game as a loss)
    'unknown'          -- not determined (default; all legacy rows)
);

-- mode carries a default expression, which must be dropped before the type change
-- and restored afterwards.
ALTER TABLE games ALTER COLUMN mode DROP DEFAULT;
ALTER TABLE games ALTER COLUMN mode TYPE game_mode_enum USING mode::game_mode_enum;
ALTER TABLE games ALTER COLUMN mode SET DEFAULT 'classic';

ALTER TABLE games ALTER COLUMN termination TYPE game_termination_enum USING termination::game_termination_enum;
ALTER TABLE games ALTER COLUMN termination SET DEFAULT 'unknown';
ALTER TABLE games ALTER COLUMN termination SET NOT NULL;

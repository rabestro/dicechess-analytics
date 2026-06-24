-- Curated opening-book favorites: one expert-chosen continuation per (position, dice roll).
--
-- A favorite always overrides the statistically-best pick in ExportOpeningBookApp output
-- (the merge is statistical ++ curated so curated wins on key collision).
-- Keyed by the canonical opening-book key split into its two components, which together
-- byte-match the engine's OpeningBook.key: normalized_fen + ' ' + dice_sorted.
--
-- dice_sorted encodes the side to move by letter case (white upper-case, black lower-case),
-- identical to the turns.dice_sorted convention. The application layer derives the correct
-- casing from the FEN's active_color field and must never trust the caller's casing.

CREATE TABLE opening_book_favorites (
    normalized_fen VARCHAR NOT NULL,
    dice_sorted    VARCHAR NOT NULL,
    played_moves   VARCHAR(5)[] NOT NULL,
    note           TEXT,
    created_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY    (normalized_fen, dice_sorted)
);

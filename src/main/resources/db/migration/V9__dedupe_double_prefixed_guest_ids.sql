-- Merge double-prefixed guest:guest:<uuid> player rows from live games (dicechess-play-api#14).
--
-- Every live game created through play-api's lobby/play-a-friend flow (POST /games,
-- /lobby/seeks, /lobby/seeks/{id}/accept) ingested its guest players under a
-- double-prefixed external_id: guest:guest:<uuid> instead of guest:<uuid>. Root cause:
-- the play SPA's per-browser guest identity is already the string guest:<uuidv7>; the
-- server wrapped whatever id it was given in its own Principal.Guest(id), whose
-- externalId prepends guest: again. Fixed at both ends -- dicechess-play#135 (the
-- client stops sending the prefixed string) and dicechess-play-api#141 (the server
-- now rejects anything but a bare UUID) -- both merged, so no NEW corrupted rows
-- should land from here on. This migration is a one-time backfill for what already did.
--
-- Merge every guest:guest:<uuid> row into its guest:<uuid> twin, if one exists (created
-- by that same guest's /play bot games, which post straight to the ingest gateway and
-- never went through the buggy server path): repoint games, then drop the duplicate.
-- A guest who only ever played live games has no twin yet -- just relabel in place.
-- Idempotent, same merge pattern as V6__dedupe_site_bots.sql -- safe to re-run.

DO $$
DECLARE
  rec       RECORD;
  canonical TEXT;
  survivor  UUID;
BEGIN
  FOR rec IN
    SELECT p.id,
           -- Strip exactly one leading 'guest:' (6 chars: g-u-e-s-t-:) -- the corrupted
           -- form is always that prefix glued onto an already-valid 'guest:<uuid>'.
           substring(p.external_id FROM 7) AS target
    FROM players p
    WHERE p.external_id LIKE 'guest:guest:%'
  LOOP
    canonical := rec.target;

    SELECT id INTO survivor FROM players WHERE external_id = canonical;

    IF survivor IS NULL THEN
      -- No twin yet: just relabel this row to the canonical id.
      UPDATE players SET external_id = canonical, updated_at = now() WHERE id = rec.id;
    ELSIF survivor <> rec.id THEN
      UPDATE games SET white_player_id = survivor WHERE white_player_id = rec.id;
      UPDATE games SET black_player_id = survivor WHERE black_player_id = rec.id;
      DELETE FROM players WHERE id = rec.id;
    END IF;
  END LOOP;
END $$;

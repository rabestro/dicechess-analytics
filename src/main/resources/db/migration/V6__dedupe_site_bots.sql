-- Deduplicate dicechess.com site bots.
--
-- Site-resident bots are canonically identified by their native (negative) user
-- id -- that is how dicechess-observer ingests them. An earlier dicechess-extension
-- wrote the four "DC Coach" site bots under synthetic `bot:dc-coach-*` ids (and
-- could write `bot:site<id>` for others), creating a second players row per bot.
-- Merge every synthetic site-bot row into its native-id twin: repoint games, then
-- drop the duplicate. Idempotent -- once merged, re-running is a no-op.
--
-- Note: our OWN engine bot (`bot:<algorithm>`, e.g. `bot:aggressive`) is a real
-- distinct player and is intentionally left untouched.

DO $$
DECLARE
  rec       RECORD;
  native_id TEXT;
  survivor  UUID;
BEGIN
  FOR rec IN
    SELECT p.id,
           CASE p.external_id
             WHEN 'bot:dc-coach-rookie'   THEN '-40'
             WHEN 'bot:dc-coach-beginner' THEN '-41'
             WHEN 'bot:dc-coach-amateur'  THEN '-42'
             WHEN 'bot:dc-coach-master'   THEN '-43'
             ELSE substring(p.external_id FROM 9)  -- 'bot:site-101' -> '-101'
           END AS target
    FROM players p
    WHERE p.external_id LIKE 'bot:dc-coach-%'
       OR p.external_id LIKE 'bot:site-%'
  LOOP
    native_id := rec.target;
    SELECT id INTO survivor FROM players WHERE external_id = native_id;

    IF survivor IS NULL THEN
      -- No native-id twin yet: just relabel this row to the native id.
      UPDATE players SET external_id = native_id, updated_at = now() WHERE id = rec.id;
    ELSIF survivor <> rec.id THEN
      UPDATE games SET white_player_id = survivor WHERE white_player_id = rec.id;
      UPDATE games SET black_player_id = survivor WHERE black_player_id = rec.id;
      DELETE FROM players WHERE id = rec.id;
    END IF;
  END LOOP;
END $$;

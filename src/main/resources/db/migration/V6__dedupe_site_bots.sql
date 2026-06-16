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
           p.external_id AS ext,
           -- Map each synthetic id to its native (negative) id. Match EXACTLY:
           -- anything unexpected resolves to NULL and is skipped below, so a future
           -- `bot:dc-coach-*` variant can never be mis-sliced into a corrupt id.
           CASE
             WHEN p.external_id = 'bot:dc-coach-rookie'   THEN '-40'
             WHEN p.external_id = 'bot:dc-coach-beginner' THEN '-41'
             WHEN p.external_id = 'bot:dc-coach-amateur'  THEN '-42'
             WHEN p.external_id = 'bot:dc-coach-master'   THEN '-43'
             WHEN p.external_id ~ '^bot:site-[0-9]+$'     THEN substring(p.external_id FROM 9)  -- 'bot:site-101' -> '-101'
             ELSE NULL
           END AS target
    FROM players p
    WHERE p.external_id LIKE 'bot:dc-coach-%'
       OR p.external_id LIKE 'bot:site-%'
  LOOP
    native_id := rec.target;

    -- Unexpected/unmapped synthetic id (e.g. an unknown dc-coach variant, or a
    -- malformed `bot:site-`): leave it untouched rather than relabel to a wrong id.
    IF native_id IS NULL THEN
      RAISE NOTICE 'V6: skipping unmapped bot external_id %', rec.ext;
      CONTINUE;
    END IF;

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

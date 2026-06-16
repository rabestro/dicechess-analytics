-- Convert legacy minute-based initial times to seconds.
-- Since the maximum allowed game time is 10 minutes (600 seconds),
-- any value <= 60 is definitively minutes.
UPDATE games
SET time_initial_sec = time_initial_sec * 60
WHERE time_initial_sec > 0 AND time_initial_sec <= 60;

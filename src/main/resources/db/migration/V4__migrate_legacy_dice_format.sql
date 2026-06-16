UPDATE turns
SET dice_sorted = (
  SELECT string_agg(ch, '' ORDER BY ch)
  FROM string_to_table(
    translate(dice_sorted, '123456', CASE WHEN active_color = 'w' THEN 'PNBRQK' ELSE 'pnbrqk' END),
    NULL
  ) AS ch
)
WHERE dice_sorted ~ '^[1-6]+$';

ALTER TABLE turns ADD CONSTRAINT chk_dice_sorted_letters CHECK (dice_sorted ~ '^[pnbqrkPNBQRK]{1,3}$');

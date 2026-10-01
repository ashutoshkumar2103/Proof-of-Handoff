-- Correct stored statuses: a handoff with confirmed MISSING items is NOT fully returned
-- (some items never came back). Such handoffs were wrongly left as FULLY_RETURNED by the
-- earlier status logic; reclassify them as PARTIALLY_RETURNED so they are not treated as
-- complete. CLOSED handoffs are left untouched (already finalized by the owner).
UPDATE handoff
SET status = 'PARTIALLY_RETURNED'
WHERE status = 'FULLY_RETURNED'
  AND id IN (
      SELECT re.handoff_id
      FROM return_event re
      JOIN return_line rl ON rl.return_event_id = re.id
      WHERE re.confirmed = TRUE AND rl.item_condition = 'MISSING'
  );

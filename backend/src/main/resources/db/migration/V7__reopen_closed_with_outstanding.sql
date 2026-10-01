-- Correct stored statuses: a handoff was wrongly closable while items were still
-- genuinely outstanding (neither returned nor accounted as missing). Reopen any CLOSED
-- handoff that has an item whose outgoing quantity still exceeds the total of its confirmed
-- return lines (returned + missing) — that surplus is outstanding and must be returned
-- before closing. Handoffs closed with only missing items (lines cover the outgoing qty)
-- are left untouched, since closing those is allowed once the recipient confirms.
UPDATE handoff
SET status = 'PARTIALLY_RETURNED'
WHERE status = 'CLOSED'
  AND id IN (
      SELECT hi.handoff_id
      FROM handoff_item hi
      WHERE hi.quantity > COALESCE((
          SELECT SUM(rl.quantity)
          FROM return_line rl
          JOIN return_event re ON rl.return_event_id = re.id
          WHERE re.confirmed = TRUE
            AND rl.handoff_item_id = hi.id
      ), 0)
  );

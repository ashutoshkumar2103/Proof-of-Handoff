-- Capture the recipient's typed acknowledgement name when they confirm missing items,
-- mirroring the acknowledgement name captured on accept. Proof of who confirmed the loss.
ALTER TABLE handoff ADD COLUMN missing_confirmed_by_name VARCHAR(200);

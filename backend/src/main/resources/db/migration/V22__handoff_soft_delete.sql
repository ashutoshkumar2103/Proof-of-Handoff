-- Deleting a draft handoff takes it out of the customer's workspace but never out of its history: its audit events (and any
-- attachments) reference the handoff row, and audit events are append-only, so the row is kept and marked as deleted instead of
-- erased. The application never loads a row with this set (see Handoff.deletedAt). Existing rows are untouched (NULL = not deleted).
ALTER TABLE handoff
    ADD COLUMN deleted_at DATETIME(6);

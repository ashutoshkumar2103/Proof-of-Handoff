-- When items are reported MISSING (lost, not coming back), the recipient must confirm
-- the loss before the handoff can be closed. These timestamps track that flow.
ALTER TABLE handoff
    ADD COLUMN missing_confirmation_requested_at DATETIME(6);
ALTER TABLE handoff
    ADD COLUMN missing_confirmed_at DATETIME(6);

-- Record the condition of each item at handover (GOOD/DAMAGED/MISSING/OTHER).
-- Returns still capture their own per-return condition on return_line.
ALTER TABLE handoff_item
    ADD COLUMN item_condition VARCHAR(20) NOT NULL DEFAULT 'GOOD';

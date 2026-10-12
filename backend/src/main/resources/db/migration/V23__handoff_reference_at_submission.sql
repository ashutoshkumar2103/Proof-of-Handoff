-- A handoff's customer-facing reference (public_code) is now issued when the draft is first submitted, not when it is created.
-- Portable SQL: runs on MySQL 8 (prod/dev) and H2 in MySQL mode (tests).
--
-- NON-DESTRUCTIVE: no id, token, reference or audit event is changed.
--   * public_code may now be NULL: a new draft has no reference until it is submitted. Every reference already issued
--     (including those of drafts that were numbered when they were created) is kept exactly as it is, and so is the
--     per-account counter, so nothing is renumbered or reused. The unique key (owner_user_id, public_code) is untouched:
--     it allows any number of NULLs.
--   * reference_number is the numeric part of the reference (AV-12 -> 12), so lists can be put in reference order by the
--     database instead of by comparing text (AV-10 would come before AV-9) or by the internal id (which no longer follows
--     the order references are issued in). It is filled in here for every existing handoff from its reference, and set
--     whenever a reference is issued; it stays NULL for a draft that has none.

ALTER TABLE handoff MODIFY public_code VARCHAR(20) NULL;

ALTER TABLE handoff ADD COLUMN reference_number BIGINT NULL;

UPDATE handoff
   SET reference_number = CAST(SUBSTRING(public_code, LOCATE('-', public_code) + 1) AS DECIMAL(19, 0))
 WHERE public_code IS NOT NULL;

CREATE INDEX ix_handoff_owner_reference ON handoff (owner_user_id, reference_number);

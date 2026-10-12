-- A draft gets a reference of its own (AKD-1, AKD-2 ...: the account's handoff prefix, then D for draft), issued when the draft is
-- created, so a customer with several drafts can tell them apart. It is kept apart from the official reference (public_code, AK-1 ...),
-- which is still issued only when the draft is first submitted from the account's own handoff counter: creating, editing, duplicating
-- or deleting a draft never uses an official number, and a draft's own reference never becomes its official one. Additive only; portable
-- SQL (MySQL 8 and H2 in MySQL mode).
--
-- app_user.draft_sequence   the last draft number issued to the account: its own counter, never reset and never reused (a deleted draft
--                           keeps its number), independent of handoff_sequence and of the prefix.
-- handoff.draft_code        the draft's reference as it was issued (e.g. AKD-3), stored so that it never changes when the account's
--                           prefix later does. NULL for a handoff that was numbered when it was created (before references moved to
--                           submission): it keeps its official reference and needs none. It stays on the row after submission, for the
--                           record; it is not shown as the reference then. Unique per account.
ALTER TABLE app_user ADD COLUMN draft_sequence BIGINT NOT NULL DEFAULT 0;
ALTER TABLE handoff ADD COLUMN draft_code VARCHAR(20) NULL;

-- Drafts that already exist and have no reference (including deleted ones, so their numbers are never reused) are numbered per account
-- in the order they were created, under the account's current prefix, and each account's counter is set to the last number used. The
-- numbers are worked out in a scratch table because MySQL does not allow an UPDATE to read the table it is changing.
CREATE TABLE tmp_draft_rank AS
SELECT id, owner_user_id, ROW_NUMBER() OVER (PARTITION BY owner_user_id ORDER BY id) AS rn
  FROM handoff
 WHERE public_code IS NULL;

UPDATE handoff
   SET draft_code = (SELECT CONCAT(u.handoff_prefix, 'D-', t.rn)
                       FROM tmp_draft_rank t JOIN app_user u ON u.id = t.owner_user_id
                      WHERE t.id = handoff.id)
 WHERE public_code IS NULL;

UPDATE app_user
   SET draft_sequence = (SELECT COALESCE(MAX(t.rn), 0) FROM tmp_draft_rank t WHERE t.owner_user_id = app_user.id);

DROP TABLE tmp_draft_rank;

-- NULLs do not clash, so only the drafts that have a reference are held to this.
ALTER TABLE handoff ADD CONSTRAINT uq_handoff_owner_draft_code UNIQUE (owner_user_id, draft_code);

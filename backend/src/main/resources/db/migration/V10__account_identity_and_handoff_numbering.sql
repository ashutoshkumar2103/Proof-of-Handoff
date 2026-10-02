-- Customer account identity, subscription plan and per-account handoff numbering.
-- Portable SQL: runs on MySQL 8 (prod/dev) and H2 in MySQL mode (tests).
--
-- NON-DESTRUCTIVE: no id, token or existing handoff code is changed.
--   * handoff.public_code keeps every value already issued (HO-1, HO-14, ...). Those codes were
--     globally unique, so they are trivially unique per account; only the constraint is relaxed
--     from "globally unique" to "unique per owning account".
--   * Each existing account starts its own counter at the highest handoff id it already owns, which
--     is the highest HO-<n> it has ever been shown (V6 made every code exactly HO-<id>). Its next
--     handoff is therefore one past anything it has seen, so no existing reference is reused.
--   * Existing accounts get CUS-<id padded to 6> and the default prefix HO, which matches the codes
--     they already have. The global account counter then continues after the highest id.

ALTER TABLE app_user ADD COLUMN account_code      VARCHAR(20) NOT NULL DEFAULT '';
ALTER TABLE app_user ADD COLUMN phone             VARCHAR(40);
ALTER TABLE app_user ADD COLUMN subscription_plan VARCHAR(20) NOT NULL DEFAULT 'MONTHLY';
ALTER TABLE app_user ADD COLUMN handoff_prefix    VARCHAR(5)  NOT NULL DEFAULT 'HO';
ALTER TABLE app_user ADD COLUMN handoff_sequence  BIGINT      NOT NULL DEFAULT 0;

UPDATE app_user SET account_code = CONCAT('CUS-', LPAD(id, 6, '0'));

UPDATE app_user SET handoff_sequence =
    COALESCE((SELECT MAX(h.id) FROM handoff h WHERE h.owner_user_id = app_user.id), 0);

ALTER TABLE app_user ADD CONSTRAINT uq_app_user_account_code UNIQUE (account_code);

-- A handoff reference only has to be unique within its account.
ALTER TABLE handoff DROP CONSTRAINT uq_handoff_public_code;
ALTER TABLE handoff ADD CONSTRAINT uq_handoff_owner_code UNIQUE (owner_user_id, public_code);

-- Global counters for human-friendly identifiers (account IDs now; ticket IDs in V11).
CREATE TABLE sequence_counter (
    name       VARCHAR(40) NOT NULL,
    next_value BIGINT      NOT NULL,
    CONSTRAINT pk_sequence_counter PRIMARY KEY (name)
);
INSERT INTO sequence_counter (name, next_value)
    SELECT 'ACCOUNT', COALESCE(MAX(id), 0) + 1 FROM app_user;

-- Password reset and session revocation. Additive only; portable SQL (MySQL 8 and H2 in MySQL mode).
--
-- token_version: every customer token carries the version it was issued under. Changing or resetting a password
-- raises it, which makes every older token stop working at once. Existing customers start at 0, which is what a
-- token issued before this change counts as, so nobody is signed out by upgrading.
ALTER TABLE app_user ADD COLUMN token_version INT NOT NULL DEFAULT 0;

-- One-time password reset links. Only the SHA-256 hash of the link's token is stored (like recipient links).
-- A used or expired row is kept as a record; it can never be used again.
CREATE TABLE password_reset_token (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    version     BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    token_hash  VARCHAR(64)  NOT NULL,
    expires_at  DATETIME(6)  NOT NULL,
    used_at     DATETIME(6),
    CONSTRAINT pk_password_reset_token PRIMARY KEY (id),
    CONSTRAINT uq_password_reset_token_hash UNIQUE (token_hash),
    CONSTRAINT fk_password_reset_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_password_reset_user ON password_reset_token (user_id);

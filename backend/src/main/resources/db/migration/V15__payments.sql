-- Payments: a customer pays for a plan (today only the DEMO provider, for testing — no real money) and receives a
-- one-time token; redeeming it while signed in applies the plan. Portable SQL: MySQL 8 and H2 (MySQL mode).
-- Only the SHA-256 hash of the token is stored. A row is never deleted or edited except to record its redemption,
-- so it stays as the record of what was bought, by whom, and which plan it replaced.

CREATE TABLE payment (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    version             BIGINT       NOT NULL,
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    provider            VARCHAR(20)  NOT NULL,
    plan                VARCHAR(20)  NOT NULL,
    amount              INT          NOT NULL,
    currency            VARCHAR(3)   NOT NULL,
    token_hash          VARCHAR(64)  NOT NULL,
    paid_at             DATETIME(6)  NOT NULL,
    expires_at          DATETIME(6)  NOT NULL,
    redeemed_at         DATETIME(6),
    redeemed_by_user_id BIGINT,
    plan_before         VARCHAR(20),
    CONSTRAINT pk_payment PRIMARY KEY (id),
    CONSTRAINT uq_payment_token UNIQUE (token_hash),
    CONSTRAINT fk_payment_redeemed_by FOREIGN KEY (redeemed_by_user_id) REFERENCES app_user (id),
    -- Redeemed means all three facts are recorded; unredeemed means none are.
    CONSTRAINT ck_payment_redemption CHECK (
        (redeemed_at IS NULL AND redeemed_by_user_id IS NULL AND plan_before IS NULL)
        OR (redeemed_at IS NOT NULL AND redeemed_by_user_id IS NOT NULL AND plan_before IS NOT NULL))
);
CREATE INDEX ix_payment_redeemed_by ON payment (redeemed_by_user_id);

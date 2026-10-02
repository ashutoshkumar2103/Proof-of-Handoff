-- The support team's subscription-expiry reminder job. Additive only; portable SQL (MySQL 8 and H2 in MySQL mode).
--
-- support_job: the settings and latest result of a support-side job. There is one row per kind of job (unique
-- job_type), created the first time staff open it, switched OFF: nothing is emailed until an administrator or
-- manager turns it on. Only the latest result is kept.
CREATE TABLE support_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    version         BIGINT        NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    job_type        VARCHAR(40)   NOT NULL,
    enabled         BOOLEAN       NOT NULL,
    cron_expression VARCHAR(100)  NOT NULL,
    timezone        VARCHAR(60)   NOT NULL,
    window_days     INT           NOT NULL,
    next_run_at     DATETIME(6),
    last_run_at     DATETIME(6),
    last_status     VARCHAR(20),
    last_count      INT,
    last_detail     VARCHAR(500),
    CONSTRAINT pk_support_job PRIMARY KEY (id),
    CONSTRAINT uq_support_job_type UNIQUE (job_type)
);
CREATE INDEX ix_support_job_due ON support_job (enabled, next_run_at);

-- One row per reminder sent: the customer and the subscription end date it was about. A customer is reminded about
-- a given end date once; renewing moves the end date, so the next period can be reminded about again. The unique
-- key is what makes a duplicate impossible, even if two runs overlap.
CREATE TABLE subscription_expiry_reminder (
    id          BIGINT       NOT NULL AUTO_INCREMENT,
    version     BIGINT       NOT NULL,
    created_at  DATETIME(6)  NOT NULL,
    updated_at  DATETIME(6)  NOT NULL,
    user_id     BIGINT       NOT NULL,
    valid_until DATETIME(6)  NOT NULL,
    plan        VARCHAR(20)  NOT NULL,
    CONSTRAINT pk_subscription_expiry_reminder PRIMARY KEY (id),
    CONSTRAINT uq_subscription_expiry_reminder UNIQUE (user_id, valid_until),
    CONSTRAINT fk_subscription_expiry_reminder_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);

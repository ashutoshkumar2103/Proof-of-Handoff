-- Customer jobs: scheduled reminders and a weekly summary, one row per customer per kind of job. Additive only;
-- portable SQL (MySQL 8 and H2 in MySQL mode).
--
-- Every row belongs to one customer (user_id) and is only ever read or run for that customer. The row holds the
-- customer's own schedule and ONLY the latest result of the job, not a history: last_run_at / last_status /
-- last_handoffs / last_detail are overwritten by the next run. enabled starts FALSE (a job sends nothing until the
-- customer turns it on), and a job with no row yet is simply a job the customer has not opened.
CREATE TABLE customer_job (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    version         BIGINT        NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    user_id         BIGINT        NOT NULL,
    job_type        VARCHAR(30)   NOT NULL,
    enabled         BOOLEAN       NOT NULL,
    cron_expression VARCHAR(100)  NOT NULL,
    timezone        VARCHAR(60)   NOT NULL,
    next_run_at     DATETIME(6),
    last_run_at     DATETIME(6),
    last_status     VARCHAR(20),
    last_handoffs   VARCHAR(2000),
    last_detail     VARCHAR(500),
    CONSTRAINT pk_customer_job PRIMARY KEY (id),
    -- One customer cannot hold two configurations of the same job.
    CONSTRAINT uq_customer_job UNIQUE (user_id, job_type),
    CONSTRAINT fk_customer_job_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
-- The scheduler looks for enabled jobs whose time has come.
CREATE INDEX ix_customer_job_due ON customer_job (enabled, next_run_at);

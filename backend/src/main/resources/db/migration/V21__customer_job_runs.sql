-- Job history: one row for every time one of a customer's jobs actually ran. Additive only; portable SQL (MySQL 8 and
-- H2 in MySQL mode).
--
-- customer_job keeps only the LATEST result of a job (it is overwritten by the next run). This table keeps every run,
-- append-only: nothing in the application updates or deletes a row, so a later success never overwrites an earlier
-- partial or failed run. A run belongs to one customer (user_id) and one kind of job and is only ever read for that
-- customer.
--
-- sent_handoffs   the references of the handoffs the run's one email told the customer about (empty when it sent nothing)
-- failed_handoffs the references that could not be included, or - when the email itself failed - everything that was not
--                 delivered; comma separated, cut to fit the column like customer_job.last_handoffs
-- detail          the SAFE, customer-readable reason for the failures (never an exception message)
-- trigger_kind    SCHEDULED, RUN_NOW or RUN_ALL_NOW; NULL only on the rows copied below, whose trigger was never recorded
CREATE TABLE customer_job_run (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    version         BIGINT        NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    user_id         BIGINT        NOT NULL,
    job_type        VARCHAR(30)   NOT NULL,
    trigger_kind    VARCHAR(20),
    run_at          DATETIME(6)   NOT NULL,
    status          VARCHAR(20)   NOT NULL,
    sent_handoffs   VARCHAR(2000),
    failed_handoffs VARCHAR(2000),
    detail          VARCHAR(500),
    CONSTRAINT pk_customer_job_run PRIMARY KEY (id),
    CONSTRAINT fk_customer_job_run_user FOREIGN KEY (user_id) REFERENCES app_user (id)
);
-- A customer's history, newest first; and "the latest run of this job" for the retry rule.
CREATE INDEX ix_customer_job_run_user ON customer_job_run (user_id, id);
CREATE INDEX ix_customer_job_run_job ON customer_job_run (user_id, job_type, id);

-- Keep what the monitoring page already showed: each job's latest run becomes the first row of its history. A failed run's
-- references were the ones the email could not deliver, so they go in the failed column.
INSERT INTO customer_job_run (version, created_at, updated_at, user_id, job_type, trigger_kind, run_at, status,
                              sent_handoffs, failed_handoffs, detail)
SELECT 0, last_run_at, last_run_at, user_id, job_type, NULL, last_run_at, last_status,
       CASE WHEN last_status = 'FAILED' THEN NULL ELSE last_handoffs END,
       CASE WHEN last_status = 'FAILED' THEN last_handoffs ELSE NULL END,
       last_detail
FROM customer_job
WHERE last_run_at IS NOT NULL AND last_status IS NOT NULL;

-- Who ran a job, and who changed a job's settings. Additive only: two new nullable columns on customer_job_run and one new
-- table. No existing column, row or constraint is changed or rewritten; portable SQL (MySQL 8 and H2 in MySQL mode).
--
-- customer_job_run.actor_*  the person who pressed Run now / Run All Now: their stable id (the account's user id), the name they
--                           had at that moment (a snapshot, so a later rename never rewrites history) and what kind of actor
--                           they were (ActorType). They stay NULL for scheduled runs (the scheduler is the actor, shown as
--                           "System") and for every run recorded before this migration, whose actor was never recorded.
--
-- customer_job_change      one row per successful change to a customer's job settings: its schedule (cron expression and
--                          timezone together), or paused/resumed. Append-only, like customer_job_run: nothing in the application
--                          updates or deletes a row. account_id is the customer whose job it is; actor_user_id is who changed it.
--                          They are separate columns so another kind of actor can be added later without touching account_id.
--                          previous_value/new_value are the settings before and after, as text ("0 0 9 * * * · Asia/Kolkata",
--                          "Active", "Paused").
ALTER TABLE customer_job_run ADD COLUMN actor_user_id BIGINT;
ALTER TABLE customer_job_run ADD COLUMN actor_name VARCHAR(150);
ALTER TABLE customer_job_run ADD COLUMN actor_type VARCHAR(20);
ALTER TABLE customer_job_run ADD CONSTRAINT fk_customer_job_run_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id);

CREATE TABLE customer_job_change (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    version        BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    account_id     BIGINT        NOT NULL,
    job_type       VARCHAR(30)   NOT NULL,
    action         VARCHAR(30)   NOT NULL,
    previous_value VARCHAR(200),
    new_value      VARCHAR(200)  NOT NULL,
    actor_user_id  BIGINT        NOT NULL,
    actor_name     VARCHAR(150)  NOT NULL,
    actor_type     VARCHAR(20)   NOT NULL,
    changed_at     DATETIME(6)   NOT NULL,
    CONSTRAINT pk_customer_job_change PRIMARY KEY (id),
    CONSTRAINT fk_customer_job_change_account FOREIGN KEY (account_id) REFERENCES app_user (id),
    CONSTRAINT fk_customer_job_change_actor FOREIGN KEY (actor_user_id) REFERENCES app_user (id)
);
-- A customer's changes, newest first.
CREATE INDEX ix_customer_job_change_account ON customer_job_change (account_id, id);

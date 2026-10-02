-- Subscription lifecycle and history. Additive only; portable SQL (MySQL 8 and H2 in MySQL mode).
--
-- plan_started_at / plan_valid_until: when the current plan began and until when it is paid for. Both are NULL for
-- every existing customer, on purpose: the real dates were never recorded and are not invented here. NULL
-- valid-until means "no end date", which is exactly how every existing plan behaved, so nobody's access changes.
-- The status (ACTIVE / INACTIVE) is not stored: it follows from valid-until, so it can never go stale.
ALTER TABLE app_user ADD COLUMN plan_started_at DATETIME(6) NULL;
ALTER TABLE app_user ADD COLUMN plan_valid_until DATETIME(6) NULL;

-- Every plan a customer was moved to, append-only: nothing in the application updates or deletes a row.
-- staff_id is set when support made the change; source says who (STAFF) or what (PAYMENT) did.
CREATE TABLE subscription_history (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    user_id       BIGINT       NOT NULL,
    previous_plan VARCHAR(20),
    new_plan      VARCHAR(20)  NOT NULL,
    starts_at     DATETIME(6),
    valid_until   DATETIME(6),
    source        VARCHAR(20)  NOT NULL,
    staff_id      BIGINT,
    reason        VARCHAR(500),
    CONSTRAINT pk_subscription_history PRIMARY KEY (id),
    CONSTRAINT fk_subscription_history_user FOREIGN KEY (user_id) REFERENCES app_user (id),
    CONSTRAINT fk_subscription_history_staff FOREIGN KEY (staff_id) REFERENCES support_staff (id)
);
CREATE INDEX ix_subscription_history_user ON subscription_history (user_id, id);

-- Fill the history with what really happened before this change: the plan changes support made (recorded in the
-- audit trail) and the plans customers paid for. Both took effect when they were recorded; how long they were
-- meant to last was never stored, so valid_until stays NULL. Customers who were never moved get no row.
INSERT INTO subscription_history (version, created_at, updated_at, user_id, previous_plan, new_plan, starts_at,
                                  valid_until, source, staff_id, reason)
SELECT 0, e.created_at, e.created_at, e.customer_id, e.previous_value, e.new_value, e.created_at,
       NULL, 'STAFF', e.staff_id, e.reason
FROM support_audit_event e
WHERE e.event_type = 'PLAN_CHANGED' AND e.customer_id IS NOT NULL;

INSERT INTO subscription_history (version, created_at, updated_at, user_id, previous_plan, new_plan, starts_at,
                                  valid_until, source, staff_id, reason)
SELECT 0, p.redeemed_at, p.redeemed_at, p.redeemed_by_user_id, p.plan_before, p.plan, p.redeemed_at,
       NULL, 'PAYMENT', NULL, NULL
FROM payment p
WHERE p.redeemed_at IS NOT NULL AND p.redeemed_by_user_id IS NOT NULL;

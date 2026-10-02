-- Support staff become their own identity, separate from customers; plan/prefix changes get an audit trail.
-- Portable SQL: MySQL 8 and H2 (MySQL mode).
--
-- NON-DESTRUCTIVE for customers: no customer row, id, Account ID, plan, prefix, handoff or token is touched.

-- 1) A customer has no role any more. The legacy column is kept (nothing is destroyed) but given a default,
--    because the application no longer writes it. Any customer that was ever given a staff role by the
--    earlier design is returned to an ordinary customer: support staff are not customers.
ALTER TABLE app_user ALTER COLUMN role SET DEFAULT 'USER';
UPDATE app_user SET role = 'USER' WHERE role <> 'USER';

-- 2) Support staff: their own table, Staff IDs (STAFF-000001, ...) from the shared counter table.
INSERT INTO sequence_counter (name, next_value) VALUES ('STAFF', 1);

CREATE TABLE support_staff (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    staff_code    VARCHAR(20)  NOT NULL,
    name          VARCHAR(150) NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    role          VARCHAR(20)  NOT NULL,
    active        BOOLEAN      NOT NULL DEFAULT TRUE,
    CONSTRAINT pk_support_staff PRIMARY KEY (id),
    CONSTRAINT uq_support_staff_code UNIQUE (staff_code),
    CONSTRAINT uq_support_staff_email UNIQUE (email)
);

-- 3) Audit trail of what staff change on a customer account (plan, handoff prefix). Append-only.
CREATE TABLE support_audit_event (
    id             BIGINT       NOT NULL AUTO_INCREMENT,
    version        BIGINT       NOT NULL,
    created_at     DATETIME(6)  NOT NULL,
    updated_at     DATETIME(6)  NOT NULL,
    staff_id       BIGINT       NOT NULL,
    customer_id    BIGINT       NOT NULL,
    event_type     VARCHAR(30)  NOT NULL,
    previous_value VARCHAR(60)  NOT NULL,
    new_value      VARCHAR(60)  NOT NULL,
    reason         VARCHAR(500),
    CONSTRAINT pk_support_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_support_audit_staff FOREIGN KEY (staff_id) REFERENCES support_staff (id),
    CONSTRAINT fk_support_audit_customer FOREIGN KEY (customer_id) REFERENCES app_user (id)
);
CREATE INDEX ix_support_audit_customer ON support_audit_event (customer_id, id);

-- 4) A ticket reply is written by a customer OR by a staff member: exactly one of the two references is set.
--    Existing replies keep their customer reference.
ALTER TABLE support_ticket_message MODIFY author_user_id BIGINT NULL;
ALTER TABLE support_ticket_message ADD COLUMN author_staff_id BIGINT NULL;
ALTER TABLE support_ticket_message ADD CONSTRAINT fk_message_author_staff
    FOREIGN KEY (author_staff_id) REFERENCES support_staff (id);
ALTER TABLE support_ticket_message ADD CONSTRAINT ck_message_one_author CHECK (
    (author_user_id IS NOT NULL AND author_staff_id IS NULL)
    OR (author_user_id IS NULL AND author_staff_id IS NOT NULL));

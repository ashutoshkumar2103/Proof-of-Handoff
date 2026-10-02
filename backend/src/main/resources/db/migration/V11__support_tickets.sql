-- Support: a lightweight ticket system (not a CRM). Portable SQL: MySQL 8 and H2 (MySQL mode).
-- A ticket belongs to a customer account; replies are kept as messages; files reuse the existing
-- blob storage, so only metadata lives here.

INSERT INTO sequence_counter (name, next_value) VALUES ('TICKET', 1);

CREATE TABLE support_ticket (
    id                BIGINT        NOT NULL AUTO_INCREMENT,
    version           BIGINT        NOT NULL,
    created_at        DATETIME(6)   NOT NULL,
    updated_at        DATETIME(6)   NOT NULL,
    ticket_code       VARCHAR(20)   NOT NULL,
    account_user_id   BIGINT        NOT NULL,
    category          VARCHAR(30)   NOT NULL,
    contact_method    VARCHAR(20)   NOT NULL,
    subject           VARCHAR(200)  NOT NULL,
    description       VARCHAR(5000) NOT NULL,
    -- The customer's own handoff reference, kept as text so support sees the reference without
    -- being given the handoff itself.
    handoff_reference VARCHAR(20),
    contact_phone     VARCHAR(40),
    status            VARCHAR(30)   NOT NULL,
    message_count     INT           NOT NULL,
    CONSTRAINT pk_support_ticket PRIMARY KEY (id),
    CONSTRAINT uq_support_ticket_code UNIQUE (ticket_code),
    CONSTRAINT fk_ticket_account FOREIGN KEY (account_user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_ticket_account ON support_ticket (account_user_id);
CREATE INDEX ix_ticket_status ON support_ticket (status, updated_at);

CREATE TABLE support_ticket_message (
    id             BIGINT        NOT NULL AUTO_INCREMENT,
    version        BIGINT        NOT NULL,
    created_at     DATETIME(6)   NOT NULL,
    updated_at     DATETIME(6)   NOT NULL,
    ticket_id      BIGINT        NOT NULL,
    author_user_id BIGINT        NOT NULL,
    author_role    VARCHAR(20)   NOT NULL,
    body           VARCHAR(5000) NOT NULL,
    CONSTRAINT pk_support_ticket_message PRIMARY KEY (id),
    CONSTRAINT fk_message_ticket FOREIGN KEY (ticket_id) REFERENCES support_ticket (id),
    CONSTRAINT fk_message_author FOREIGN KEY (author_user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_ticket_message_ticket ON support_ticket_message (ticket_id);

CREATE TABLE support_ticket_attachment (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    version           BIGINT       NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    ticket_id         BIGINT       NOT NULL,
    storage_key       VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    CONSTRAINT pk_support_ticket_attachment PRIMARY KEY (id),
    CONSTRAINT fk_ticket_attachment_ticket FOREIGN KEY (ticket_id) REFERENCES support_ticket (id)
);
CREATE INDEX ix_ticket_attachment_ticket ON support_ticket_attachment (ticket_id);

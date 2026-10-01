-- HandOffly initial schema.
-- Portable SQL: runs on MySQL 8.4 (prod/dev) and H2 in MySQL mode (tests).
-- No ENGINE/CHARSET clauses (MySQL 8 defaults to utf8mb4; H2 rejects those clauses).
-- Reserved words avoided in column names (item_condition, event_type, sort_order).

CREATE TABLE app_user (
    id            BIGINT       NOT NULL AUTO_INCREMENT,
    version       BIGINT       NOT NULL,
    created_at    DATETIME(6)  NOT NULL,
    updated_at    DATETIME(6)  NOT NULL,
    email         VARCHAR(255) NOT NULL,
    password_hash VARCHAR(100) NOT NULL,
    display_name  VARCHAR(150) NOT NULL,
    organization  VARCHAR(200),
    role          VARCHAR(20)  NOT NULL,
    enabled       BOOLEAN      NOT NULL,
    CONSTRAINT pk_app_user PRIMARY KEY (id),
    CONSTRAINT uq_app_user_email UNIQUE (email)
);

CREATE TABLE handoff (
    id                   BIGINT       NOT NULL AUTO_INCREMENT,
    version              BIGINT       NOT NULL,
    created_at           DATETIME(6)  NOT NULL,
    updated_at           DATETIME(6)  NOT NULL,
    public_code          VARCHAR(20)  NOT NULL,
    owner_user_id        BIGINT       NOT NULL,
    title                VARCHAR(200) NOT NULL,
    purpose              VARCHAR(2000),
    category             VARCHAR(60),
    sender_name          VARCHAR(200) NOT NULL,
    sender_organization  VARCHAR(200),
    recipient_name       VARCHAR(200) NOT NULL,
    recipient_email      VARCHAR(255) NOT NULL,
    recipient_phone      VARCHAR(40),
    status               VARCHAR(30)  NOT NULL,
    outgoing_at          DATETIME(6),
    acceptance_at        DATETIME(6),
    due_at               DATETIME(6),
    acknowledgement_name VARCHAR(200),
    acknowledged_at      DATETIME(6),
    rejection_reason     VARCHAR(1000),
    CONSTRAINT pk_handoff PRIMARY KEY (id),
    CONSTRAINT uq_handoff_public_code UNIQUE (public_code),
    CONSTRAINT fk_handoff_owner FOREIGN KEY (owner_user_id) REFERENCES app_user (id)
);
CREATE INDEX ix_handoff_owner_status ON handoff (owner_user_id, status);
CREATE INDEX ix_handoff_due_at ON handoff (due_at);

CREATE TABLE handoff_item (
    id            BIGINT        NOT NULL AUTO_INCREMENT,
    version       BIGINT        NOT NULL,
    created_at    DATETIME(6)   NOT NULL,
    updated_at    DATETIME(6)   NOT NULL,
    handoff_id    BIGINT        NOT NULL,
    name          VARCHAR(300)  NOT NULL,
    description   VARCHAR(1000),
    sku           VARCHAR(80),
    serial_number VARCHAR(120),
    asset_number  VARCHAR(120),
    quantity      DECIMAL(19,3) NOT NULL,
    unit          VARCHAR(30),
    notes         VARCHAR(1000),
    sort_order    INT           NOT NULL,
    CONSTRAINT pk_handoff_item PRIMARY KEY (id),
    CONSTRAINT fk_item_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (id)
);
CREATE INDEX ix_item_handoff ON handoff_item (handoff_id);

CREATE TABLE return_event (
    id               BIGINT       NOT NULL AUTO_INCREMENT,
    version          BIGINT       NOT NULL,
    created_at       DATETIME(6)  NOT NULL,
    updated_at       DATETIME(6)  NOT NULL,
    handoff_id       BIGINT       NOT NULL,
    occurred_at      DATETIME(6)  NOT NULL,
    entered_by_type  VARCHAR(20)  NOT NULL,
    entered_by_ref   VARCHAR(200),
    note             VARCHAR(1000),
    confirmed        BOOLEAN      NOT NULL,
    confirmed_at     DATETIME(6),
    confirmed_by_ref VARCHAR(200),
    CONSTRAINT pk_return_event PRIMARY KEY (id),
    CONSTRAINT fk_return_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (id)
);
CREATE INDEX ix_return_event_handoff ON return_event (handoff_id);

CREATE TABLE return_line (
    id              BIGINT        NOT NULL AUTO_INCREMENT,
    version         BIGINT        NOT NULL,
    created_at      DATETIME(6)   NOT NULL,
    updated_at      DATETIME(6)   NOT NULL,
    return_event_id BIGINT        NOT NULL,
    handoff_item_id BIGINT        NOT NULL,
    quantity        DECIMAL(19,3) NOT NULL,
    item_condition  VARCHAR(20)   NOT NULL,
    note            VARCHAR(1000),
    CONSTRAINT pk_return_line PRIMARY KEY (id),
    CONSTRAINT fk_line_event FOREIGN KEY (return_event_id) REFERENCES return_event (id),
    CONSTRAINT fk_line_item FOREIGN KEY (handoff_item_id) REFERENCES handoff_item (id)
);
CREATE INDEX ix_return_line_event ON return_line (return_event_id);
CREATE INDEX ix_return_line_item ON return_line (handoff_item_id);

CREATE TABLE recipient_link (
    id         BIGINT      NOT NULL AUTO_INCREMENT,
    version    BIGINT      NOT NULL,
    created_at DATETIME(6) NOT NULL,
    updated_at DATETIME(6) NOT NULL,
    handoff_id BIGINT      NOT NULL,
    token_hash VARCHAR(64) NOT NULL,
    expires_at DATETIME(6) NOT NULL,
    opened_at  DATETIME(6),
    revoked    BOOLEAN     NOT NULL,
    CONSTRAINT pk_recipient_link PRIMARY KEY (id),
    CONSTRAINT uq_recipient_token UNIQUE (token_hash),
    CONSTRAINT fk_link_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (id)
);
CREATE INDEX ix_link_handoff ON recipient_link (handoff_id);

CREATE TABLE attachment (
    id                BIGINT       NOT NULL AUTO_INCREMENT,
    version           BIGINT       NOT NULL,
    created_at        DATETIME(6)  NOT NULL,
    updated_at        DATETIME(6)  NOT NULL,
    handoff_id        BIGINT       NOT NULL,
    kind              VARCHAR(30)  NOT NULL,
    storage_key       VARCHAR(100) NOT NULL,
    original_filename VARCHAR(255) NOT NULL,
    content_type      VARCHAR(100) NOT NULL,
    size_bytes        BIGINT       NOT NULL,
    uploaded_by_type  VARCHAR(20)  NOT NULL,
    uploaded_by_ref   VARCHAR(200),
    CONSTRAINT pk_attachment PRIMARY KEY (id),
    CONSTRAINT fk_attachment_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (id)
);
CREATE INDEX ix_attachment_handoff ON attachment (handoff_id);

CREATE TABLE audit_event (
    id         BIGINT        NOT NULL AUTO_INCREMENT,
    version    BIGINT        NOT NULL,
    created_at DATETIME(6)   NOT NULL,
    updated_at DATETIME(6)   NOT NULL,
    handoff_id BIGINT        NOT NULL,
    event_type VARCHAR(40)   NOT NULL,
    actor_type VARCHAR(20)   NOT NULL,
    actor_ref  VARCHAR(200),
    message    VARCHAR(1000) NOT NULL,
    CONSTRAINT pk_audit_event PRIMARY KEY (id),
    CONSTRAINT fk_audit_handoff FOREIGN KEY (handoff_id) REFERENCES handoff (id)
);
CREATE INDEX ix_audit_handoff ON audit_event (handoff_id);

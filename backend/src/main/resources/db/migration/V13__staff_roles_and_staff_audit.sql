-- Staff roles become ADMIN / MANAGER / TICKET_AGENT, and the audit trail can describe changes to staff.
-- Portable SQL: MySQL 8 and H2 (MySQL mode).
--
-- NON-DESTRUCTIVE: no staff member is added, removed or re-identified; Staff IDs, names, emails, password
-- hashes and active flags are untouched, so every login keeps working and every audit link stays valid.

-- 1) The old generic SUPPORT role is replaced. Existing SUPPORT staff become MANAGER: they could already change
--    plans and prefixes and work tickets, which is exactly what a manager may do — nobody loses a capability they
--    had, and nobody gains staff management. ADMIN is kept as it is.
UPDATE support_staff SET role = 'MANAGER' WHERE role = 'SUPPORT';

-- 2) An audit event is now about EITHER a customer (plan / prefix) OR a staff member (created, deactivated,
--    reactivated, role changed): exactly one target is set. A staff creation has no previous value.
ALTER TABLE support_audit_event MODIFY customer_id BIGINT NULL;
ALTER TABLE support_audit_event MODIFY previous_value VARCHAR(60) NULL;
ALTER TABLE support_audit_event ADD COLUMN target_staff_id BIGINT NULL;
ALTER TABLE support_audit_event ADD CONSTRAINT fk_support_audit_target_staff
    FOREIGN KEY (target_staff_id) REFERENCES support_staff (id);
ALTER TABLE support_audit_event ADD CONSTRAINT ck_support_audit_one_target CHECK (
    (customer_id IS NOT NULL AND target_staff_id IS NULL)
    OR (customer_id IS NULL AND target_staff_id IS NOT NULL));

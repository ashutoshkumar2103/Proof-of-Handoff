-- Public IDs drop their padding zeros: CUS-000009 -> CUS-09, TKT-000001 -> TKT-01, STAFF-000001 -> STAFF-01.
-- Only the written form changes. The number inside each ID is kept (nothing is renumbered, the counters carry on
-- from where they were), so every ID stays unique and keeps pointing at the same account, ticket or staff member.
-- Numbers below 10 keep two digits; larger ones are simply written out (CUS-123).
-- Portable SQL (MySQL 8 and H2): LPAD is avoided on purpose, because MySQL's LPAD would cut a longer number short.

UPDATE app_user
SET account_code = CONCAT('CUS-',
        CASE WHEN CHAR_LENGTH(TRIM(LEADING '0' FROM SUBSTRING(account_code, 5))) < 2
             THEN CONCAT('0', TRIM(LEADING '0' FROM SUBSTRING(account_code, 5)))
             ELSE TRIM(LEADING '0' FROM SUBSTRING(account_code, 5)) END)
WHERE account_code LIKE 'CUS-0%';

UPDATE support_ticket
SET ticket_code = CONCAT('TKT-',
        CASE WHEN CHAR_LENGTH(TRIM(LEADING '0' FROM SUBSTRING(ticket_code, 5))) < 2
             THEN CONCAT('0', TRIM(LEADING '0' FROM SUBSTRING(ticket_code, 5)))
             ELSE TRIM(LEADING '0' FROM SUBSTRING(ticket_code, 5)) END)
WHERE ticket_code LIKE 'TKT-0%';

UPDATE support_staff
SET staff_code = CONCAT('STAFF-',
        CASE WHEN CHAR_LENGTH(TRIM(LEADING '0' FROM SUBSTRING(staff_code, 7))) < 2
             THEN CONCAT('0', TRIM(LEADING '0' FROM SUBSTRING(staff_code, 7)))
             ELSE TRIM(LEADING '0' FROM SUBSTRING(staff_code, 7)) END)
WHERE staff_code LIKE 'STAFF-0%';

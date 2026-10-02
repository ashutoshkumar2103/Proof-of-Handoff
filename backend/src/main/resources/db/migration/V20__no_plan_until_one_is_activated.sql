-- A new account has no plan until one is paid for (or put in place by support): no plan, no start, no end, and no
-- active subscription. Until now every account was silently put on MONTHLY at registration, which made an unpaid account
-- look like a paying one. Relaxing only; portable SQL (MySQL 8 and H2 in MySQL mode). Nothing is rewritten: every existing
-- account keeps the plan, start and end it has, and no subscription_history row is touched.
ALTER TABLE app_user MODIFY subscription_plan VARCHAR(20) NULL;

-- The first plan a customer pays for replaces no plan at all, so a redemption has to be able to record that: plan_before may
-- now be empty. What the constraint guarantees is otherwise unchanged: a redeemed payment says when and by whom, an
-- unredeemed one says none of it.
ALTER TABLE payment DROP CONSTRAINT ck_payment_redemption;
ALTER TABLE payment ADD CONSTRAINT ck_payment_redemption CHECK (
    (redeemed_at IS NULL AND redeemed_by_user_id IS NULL AND plan_before IS NULL)
    OR (redeemed_at IS NOT NULL AND redeemed_by_user_id IS NOT NULL));

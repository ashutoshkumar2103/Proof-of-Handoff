package com.handoffly.support;

import com.handoffly.user.SubscriptionPlan;
import org.springframework.data.jpa.domain.Specification;

import java.time.Instant;
import java.util.Collection;

/** Composable filters for listing tickets; a null argument means "no restriction". */
public final class TicketSpecifications {

    private TicketSpecifications() {}

    public static Specification<SupportTicket> ofAccount(Long accountId) {
        return (root, query, cb) -> cb.equal(root.get("account").get("id"), accountId);
    }

    public static Specification<SupportTicket> ofAccountCode(String accountCode) {
        if (accountCode == null || accountCode.isBlank()) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("account").get("accountCode"), accountCode.trim());
    }

    public static Specification<SupportTicket> withStatusIn(Collection<TicketStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /** Tickets of customers on one of these plans whose subscription is active at {@code now} (null plans: any). */
    public static Specification<SupportTicket> onActivePlanIn(Collection<SubscriptionPlan> plans, Instant now) {
        if (plans == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.and(
                root.get("account").get("subscriptionPlan").in(plans),
                cb.or(cb.isNull(root.get("account").get("planValidUntil")),
                        cb.greaterThan(root.<Instant>get("account").get("planValidUntil"), now)));
    }
}

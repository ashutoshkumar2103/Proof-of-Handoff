package com.handoffly.handoff;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/**
 * Reusable, composable query predicates for listing/searching handoffs. Keeping
 * these here avoids scattering ad-hoc queries across the service layer.
 */
public final class HandoffSpecifications {

    private HandoffSpecifications() {}

    public static Specification<Handoff> ownedBy(Long ownerId) {
        return (root, query, cb) -> cb.equal(root.get("owner").get("id"), ownerId);
    }

    public static Specification<Handoff> statusIn(Collection<HandoffStatus> statuses) {
        if (statuses == null || statuses.isEmpty()) {
            return (root, query, cb) -> null; // no restriction
        }
        return (root, query, cb) -> root.get("status").in(statuses);
    }

    /** Case-insensitive match across title, recipient name/email, and public code. */
    public static Specification<Handoff> textSearch(String q) {
        if (q == null || q.isBlank()) {
            return (root, query, cb) -> null; // no restriction
        }
        String like = "%" + q.trim().toLowerCase() + "%";
        return (root, query, cb) -> {
            List<Predicate> ors = new ArrayList<>();
            ors.add(cb.like(cb.lower(root.get("title")), like));
            ors.add(cb.like(cb.lower(root.get("recipientName")), like));
            ors.add(cb.like(cb.lower(root.get("recipientEmail")), like));
            ors.add(cb.like(cb.lower(root.get("publicCode")), like));
            return cb.or(ors.toArray(Predicate[]::new));
        };
    }
}

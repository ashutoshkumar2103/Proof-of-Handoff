package com.handoffly.support.staff;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;

/** Composable, database-side filters for the staff list; a null/blank argument means "no restriction". */
public final class StaffSpecifications {

    private StaffSpecifications() {}

    /** Case-insensitive match on Staff ID, name or email. */
    public static Specification<SupportStaff> matching(String text) {
        if (text == null || text.isBlank()) {
            return (root, query, cb) -> null;
        }
        String like = "%" + text.trim().toLowerCase(Locale.ROOT) + "%";
        return (root, query, cb) -> cb.or(
                cb.like(cb.lower(root.get("staffCode")), like),
                cb.like(cb.lower(root.get("name")), like),
                cb.like(cb.lower(root.get("email")), like));
    }

    public static Specification<SupportStaff> withRole(SupportRole role) {
        if (role == null) {
            return (root, query, cb) -> null;
        }
        return (root, query, cb) -> cb.equal(root.get("role"), role);
    }

    public static Specification<SupportStaff> isActive(Boolean active) {
        if (active == null) {
            return (root, query, cb) -> null;
        }
        Specification<SupportStaff> spec = (root, query, cb) -> {
            Predicate p = cb.isTrue(root.get("active"));
            return active ? p : cb.not(p);
        };
        return spec;
    }
}

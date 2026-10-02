package com.handoffly.support.staff;

import jakarta.persistence.criteria.Predicate;
import org.springframework.data.jpa.domain.Specification;

import java.util.Locale;
import java.util.regex.Pattern;

/** Composable, database-side filters for the staff list; a null/blank argument means "no restriction". */
public final class StaffSpecifications {

    /** A complete Staff ID (STAFF-01, STAFF-120 …). IDs are short, so it must not also match STAFF-010 or STAFF-120. */
    private static final Pattern FULL_STAFF_ID = Pattern.compile("(?i)STAFF-\\d{2,}");

    private StaffSpecifications() {}

    /**
     * Case-insensitive match on Staff ID, name or email. Typing a complete Staff ID finds exactly that person;
     * anything else is a "contains" search.
     */
    public static Specification<SupportStaff> matching(String text) {
        if (text == null || text.isBlank()) {
            return (root, query, cb) -> null;
        }
        String trimmed = text.trim();
        if (FULL_STAFF_ID.matcher(trimmed).matches()) {
            String code = trimmed.toUpperCase(Locale.ROOT);
            return (root, query, cb) -> cb.equal(root.get("staffCode"), code);
        }
        String like = "%" + trimmed.toLowerCase(Locale.ROOT) + "%";
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

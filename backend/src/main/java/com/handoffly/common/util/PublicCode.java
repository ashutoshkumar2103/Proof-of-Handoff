package com.handoffly.common.util;

/**
 * The human-friendly references people see: customer account IDs, support staff IDs, support ticket IDs
 * and handoff references. They are display identifiers only — they never grant access (recipient access uses
 * opaque hashed tokens; every API checks ownership) — so being readable and sequential is safe.
 * The numbers come from {@code SequenceService} (global) or the owning account's own counter.
 */
public final class PublicCode {

    private static final String ACCOUNT_PREFIX = "CUS";
    private static final String TICKET_PREFIX = "TKT";
    private static final String STAFF_PREFIX = "STAFF";

    private PublicCode() {}

    /** e.g. {@code CUS-09}, {@code CUS-42}, {@code CUS-1234} */
    public static String account(long number) {
        return ACCOUNT_PREFIX + "-" + padded(number);
    }

    /** e.g. {@code TKT-01}, {@code TKT-24} */
    public static String ticket(long number) {
        return TICKET_PREFIX + "-" + padded(number);
    }

    /** e.g. {@code STAFF-01}, {@code STAFF-12} */
    public static String staff(long number) {
        return STAFF_PREFIX + "-" + padded(number);
    }

    /** An account-scoped handoff reference, e.g. {@code AV-3}. The prefix belongs to the account. */
    public static String handoff(String accountPrefix, long number) {
        return accountPrefix + "-" + number;
    }

    /** Two digits at least (01, 09, 10, 123): short, but still tidy in lists. */
    private static String padded(long number) {
        return String.format("%02d", number);
    }
}

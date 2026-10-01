package com.handoffly.handoff;

/** Structured condition captured on returned items (alongside free-form notes). */
public enum ItemCondition {
    GOOD,
    DAMAGED,
    MISSING,
    OTHER,
    /** A previously-MISSING item that was later found and returned (offsets the missing count). */
    RECOVERED
}

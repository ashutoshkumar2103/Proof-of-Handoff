package com.handoffly.user;

import java.util.Arrays;
import java.util.List;

/**
 * A customer's subscription plan, with its list price. Every support entitlement is derived from the plan
 * alone — they are never stored or edited separately, so they can't drift from what the customer pays
 * for. This enum is the ONE place that says what each plan includes: the backend's authorization, the
 * customer app and the support portal all read it (through {@link SupportEntitlements}) rather than
 * checking for particular plans. Customers cannot change their plan: support staff do (an explicit,
 * audited action) until payments can. The list prices here are the ONE place prices live too.
 * The core product (handoffs, returns, HandoffCheck, PDFs) is the same on every plan; plans differ in support.
 */
public enum SubscriptionPlan {
    //         months, price, contact support, message, ticket, direct call, priority
    MONTHLY(1, 199, false, false, false, false, SupportPriority.NORMAL),
    /** Assisted: Contact Support in the app, as a simple message — no ticket workflow, no call. */
    QUARTERLY(3, 549, true, true, false, false, SupportPriority.NORMAL),
    HALF_YEARLY(6, 999, true, true, true, false, SupportPriority.PRIORITY),
    YEARLY(12, 1999, true, true, true, true, SupportPriority.HIGHEST);

    /** The currency of every list price (ISO 4217). */
    public static final String CURRENCY = "INR";

    private final int months;
    private final int amount;
    private final boolean contactSupport;
    private final boolean message;
    private final boolean tickets;
    private final boolean directCall;
    private final SupportPriority priority;

    SubscriptionPlan(int months, int amount, boolean contactSupport, boolean message, boolean tickets,
                     boolean directCall, SupportPriority priority) {
        this.months = months;
        this.amount = amount;
        this.contactSupport = contactSupport;
        this.message = message;
        this.tickets = tickets;
        this.directCall = directCall;
        this.priority = priority;
    }

    /** The plans whose customers the support desk treats ahead of normal ones. */
    public static List<SubscriptionPlan> withElevatedPriority() {
        return Arrays.stream(values()).filter(p -> p.priority.isElevated()).toList();
    }

    /** How many months one payment covers. */
    public int months() { return months; }

    /** The list price for one billing period, in whole {@link #CURRENCY} units. */
    public int amount() { return amount; }

    /** The authenticated "Contact Support" area on the customer dashboard. */
    public boolean includesContactSupport() { return contactSupport; }

    /** Sending support a message from inside the app. */
    public boolean includesMessages() { return message; }

    /** Creating, following and replying to support tickets. */
    public boolean includesTickets() { return tickets; }

    public boolean includesDirectCall() { return directCall; }

    public SupportPriority supportPriority() { return priority; }
}

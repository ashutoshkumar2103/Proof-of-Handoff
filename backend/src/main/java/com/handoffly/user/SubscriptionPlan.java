package com.handoffly.user;

import java.time.Instant;
import java.time.Period;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.Arrays;
import java.util.List;

/**
 * A customer's subscription plan, with its list price and how long one payment lasts. Every support entitlement is
 * derived from the plan alone — they are never stored or edited separately, so they can't drift from what the customer
 * pays for. This enum is the ONE place that says what each plan includes: the backend's authorization, the
 * customer app and the support portal all read it (through {@link SupportEntitlements}) rather than
 * checking for particular plans. Customers cannot change their plan: support staff do (an explicit,
 * audited action) until payments can. The list prices here are the ONE place prices live too, and
 * {@link #validUntil} is the ONE place a plan's duration is worked out.
 * The core product (handoffs, returns, PDFs) is the same on every plan; plans differ in support, and in whether HandoffCheck
 * (comparing two files) is included — a product feature, kept apart from the support entitlements.
 */
public enum SubscriptionPlan {
    //         months, validity, price, contact support, message, ticket, direct call, priority, HandoffCheck
    MONTHLY(1, Period.ofDays(30), 199, false, false, false, false, SupportPriority.NORMAL, false),
    /** Assisted: Contact Support in the app, as a simple message — no ticket workflow, no call. */
    QUARTERLY(3, Period.ofDays(90), 549, true, true, false, false, SupportPriority.NORMAL, false),
    HALF_YEARLY(6, Period.ofMonths(6), 999, true, true, true, false, SupportPriority.PRIORITY, true),
    YEARLY(12, Period.ofDays(365), 1999, true, true, true, true, SupportPriority.HIGHEST, true);

    /** The currency of every list price (ISO 4217). */
    public static final String CURRENCY = "INR";

    /**
     * The plan whose support applies once a subscription has lapsed: none of the extras, only the core product that
     * every plan includes. The customer's plan itself is left as it was, so a renewal simply picks it up again.
     */
    public static final SubscriptionPlan LAPSED_FALLBACK = MONTHLY;

    private final int months;
    private final Period validity;
    private final int amount;
    private final boolean contactSupport;
    private final boolean message;
    private final boolean tickets;
    private final boolean directCall;
    private final SupportPriority priority;
    private final boolean handoffCheck;

    SubscriptionPlan(int months, Period validity, int amount, boolean contactSupport, boolean message, boolean tickets,
                     boolean directCall, SupportPriority priority, boolean handoffCheck) {
        this.months = months;
        this.validity = validity;
        this.amount = amount;
        this.contactSupport = contactSupport;
        this.message = message;
        this.tickets = tickets;
        this.directCall = directCall;
        this.priority = priority;
        this.handoffCheck = handoffCheck;
    }

    /** The plans whose customers the support desk treats ahead of normal ones. */
    public static List<SubscriptionPlan> withElevatedPriority() {
        return Arrays.stream(values()).filter(p -> p.priority.isElevated()).toList();
    }

    /** The billing cycle one payment is priced for, in months — what the pricing pages divide the price by. */
    public int months() { return months; }

    /**
     * When a subscription on this plan that starts at {@code start} runs out: 30 days (Monthly), 90 days (Quarterly),
     * 6 calendar months (Half-yearly) or 365 days (Yearly) later. Counted in UTC like every other date here, so the same
     * start gives the same end wherever it is asked. A payment, a renewal and a support change all ask this — nothing
     * else works out a plan's duration, and nobody types an end date for a normal activation.
     */
    public Instant validUntil(Instant start) {
        return ZonedDateTime.ofInstant(start, ZoneOffset.UTC).plus(validity).toInstant();
    }

    /** The list price for one billing period, in whole {@link #CURRENCY} units. */
    public int amount() { return amount; }

    /**
     * What moving from {@code current} to this plan costs: the difference of the two list prices, so the plan the customer is
     * on counts in full as already paid. Zero or less means this plan is not an upgrade from it. The ONE place that rule lives.
     */
    public int upgradeAmountFrom(SubscriptionPlan current) { return amount - current.amount; }

    /** The authenticated "Contact Support" area on the customer dashboard. */
    public boolean includesContactSupport() { return contactSupport; }

    /** Sending support a message from inside the app. */
    public boolean includesMessages() { return message; }

    /** Creating, following and replying to support tickets. */
    public boolean includesTickets() { return tickets; }

    public boolean includesDirectCall() { return directCall; }

    public SupportPriority supportPriority() { return priority; }

    /** HandoffCheck, comparing two files: a product feature of some plans. Not a support entitlement. */
    public boolean includesHandoffCheck() { return handoffCheck; }
}

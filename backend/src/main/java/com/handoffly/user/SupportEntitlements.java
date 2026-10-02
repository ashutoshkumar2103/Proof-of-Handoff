package com.handoffly.user;

/**
 * What a plan entitles a customer to in the support area — the single projection of {@link SubscriptionPlan}
 * that the customer UI, the support portal and the public pricing page all show. The support phone number
 * is included only when direct calls are part of the plan, so a customer on a lower plan never receives it.
 */
public record SupportEntitlements(boolean contactSupport, boolean message, boolean ticket, boolean call,
                                  SupportPriority priority, String supportPhone) {

    /**
     * What an account with no plan gets so it can ask support to activate it: Contact Support and tickets, at normal
     * priority — nothing else, and no phone number. This is not a plan's entitlement and ends the moment a plan exists.
     */
    private static final SupportEntitlements ACTIVATION_HELP =
            new SupportEntitlements(true, false, true, false, SupportPriority.NORMAL, null);

    public static SupportEntitlements of(SubscriptionPlan plan, String configuredPhone) {
        boolean call = plan.includesDirectCall();
        String phone = call && configuredPhone != null && !configuredPhone.isBlank() ? configuredPhone.trim() : null;
        return new SupportEntitlements(plan.includesContactSupport(), plan.includesMessages(),
                plan.includesTickets(), call, plan.supportPriority(), phone);
    }

    /**
     * What this customer gets right now: their plan's entitlements while the subscription is active (none of the extras
     * once it lapsed, see {@link User#entitledPlan}), or the activation help of an account that has no plan yet.
     */
    public static SupportEntitlements of(User customer, String configuredPhone) {
        return customer.hasPlan() ? of(customer.entitledPlan(), configuredPhone) : ACTIVATION_HELP;
    }
}

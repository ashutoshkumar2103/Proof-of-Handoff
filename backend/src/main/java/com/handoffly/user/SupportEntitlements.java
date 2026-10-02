package com.handoffly.user;

/**
 * What a plan entitles a customer to in the support area — the single projection of {@link SubscriptionPlan}
 * that the customer UI, the support portal and the public pricing page all show. The support phone number
 * is included only when direct calls are part of the plan, so a customer on a lower plan never receives it.
 */
public record SupportEntitlements(boolean contactSupport, boolean message, boolean ticket, boolean call,
                                  SupportPriority priority, String supportPhone) {

    public static SupportEntitlements of(SubscriptionPlan plan, String configuredPhone) {
        boolean call = plan.includesDirectCall();
        String phone = call && configuredPhone != null && !configuredPhone.isBlank() ? configuredPhone.trim() : null;
        return new SupportEntitlements(plan.includesContactSupport(), plan.includesMessages(),
                plan.includesTickets(), call, plan.supportPriority(), phone);
    }
}

package com.handoffly.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The account rules that need no database: plan entitlements, handoff prefixes and per-account numbering. */
class AccountModelTest {

    private static User newCustomer() {
        return new User("CUS-000001", "alex@example.test", "hash", "Alex", null, null);
    }

    @Test
    void supportEntitlementsAreDerivedFromThePlanAlone() {
        // contact support, message, ticket, call, priority, phone — the final entitlement matrix
        assertThat(SupportEntitlements.of(SubscriptionPlan.MONTHLY, "+1 555 0100"))
                .isEqualTo(new SupportEntitlements(false, false, false, false, SupportPriority.NORMAL, null));
        assertThat(SupportEntitlements.of(SubscriptionPlan.QUARTERLY, "+1 555 0100"))
                .isEqualTo(new SupportEntitlements(true, true, false, false, SupportPriority.NORMAL, null));
        assertThat(SupportEntitlements.of(SubscriptionPlan.HALF_YEARLY, "+1 555 0100"))
                .isEqualTo(new SupportEntitlements(true, true, true, false, SupportPriority.PRIORITY, null));
        assertThat(SupportEntitlements.of(SubscriptionPlan.YEARLY, "+1 555 0100"))
                .isEqualTo(new SupportEntitlements(true, true, true, true, SupportPriority.HIGHEST, "+1 555 0100"));
    }

    @Test
    void thePlansWithElevatedSupportPriorityAreHalfYearlyAndYearly() {
        assertThat(SubscriptionPlan.withElevatedPriority())
                .containsExactly(SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY);
    }

    @Test
    void thereAreExactlyFourPlans() {
        assertThat(SubscriptionPlan.values()).containsExactly(SubscriptionPlan.MONTHLY, SubscriptionPlan.QUARTERLY,
                SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY);
    }

    @Test
    void everyPlanHasItsListPriceAndBillingPeriod() {
        assertThat(SubscriptionPlan.MONTHLY.months()).isEqualTo(1);
        assertThat(SubscriptionPlan.MONTHLY.amount()).isEqualTo(199);
        assertThat(SubscriptionPlan.QUARTERLY.months()).isEqualTo(3);
        assertThat(SubscriptionPlan.QUARTERLY.amount()).isEqualTo(549);
        assertThat(SubscriptionPlan.HALF_YEARLY.months()).isEqualTo(6);
        assertThat(SubscriptionPlan.HALF_YEARLY.amount()).isEqualTo(999);
        assertThat(SubscriptionPlan.YEARLY.months()).isEqualTo(12);
        assertThat(SubscriptionPlan.YEARLY.amount()).isEqualTo(1999);
        assertThat(SubscriptionPlan.CURRENCY).isEqualTo("INR");
    }

    @Test
    void theSupportPhoneIsOnlyOfferedWhenOneIsConfigured() {
        assertThat(SupportEntitlements.of(SubscriptionPlan.YEARLY, null).supportPhone()).isNull();
        assertThat(SupportEntitlements.of(SubscriptionPlan.YEARLY, "").supportPhone()).isNull();
        assertThat(SupportEntitlements.of(SubscriptionPlan.YEARLY, "   ").supportPhone()).isNull();
        // The call option itself still follows the plan; the UI just has no number to show.
        assertThat(SupportEntitlements.of(SubscriptionPlan.YEARLY, null).call()).isTrue();
    }

    @Test
    void newAccountsHaveNoPlanAndTheDefaultPrefix() {
        User user = newCustomer();
        assertThat(user.hasPlan()).isFalse();
        assertThat(user.getSubscriptionPlan()).isNull();
        assertThat(user.getPlanStartedAt()).isNull();
        assertThat(user.getPlanValidUntil()).isNull();
        assertThat(user.getHandoffPrefix()).isEqualTo("HO");
        assertThat(user.getHandoffSequence()).isZero();
        assertThat(user.isEnabled()).isTrue();
    }

    @Test
    void anAccountWithNoPlanHasNothingActiveAndOnlyTheHelpToGetActivated() {
        User user = newCustomer();
        Instant now = Instant.parse("2026-10-02T10:00:00Z");
        assertThat(user.subscriptionStatus(now)).isEqualTo(SubscriptionStatus.INACTIVE);
        assertThat(user.entitledPlan(now)).isEqualTo(SubscriptionPlan.LAPSED_FALLBACK);   // none of the extras of any plan
        // Contact Support and tickets to ask for the activation: no message form, no call, no priority, no phone number.
        assertThat(SupportEntitlements.of(user, "+1 555 0100"))
                .isEqualTo(new SupportEntitlements(true, false, true, false, SupportPriority.NORMAL, null));
        assertThat(SubscriptionSummary.of(user, now))
                .isEqualTo(new SubscriptionSummary(null, SubscriptionStatus.INACTIVE, null, null));
    }

    @Test
    void oncePutOnAPlanAnAccountIsActiveAndItsSupportFollowsThePlan() {
        User user = newCustomer();
        Instant now = Instant.now();
        user.startPlan(SubscriptionPlan.QUARTERLY, now, SubscriptionPlan.QUARTERLY.validUntil(now));
        assertThat(user.hasPlan()).isTrue();
        assertThat(user.subscriptionStatus(now)).isEqualTo(SubscriptionStatus.ACTIVE);
        assertThat(SupportEntitlements.of(user, null)).isEqualTo(SupportEntitlements.of(SubscriptionPlan.QUARTERLY, null));

        // When a plan runs out it is a lapsed plan, not 'no plan': the account keeps it, and gets no extras.
        User lapsed = newCustomer();
        Instant longAgo = Instant.parse("2020-01-01T00:00:00Z");
        lapsed.startPlan(SubscriptionPlan.QUARTERLY, longAgo, SubscriptionPlan.QUARTERLY.validUntil(longAgo));
        assertThat(lapsed.subscriptionStatus(Instant.now())).isEqualTo(SubscriptionStatus.INACTIVE);
        assertThat(lapsed.hasPlan()).isTrue();
        assertThat(SupportEntitlements.of(lapsed, null)).isEqualTo(SupportEntitlements.of(SubscriptionPlan.LAPSED_FALLBACK, null));
    }

    @Test
    void everyPlanLastsItsOwnDurationFromWhereItStarts() {
        Instant start = Instant.parse("2026-10-02T10:00:00Z");
        assertThat(SubscriptionPlan.MONTHLY.validUntil(start)).isEqualTo(Instant.parse("2026-11-01T10:00:00Z"));       // 30 days
        assertThat(SubscriptionPlan.QUARTERLY.validUntil(start)).isEqualTo(Instant.parse("2026-12-31T10:00:00Z"));     // 90 days
        assertThat(SubscriptionPlan.HALF_YEARLY.validUntil(start)).isEqualTo(Instant.parse("2027-04-02T10:00:00Z"));   // 6 calendar months
        assertThat(SubscriptionPlan.YEARLY.validUntil(start)).isEqualTo(Instant.parse("2027-10-02T10:00:00Z"));        // 365 days
    }

    @Test
    void planDurationsDoNotDriftAtMonthEndsOrAcrossALeapDay() {
        // Half-yearly counts calendar months and stops at the end of a shorter month rather than spilling over.
        assertThat(SubscriptionPlan.HALF_YEARLY.validUntil(Instant.parse("2026-08-31T00:00:00Z")))
                .isEqualTo(Instant.parse("2027-02-28T00:00:00Z"));
        // Yearly is 365 days, not a calendar year: across a leap day it ends a day earlier on the calendar.
        assertThat(SubscriptionPlan.YEARLY.validUntil(Instant.parse("2027-03-01T00:00:00Z")))
                .isEqualTo(Instant.parse("2028-02-29T00:00:00Z"));
        // Monthly is 30 days even from the 31st; the time of day is kept.
        assertThat(SubscriptionPlan.MONTHLY.validUntil(Instant.parse("2026-12-31T23:59:59Z")))
                .isEqualTo(Instant.parse("2027-01-30T23:59:59Z"));
    }

    @ParameterizedTest
    @ValueSource(strings = {"AV", "HO", "ABC", "ABCD", "ABCDE"})
    void upperCaseLettersTwoToFiveLongAreValidPrefixes(String prefix) {
        User user = newCustomer();
        user.changeHandoffPrefix(prefix);
        assertThat(user.getHandoffPrefix()).isEqualTo(prefix);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "A", "ABCDEF", "av", "Av", "A1", "12", "A-B", "A_B", "A B", " AV", "AV ", "AV\n", "ÄÖ"})
    void anythingElseIsRejectedAndLeavesThePrefixAlone(String prefix) {
        User user = newCustomer();
        assertThatThrownBy(() -> user.changeHandoffPrefix(prefix)).isInstanceOf(IllegalArgumentException.class);
        assertThat(user.getHandoffPrefix()).isEqualTo("HO");
    }

    @Test
    void aMissingPrefixIsRejected() {
        assertThatThrownBy(() -> newCustomer().changeHandoffPrefix(null)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void referencesCountUpPerAccountAndNeverRestartWhenThePrefixChanges() {
        User user = newCustomer();
        assertThat(user.nextHandoffReference()).isEqualTo("HO-1");
        assertThat(user.nextHandoffReference()).isEqualTo("HO-2");

        user.changeHandoffPrefix("AV");
        assertThat(user.peekNextHandoffReference()).isEqualTo("AV-3");
        assertThat(user.peekNextHandoffReference()).isEqualTo("AV-3");   // looking consumes nothing
        assertThat(user.nextHandoffReference()).isEqualTo("AV-3");

        user.changeHandoffPrefix("HO");
        assertThat(user.nextHandoffReference()).isEqualTo("HO-4");       // HO-1 and HO-2 are never issued twice
    }

    @Test
    void twoAccountsKeepIndependentCounters() {
        User first = newCustomer();
        User second = new User("CUS-000002", "sam@example.test", "hash", "Sam", null, null);
        first.nextHandoffReference();
        first.nextHandoffReference();
        assertThat(second.nextHandoffReference()).isEqualTo("HO-1");
        assertThat(first.nextHandoffReference()).isEqualTo("HO-3");
    }
}

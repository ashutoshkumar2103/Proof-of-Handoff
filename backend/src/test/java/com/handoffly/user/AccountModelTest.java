package com.handoffly.user;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

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
    void newAccountsAreOnTheMonthlyPlanWithTheDefaultPrefix() {
        User user = newCustomer();
        assertThat(user.getSubscriptionPlan()).isEqualTo(SubscriptionPlan.MONTHLY);
        assertThat(user.getHandoffPrefix()).isEqualTo("HO");
        assertThat(user.getHandoffSequence()).isZero();
        assertThat(user.isEnabled()).isTrue();
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

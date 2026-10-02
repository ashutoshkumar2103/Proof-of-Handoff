package com.handoffly.payment;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.User;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.ResultActions;

import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Choosing a plan on the pricing page leads to a payment (demo: no money), and the plan is applied to the account
 * the customer signs up with or signs in to. The backend alone decides the plan, from a payment it recorded.
 */
class PaymentTest extends ApiTestBase {

    @Autowired
    private PlatformTransactionManager transactions;

    private static final String PAY = "/api/v1/public/payments/demo";
    private static final String REDEEM = "/api/v1/payments/redeem";

    private static final String APPROVED_CARD = "4242 4242 4242 4242";
    private static final String DECLINED_CARD = "4000 0000 0000 0002";

    private static String body(String plan, String card, String expiry, String cvc) {
        return "{\"plan\":\"" + plan + "\",\"cardNumber\":\"" + card + "\",\"expiry\":\"" + expiry + "\",\"cvc\":\"" + cvc + "\"}";
    }

    /** A payment with the demo card that is always approved. */
    private static String body(String plan) {
        return body(plan, APPROVED_CARD, "12/99", "123");
    }

    private ResultActions pay(String json) throws Exception {
        return mvc.perform(post(PAY).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private long paymentCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM payment", Long.class);
    }

    /** Pays for a plan with no account (as the pricing page does) and returns the one-time token. */
    private String paid(SubscriptionPlan plan) throws Exception {
        String json = pay(body(plan.name())).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return JsonPath.read(json, "$.token");
    }

    private ResultActions redeem(Bearer who, String token) throws Exception {
        return mvc.perform(as(who, post(REDEEM).contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}")));
    }

    private static final String UPGRADES = "/api/v1/payments/upgrades";
    private static final String UPGRADE = "/api/v1/payments/upgrade";
    private static final String COMPARE =
            "{\"referenceLines\":[{\"name\":\"A\",\"quantity\":1}],\"targetLines\":[{\"name\":\"A\",\"quantity\":1}]}";

    private ResultActions upgradeOptions(Bearer who) throws Exception {
        return mvc.perform(as(who, get(UPGRADES)));
    }

    /** Pays the difference to move up to {@code plan}, saying what the customer was shown, with the approved demo card. */
    private ResultActions upgrade(Bearer who, SubscriptionPlan plan, int expectedAmount) throws Exception {
        return upgrade(who, plan.name(), expectedAmount, APPROVED_CARD, "12/99", "123");
    }

    private ResultActions upgrade(Bearer who, String plan, int expectedAmount, String card, String expiry, String cvc) throws Exception {
        return mvc.perform(as(who, post(UPGRADE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAmount\":" + expectedAmount + ",\"payment\":" + body(plan, card, expiry, cvc) + "}")));
    }

    private void lapse(Account customer) {
        jdbc.update("UPDATE app_user SET plan_valid_until = TIMESTAMPADD(DAY, -1, CURRENT_TIMESTAMP) WHERE id = ?", customer.id());
    }

    private void assertPlan(Account customer, SubscriptionPlan plan) throws Exception {
        mvc.perform(as(customer, get("/api/v1/auth/me"))).andExpect(jsonPath("$.plan").value(plan.name()));
    }

    // ------------------------------------------------------------------ paying

    @Test
    void payingNeedsNoAccountAndTheReceiptCarriesTheListPrice() throws Exception {
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            pay(body(plan.name()))
                    .andExpect(status().isCreated())
                    .andExpect(jsonPath("$.plan").value(plan.name()))
                    .andExpect(jsonPath("$.amount").value(plan.amount()))
                    .andExpect(jsonPath("$.currency").value(SubscriptionPlan.CURRENCY))
                    .andExpect(jsonPath("$.token").isNotEmpty())
                    .andExpect(jsonPath("$.expiresAt").isNotEmpty());
        }
    }

    @Test
    void thePriceIsNeverTakenFromTheRequest() throws Exception {
        pay(body("YEARLY").replace("}", ",\"amount\":1,\"currency\":\"USD\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amount").value(SubscriptionPlan.YEARLY.amount()))
                .andExpect(jsonPath("$.currency").value("INR"));
    }

    @Test
    void anUnknownMissingOrMalformedPlanIsRefusedAndRecordsNothing() throws Exception {
        long before = paymentCount();
        pay(body("PLATINUM")).andExpect(status().isBadRequest());
        pay("{}").andExpect(status().isBadRequest());
        pay("not json").andExpect(status().isBadRequest());
        assertThat(paymentCount()).isEqualTo(before);
    }

    // ------------------------------------------------------------------ the demo card

    @Test
    void theApprovedTestCardPaysWhateverWayItIsTypedAndTheDeclinedOneDoesNot() throws Exception {
        for (String typed : new String[]{"4242424242424242", "4242 4242 4242 4242", "4242-4242-4242-4242"}) {
            pay(body("YEARLY", typed, "12/99", "123")).andExpect(status().isCreated());
        }
        long before = paymentCount();
        pay(body("YEARLY", DECLINED_CARD, "12/99", "123")).andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("card_declined"))
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("declined")));
        assertThat(paymentCount()).isEqualTo(before);   // a declined card buys nothing and leaves no payment
    }

    @Test
    void anyOtherCardIsRefusedWithoutEchoingItOrRecordingAnything() throws Exception {
        long before = paymentCount();
        for (String realLooking : new String[]{"4111 1111 1111 1111", "5500 0000 0000 0004", "1234", "abcd"}) {
            String response = pay(body("YEARLY", realLooking, "12/99", "123")).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("demo")))
                    .andReturn().getResponse().getContentAsString();
            assertThat(response).doesNotContain(realLooking.replace(" ", ""));
        }
        assertThat(paymentCount()).isEqualTo(before);
    }

    @Test
    void theExpiryAndSecurityCodeMustBeValid() throws Exception {
        long before = paymentCount();
        for (String expiry : new String[]{"13/99", "00/99", "1299", "12/2099", "ab/cd", "01/20"}) {
            pay(body("YEARLY", APPROVED_CARD, expiry, "123")).andExpect(status().isBadRequest());
        }
        for (String cvc : new String[]{"12", "12345", "abc", "1 2 3"}) {
            pay(body("YEARLY", APPROVED_CARD, "12/99", cvc)).andExpect(status().isBadRequest());
        }
        assertThat(paymentCount()).isEqualTo(before);
        // The current month is still good, a spaced MM / YY is understood.
        java.time.YearMonth now = java.time.YearMonth.now(java.time.ZoneOffset.UTC);
        pay(body("YEARLY", APPROVED_CARD, String.format("%02d / %02d", now.getMonthValue(), now.getYear() % 100), "1234"))
                .andExpect(status().isCreated());
    }

    @Test
    void missingCardDetailsAreRefused() throws Exception {
        long before = paymentCount();
        pay("{\"plan\":\"YEARLY\"}").andExpect(status().isBadRequest());
        pay("{\"plan\":\"YEARLY\",\"cardNumber\":\"" + APPROVED_CARD + "\"}").andExpect(status().isBadRequest());
        pay(body("YEARLY", "", "12/99", "123")).andExpect(status().isBadRequest());
        pay(body("YEARLY", "4".repeat(33), "12/99", "123")).andExpect(status().isBadRequest());
        assertThat(paymentCount()).isEqualTo(before);
    }

    @Test
    void onlyAHashOfTheTokenIsStored() throws Exception {
        String token = paid(SubscriptionPlan.QUARTERLY);
        List<String> stored = jdbc.queryForList("SELECT token_hash FROM payment", String.class);
        assertThat(stored).doesNotContain(token).allMatch(h -> h.length() == 64);
    }

    // ------------------------------------------------------------------ applying the plan

    @Test
    void aNewCustomerWhoPaidGetsThePlanAndItsSupportFeaturesAtOnce() throws Exception {
        String token = paid(SubscriptionPlan.QUARTERLY);
        Account customer = register();   // signs up afterwards, on the default plan
        assertPlan(customer, SubscriptionPlan.MONTHLY);

        redeem(customer, token).andExpect(status().isOk())
                .andExpect(jsonPath("$.plan").value("QUARTERLY"))
                .andExpect(jsonPath("$.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.support.contactSupport").value(true))
                .andExpect(jsonPath("$.support.message").value(true))
                .andExpect(jsonPath("$.support.ticket").value(false))
                .andExpect(jsonPath("$.support.call").value(false));
        assertPlan(customer, SubscriptionPlan.QUARTERLY);
    }

    @Test
    void everyPlanCanBeBoughtAndAppliedAndYearlyBringsTheCall() throws Exception {
        for (SubscriptionPlan plan : SubscriptionPlan.values()) {
            Account customer = register();
            redeem(customer, paid(plan)).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value(plan.name()));
            assertPlan(customer, plan);
        }
        Account yearly = register();
        redeem(yearly, paid(SubscriptionPlan.YEARLY)).andExpect(jsonPath("$.support.call").value(true))
                .andExpect(jsonPath("$.support.supportPhone").value(SUPPORT_PHONE));
    }

    @Test
    void anExistingCustomerWhoPaysForAnotherPlanMovesToIt() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        redeem(customer, paid(SubscriptionPlan.YEARLY)).andExpect(status().isOk());
        assertPlan(customer, SubscriptionPlan.YEARLY);
    }

    @Test
    void applyingRecordsWhoGotWhatAndWhichPlanItReplaced() throws Exception {
        String token = paid(SubscriptionPlan.HALF_YEARLY);
        Account customer = register(SubscriptionPlan.QUARTERLY);
        redeem(customer, token).andExpect(status().isOk());

        var row = jdbc.queryForMap("SELECT plan, plan_before, redeemed_by_user_id, amount FROM payment WHERE redeemed_by_user_id = ?",
                customer.id());
        assertThat(row.get("plan")).isEqualTo("HALF_YEARLY");
        assertThat(row.get("plan_before")).isEqualTo("QUARTERLY");
        assertThat(((Number) row.get("amount")).intValue()).isEqualTo(SubscriptionPlan.HALF_YEARLY.amount());
    }

    // ------------------------------------------------------------------ refused

    @Test
    void aPaymentCanBeAppliedOnlyOnce() throws Exception {
        String token = paid(SubscriptionPlan.YEARLY);
        Account first = register();
        Account second = register();

        redeem(first, token).andExpect(status().isOk());
        redeem(first, token).andExpect(status().isBadRequest());     // not twice by the same account...
        redeem(second, token).andExpect(status().isBadRequest());    // ...and never by another one
        assertPlan(first, SubscriptionPlan.YEARLY);
        assertPlan(second, SubscriptionPlan.MONTHLY);
    }

    @Test
    void anUnknownMalformedOrBlankTokenChangesNothing() throws Exception {
        Account customer = register();
        redeem(customer, "no-such-token").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("unknown, expired or already used")));
        redeem(customer, "").andExpect(status().isBadRequest());
        redeem(customer, "x".repeat(201)).andExpect(status().isBadRequest());
        mvc.perform(as(customer, post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{}"))).andExpect(status().isBadRequest());
        assertPlan(customer, SubscriptionPlan.MONTHLY);
    }

    @Test
    void anExpiredPaymentCannotBeApplied() throws Exception {
        String token = paid(SubscriptionPlan.YEARLY);
        jdbc.update("UPDATE payment SET expires_at = TIMESTAMPADD(HOUR, -1, CURRENT_TIMESTAMP) WHERE redeemed_at IS NULL");
        Account customer = register();
        redeem(customer, token).andExpect(status().isBadRequest());
        assertPlan(customer, SubscriptionPlan.MONTHLY);
    }

    @Test
    void applyingNeedsASignedInCustomerNotAnAnonymousCallerOrStaff() throws Exception {
        String token = paid(SubscriptionPlan.YEARLY);
        mvc.perform(post(REDEEM).contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\"}"))
                .andExpect(status().isUnauthorized());
        StaffAccount staff = registerStaff(com.handoffly.support.staff.SupportRole.ADMIN);
        redeem(staff, token).andExpect(status().isUnauthorized());
        // The token is still good for a real customer afterwards.
        Account customer = register();
        redeem(customer, token).andExpect(status().isOk());
    }

    @Test
    void twoRequestsRacingWithTheSameTokenApplyItExactlyOnce() throws Exception {
        String token = paid(SubscriptionPlan.YEARLY);
        Account first = register();
        Account second = register();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch go = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = List.of(pool.submit(() -> {
                go.await();
                return redeem(first, token).andReturn().getResponse().getStatus();
            }), pool.submit(() -> {
                go.await();
                return redeem(second, token).andReturn().getResponse().getStatus();
            }));
            go.countDown();
            List<Integer> statuses = List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(statuses).containsExactlyInAnyOrder(200, 400);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE token_hash = ? AND redeemed_at IS NOT NULL",
                Long.class, com.handoffly.common.util.SecureTokens.sha256Hex(token))).isEqualTo(1L);
    }

    @Test
    void aCustomerCannotGiveThemselvesAPlanWithoutPaying() throws Exception {
        Account customer = register();
        // There is no way to choose a plan on registration, in a profile update or by presenting a made-up payment.
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"sneaky@example.test\",\"password\":\"password123\",\"displayName\":\"Sneaky\","
                                + "\"plan\":\"YEARLY\",\"subscriptionPlan\":\"YEARLY\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.plan").isEmpty())
                .andExpect(jsonPath("$.user.subscription.status").value("INACTIVE"));
        redeem(customer, "YEARLY").andExpect(status().isBadRequest());
        assertPlan(customer, SubscriptionPlan.MONTHLY);

        // An account with no plan at all gains nothing from a made-up token either.
        Account unpaid = registerWithoutPlan();
        redeem(unpaid, "YEARLY").andExpect(status().isBadRequest());
        mvc.perform(as(unpaid, get("/api/v1/auth/me"))).andExpect(jsonPath("$.plan").isEmpty())
                .andExpect(jsonPath("$.subscription.status").value("INACTIVE"));
    }

    // ------------------------------------------------------------------ upgrading by paying the difference

    @Test
    void theUpgradeOptionsAreThePlansThatCostMoreAtTheDifferenceOfListPrices() throws Exception {
        // The plan a customer is on counts in full as already paid: Quarterly 549 -> Half-yearly 999 leaves 450 to pay.
        upgradeOptions(register(SubscriptionPlan.QUARTERLY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].from").value("QUARTERLY"))   // the credit is for the plan they are on
                .andExpect(jsonPath("$[0].price.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$[0].price.amount").value(SubscriptionPlan.HALF_YEARLY.amount()))
                .andExpect(jsonPath("$[0].price.currency").value("INR"))
                .andExpect(jsonPath("$[0].credit").value(SubscriptionPlan.QUARTERLY.amount()))
                .andExpect(jsonPath("$[0].amountDue").value(SubscriptionPlan.HALF_YEARLY.amount() - SubscriptionPlan.QUARTERLY.amount()))
                .andExpect(jsonPath("$[0].handoffCheck").value(true))
                .andExpect(jsonPath("$[1].price.plan").value("YEARLY"))
                .andExpect(jsonPath("$[1].amountDue").value(SubscriptionPlan.YEARLY.amount() - SubscriptionPlan.QUARTERLY.amount()))
                .andExpect(jsonPath("$[1].handoffCheck").value(true));
        assertThat(SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY)).isEqualTo(450);   // 999 - 549

        // From Monthly every dearer plan is offered, and the response says which of them include HandoffCheck.
        upgradeOptions(register(SubscriptionPlan.MONTHLY)).andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].price.plan").value("QUARTERLY")).andExpect(jsonPath("$[0].handoffCheck").value(false))
                .andExpect(jsonPath("$[1].price.plan").value("HALF_YEARLY")).andExpect(jsonPath("$[1].handoffCheck").value(true))
                .andExpect(jsonPath("$[2].price.plan").value("YEARLY")).andExpect(jsonPath("$[2].handoffCheck").value(true));
    }

    @Test
    void thereAreNoUpgradeOptionsFromTheDearestPlanOrWithoutAnActivePlan() throws Exception {
        upgradeOptions(register(SubscriptionPlan.YEARLY)).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        upgradeOptions(registerWithoutPlan()).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        Account lapsed = register(SubscriptionPlan.QUARTERLY);
        lapse(lapsed);   // a plan that ran out is bought again, not upgraded
        upgradeOptions(lapsed).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void payingTheDifferenceMovesToTheDearerPlanAtOnceAndUnlocksHandoffCheck() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_required"));
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);

        Instant before = Instant.now();
        upgrade(customer, SubscriptionPlan.HALF_YEARLY, due).andExpect(status().isOk())
                .andExpect(jsonPath("$.accountCode").value(customer.accountCode()))
                .andExpect(jsonPath("$.plan").value("HALF_YEARLY"))
                .andExpect(jsonPath("$.subscription.status").value("ACTIVE"))
                .andExpect(jsonPath("$.handoffCheck").value(true))
                .andExpect(jsonPath("$.support.ticket").value(true));

        // The ledger: what was really charged, for which plan, replacing which.
        var row = jdbc.queryForMap("SELECT plan, amount, plan_before FROM payment WHERE redeemed_by_user_id = ?", customer.id());
        assertThat(row.get("plan")).isEqualTo("HALF_YEARLY");
        assertThat(((Number) row.get("amount")).intValue()).isEqualTo(due);
        assertThat(row.get("plan_before")).isEqualTo("QUARTERLY");
        // The new plan starts now and lasts its own duration; the old one is replaced, not stacked on.
        User saved = users.findById(customer.id()).orElseThrow();
        assertThat(saved.getPlanStartedAt()).isBetween(before.minusSeconds(1), Instant.now().plusSeconds(1));
        assertThat(saved.getPlanValidUntil()).isEqualTo(SubscriptionPlan.HALF_YEARLY.validUntil(saved.getPlanStartedAt()));
        var history = jdbc.queryForMap("SELECT source, previous_plan, new_plan FROM subscription_history WHERE user_id = ?", customer.id());
        assertThat(history.values()).containsExactly("PAYMENT", "QUARTERLY", "HALF_YEARLY");

        // The tool that was locked works now, with no sign-in again, and only the dearest plan is left to move up to.
        mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(COMPARE)))
                .andExpect(status().isOk());
        upgradeOptions(customer).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].price.plan").value("YEARLY"));
    }

    @Test
    void everyDearerPlanIsReachedByPayingExactlyTheDifference() throws Exception {
        for (SubscriptionPlan from : SubscriptionPlan.values()) {
            for (SubscriptionPlan to : SubscriptionPlan.values()) {
                if (to.amount() <= from.amount()) continue;
                Account customer = register(from);
                int due = to.amount() - from.amount();
                upgrade(customer, to, due).andExpect(status().isOk()).andExpect(jsonPath("$.plan").value(to.name()));
                assertThat(jdbc.queryForObject("SELECT amount FROM payment WHERE redeemed_by_user_id = ?", Integer.class, customer.id()))
                        .isEqualTo(due);
            }
        }
    }

    @Test
    void anUpgradeThatIsNotOneOrWhoseAmountIsNotWhatWasShownChargesNothingAndChangesNothing() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        long payments = paymentCount();
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);

        upgrade(customer, SubscriptionPlan.QUARTERLY, due).andExpect(status().isConflict());   // the plan they are on
        upgrade(customer, SubscriptionPlan.MONTHLY, due).andExpect(status().isConflict());     // a cheaper plan
        upgrade(customer, SubscriptionPlan.HALF_YEARLY, due + 1).andExpect(status().isConflict())   // not what they were shown
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString(String.valueOf(due))));
        upgrade(customer, SubscriptionPlan.HALF_YEARLY, due - 1).andExpect(status().isConflict());

        // A request that does not say what was shown is malformed, not a free upgrade.
        for (String json : new String[]{"{\"payment\":" + body("HALF_YEARLY") + "}",
                "{\"expectedAmount\":0,\"payment\":" + body("HALF_YEARLY") + "}",
                "{\"expectedAmount\":" + due + "}", "{\"expectedAmount\":" + due + ",\"payment\":{}}", "not json"}) {
            mvc.perform(as(customer, post(UPGRADE).contentType(MediaType.APPLICATION_JSON).content(json))).andExpect(status().isBadRequest());
        }
        assertThat(paymentCount()).isEqualTo(payments);
        assertPlan(customer, SubscriptionPlan.QUARTERLY);
    }

    @Test
    void onlyAnActivePlanCanBeUpgraded() throws Exception {
        long payments = paymentCount();
        Account none = registerWithoutPlan();
        upgrade(none, SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.HALF_YEARLY.amount()).andExpect(status().isConflict());
        mvc.perform(as(none, get("/api/v1/auth/me"))).andExpect(jsonPath("$.plan").isEmpty());

        Account lapsed = register(SubscriptionPlan.QUARTERLY);
        lapse(lapsed);
        upgrade(lapsed, SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY))
                .andExpect(status().isConflict());
        assertPlan(lapsed, SubscriptionPlan.QUARTERLY);

        Account dearest = register(SubscriptionPlan.YEARLY);
        upgrade(dearest, SubscriptionPlan.YEARLY, 1).andExpect(status().isConflict());
        assertThat(paymentCount()).isEqualTo(payments);
    }

    @Test
    void anUpgradeWithACardThatDoesNotPayChargesNothingAndChangesNothing() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        long payments = paymentCount();
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);

        upgrade(customer, "HALF_YEARLY", due, DECLINED_CARD, "12/99", "123").andExpect(status().isPaymentRequired())
                .andExpect(jsonPath("$.code").value("card_declined"));
        String response = upgrade(customer, "HALF_YEARLY", due, "4111 1111 1111 1111", "12/99", "123").andExpect(status().isBadRequest())
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("4111");   // a real-looking card is refused without being echoed
        upgrade(customer, "HALF_YEARLY", due, APPROVED_CARD, "01/20", "123").andExpect(status().isBadRequest());
        upgrade(customer, "HALF_YEARLY", due, APPROVED_CARD, "12/99", "12").andExpect(status().isBadRequest());

        assertThat(paymentCount()).isEqualTo(payments);
        assertPlan(customer, SubscriptionPlan.QUARTERLY);
    }

    @Test
    void theClientCannotChooseWhatAnUpgradeCosts() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);
        mvc.perform(as(customer, post(UPGRADE).contentType(MediaType.APPLICATION_JSON).content(
                        "{\"expectedAmount\":" + due + ",\"amount\":1,\"currency\":\"USD\",\"payment\":"
                                + body("HALF_YEARLY").replace("}", ",\"amount\":1,\"currency\":\"USD\"}") + "}")))
                .andExpect(status().isOk());
        var row = jdbc.queryForMap("SELECT amount, currency FROM payment WHERE redeemed_by_user_id = ?", customer.id());
        assertThat(((Number) row.get("amount")).intValue()).isEqualTo(due);
        assertThat(row.get("currency")).isEqualTo("INR");
    }

    @Test
    void upgradingNeedsASignedInCustomer() throws Exception {
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);
        mvc.perform(get(UPGRADES)).andExpect(status().isUnauthorized());
        mvc.perform(post(UPGRADE).contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedAmount\":" + due + ",\"payment\":" + body("HALF_YEARLY") + "}")).andExpect(status().isUnauthorized());
        StaffAccount staff = registerStaff(com.handoffly.support.staff.SupportRole.ADMIN);
        upgradeOptions(staff).andExpect(status().isUnauthorized());
        upgrade(staff, SubscriptionPlan.HALF_YEARLY, due).andExpect(status().isUnauthorized());
    }

    @Test
    void twoUpgradesInFlightTogetherChargeOnlyOnce() throws Exception {
        Account customer = register(SubscriptionPlan.QUARTERLY);
        int due = SubscriptionPlan.HALF_YEARLY.upgradeAmountFrom(SubscriptionPlan.QUARTERLY);
        ExecutorService pool = Executors.newFixedThreadPool(3);
        CountDownLatch locked = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        try {
            // Hold the customer's row, as a request in the middle of changing their plan would, so that both upgrades below
            // really are in flight together instead of one finishing before the other starts.
            Future<?> holder = pool.submit(() -> new TransactionTemplate(transactions).executeWithoutResult(status -> {
                users.findByIdForUpdate(customer.id());
                locked.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(locked.await(10, TimeUnit.SECONDS)).isTrue();
            List<Future<MockHttpServletResponse>> results = List.of(
                    pool.submit(() -> upgrade(customer, SubscriptionPlan.HALF_YEARLY, due).andReturn().getResponse()),
                    pool.submit(() -> upgrade(customer, SubscriptionPlan.HALF_YEARLY, due).andReturn().getResponse()));
            Thread.sleep(400);   // both are now waiting for the customer's row
            release.countDown();
            holder.get(10, TimeUnit.SECONDS);
            List<MockHttpServletResponse> responses = List.of(results.get(0).get(20, TimeUnit.SECONDS), results.get(1).get(20, TimeUnit.SECONDS));
            assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
            // The second finds nothing left to upgrade and is told so plainly (the row lock), not with a generic lost-update error.
            MockHttpServletResponse second = responses.stream().filter(r -> r.getStatus() == 409).findFirst().orElseThrow();
            assertThat(second.getContentAsString()).contains("not an upgrade");
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE redeemed_by_user_id = ?", Long.class, customer.id())).isEqualTo(1L);
        assertPlan(customer, SubscriptionPlan.HALF_YEARLY);
    }
}

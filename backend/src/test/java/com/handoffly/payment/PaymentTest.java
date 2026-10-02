package com.handoffly.payment;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

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
                .andExpect(status().isCreated()).andExpect(jsonPath("$.user.plan").value("MONTHLY"));
        redeem(customer, "YEARLY").andExpect(status().isBadRequest());
        assertPlan(customer, SubscriptionPlan.MONTHLY);
    }
}

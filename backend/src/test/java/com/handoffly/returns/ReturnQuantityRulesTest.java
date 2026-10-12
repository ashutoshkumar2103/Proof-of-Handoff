package com.handoffly.returns;

import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * The rules the Record a Return form shows live as "Still with recipient" (Remaining − Return qty − Missing): what is returned and what is
 * marked missing are the customer's two inputs, and what is neither stays with the recipient — it is never marked missing by itself. The
 * form calls the same API, so these are the figures it must agree with.
 */
class ReturnQuantityRulesTest extends ApiTestBase {

    /** A handoff of 6 Chairs, sent and accepted, so that Remaining is 6. Returns the handoff id and the item id. */
    private long[] handoffOfSix(Account owner) throws Exception {
        String created = mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"title\":\"Six chairs\",\"senderName\":\"Sender\",\"recipientName\":\"Recipient\","
                                + "\"recipientEmail\":\"recipient@example.test\",\"items\":[{\"name\":\"Chairs\",\"quantity\":6,\"unit\":\"pcs\"}]}")))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        long id = ((Number) JsonPath.read(created, "$.id")).longValue();
        mvc.perform(as(owner, post("/api/v1/handoffs/" + id + "/submit"))).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Recipient\"}")).andExpect(status().isOk());
        long item = ((Number) JsonPath.read(mvc.perform(as(owner, get("/api/v1/handoffs/" + id))).andReturn().getResponse().getContentAsString(),
                "$.items[0].id")).longValue();
        return new long[]{id, item};
    }

    private static String line(long item, String quantity, String condition) {
        return "{\"itemId\":" + item + ",\"quantity\":" + quantity + ",\"condition\":\"" + condition + "\"}";
    }

    private ResultActions record(Account owner, long handoff, String... lines) throws Exception {
        return mvc.perform(as(owner, post("/api/v1/handoffs/" + handoff + "/returns").contentType(MediaType.APPLICATION_JSON)
                .content("{\"lines\":[" + String.join(",", lines) + "]}")));
    }

    private void expectItem(Account owner, long handoff, double returned, double missing, double remaining) throws Exception {
        mvc.perform(as(owner, get("/api/v1/handoffs/" + handoff))).andExpect(status().isOk())
                .andExpect(jsonPath("$.items[0].returnedConfirmed").value(returned))
                .andExpect(jsonPath("$.items[0].missing").value(missing))
                .andExpect(jsonPath("$.items[0].remaining").value(remaining));
    }

    @Test
    void returningAllOfRemainingLeavesNothingWithTheRecipient() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);
        record(owner, h[0], line(h[1], "6", "GOOD")).andExpect(status().isCreated());
        expectItem(owner, h[0], 6, 0, 0);   // Remaining 6, return 6, missing 0: still with recipient 0
    }

    @Test
    void aPartialReturnLeavesTheRestWithTheRecipientAndNeverMarksItMissing() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);
        record(owner, h[0], line(h[1], "4", "GOOD")).andExpect(status().isCreated());
        expectItem(owner, h[0], 4, 0, 2);   // Remaining 6, return 4, missing 0: still with recipient 2 — and none of it missing
    }

    @Test
    void returnedPlusMissingThatAddUpToRemainingLeaveNothingWithTheRecipient() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);
        record(owner, h[0], line(h[1], "4", "GOOD"), line(h[1], "2", "MISSING")).andExpect(status().isCreated());
        expectItem(owner, h[0], 4, 2, 0);   // Remaining 6, return 4, missing 2: still with recipient 0
    }

    @Test
    void recordingNothingBackLeavesAllOfItWithTheRecipient() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);
        expectItem(owner, h[0], 0, 0, 6);   // Remaining 6, return 0, missing 0: still with recipient 6
    }

    @Test
    void returnedPlusMissingMoreThanRemainingIsRefusedAndRecordsNothing() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);

        record(owner, h[0], line(h[1], "5", "GOOD"), line(h[1], "2", "MISSING")).andExpect(status().isConflict());   // 5 + 2 > 6
        record(owner, h[0], line(h[1], "7", "GOOD")).andExpect(status().isConflict());                                 // more than is owed at all
        record(owner, h[0], line(h[1], "7", "MISSING")).andExpect(status().isConflict());
        expectItem(owner, h[0], 0, 0, 6);   // nothing was recorded by any of them
    }

    @Test
    void negativeOrZeroQuantitiesAreRefused() throws Exception {
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);

        record(owner, h[0], line(h[1], "-1", "GOOD")).andExpect(status().isBadRequest());
        record(owner, h[0], line(h[1], "-1", "MISSING")).andExpect(status().isBadRequest());
        record(owner, h[0], line(h[1], "0", "GOOD")).andExpect(status().isBadRequest());
        expectItem(owner, h[0], 0, 0, 6);
    }

    @Test
    void whatIsStillMissingCanBeBroughtBackWithAReturnBeyondRemaining() throws Exception {
        // The one case where Return qty may exceed Remaining: units that were marked missing come back (the form's cap is Remaining + Missing).
        Account owner = register(SubscriptionPlan.HALF_YEARLY);
        long[] h = handoffOfSix(owner);
        record(owner, h[0], line(h[1], "4", "GOOD"), line(h[1], "2", "MISSING")).andExpect(status().isCreated());   // 4 back, 2 missing, 0 with the recipient

        record(owner, h[0], line(h[1], "2", "GOOD")).andExpect(status().isCreated());
        expectItem(owner, h[0], 6, 0, 0);
        record(owner, h[0], line(h[1], "1", "GOOD")).andExpect(status().isConflict());   // nothing is owed any more
    }
}

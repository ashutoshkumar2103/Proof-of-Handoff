package com.handoffly.handoff;

import com.handoffly.support.CapturingEmailSender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * End-to-end lifecycle over HTTP: create → submit → recipient accept → multiple partial
 * returns → full return → close, plus authorization and over-return guards. Exercises the
 * brief's tent-house example (Bedsheets 200 / Chairs 500 / Tables 80).
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(CapturingEmailSender.Config.class)
class HandoffLifecycleIntegrationTest {

    @Autowired
    MockMvc mvc;

    @Autowired
    CapturingEmailSender emailSender;

    @Test
    void fullHandoffAndReturnLifecycle() throws Exception {
        String token = registerAndLogin("owner1@example.com");

        // --- Create draft with three items ---
        String createBody = """
                {
                  "title": "Wedding rentals",
                  "purpose": "Event on Saturday",
                  "category": "EVENT",
                  "senderName": "Rentals Co",
                  "recipientName": "Event Client",
                  "recipientEmail": "client@example.com",
                  "items": [
                    {"name": "Bedsheets", "quantity": 200},
                    {"name": "Chairs", "quantity": 500},
                    {"name": "Tables", "quantity": 80}
                  ]
                }
                """;
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(createBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("DRAFT"))
                .andExpect(jsonPath("$.items.length()").value(3))
                .andReturn();

        String createdJson = created.getResponse().getContentAsString();
        int handoffId = JsonPath.read(createdJson, "$.id");
        int bedId = JsonPath.read(createdJson, "$.items[0].id");
        int chairId = JsonPath.read(createdJson, "$.items[1].id");
        int tableId = JsonPath.read(createdJson, "$.items[2].id");

        // --- Submit → email sent, awaiting recipient ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/submit")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("AWAITING_RECIPIENT"));

        String recipientToken = emailSender.extractLastToken();

        // --- Recipient views (public) ---
        mvc.perform(get("/api/v1/r/" + recipientToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.awaitingResponse").value(true))
                .andExpect(jsonPath("$.items.length()").value(3));

        // --- Recipient accepts with typed acknowledgement ---
        mvc.perform(post("/api/v1/r/" + recipientToken + "/accept")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgementName\":\"Event Client\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("ACTIVE_WITH_RECIPIENT"))
                .andExpect(jsonPath("$.acknowledgementName").value("Event Client"));

        // --- Over-return is rejected ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + bedId + ",\"quantity\":300}]}"))
                .andExpect(status().isConflict());

        // --- Return #1: partial ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"itemId\":" + bedId + ",\"quantity\":150},"
                                + "{\"itemId\":" + chairId + ",\"quantity\":500},"
                                + "{\"itemId\":" + tableId + ",\"quantity\":60}]}"))
                .andExpect(status().isCreated());

        MvcResult afterFirst = mvc.perform(get("/api/v1/handoffs/" + handoffId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andReturn();
        String s1 = afterFirst.getResponse().getContentAsString();
        assertThat(remaining(s1, 0)).isEqualByComparingTo("50");  // Bedsheets
        assertThat(remaining(s1, 1)).isEqualByComparingTo("0");   // Chairs
        assertThat(remaining(s1, 2)).isEqualByComparingTo("20");  // Tables

        // --- Return #2: remainder → fully returned ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"itemId\":" + bedId + ",\"quantity\":50},"
                                + "{\"itemId\":" + tableId + ",\"quantity\":20}]}"))
                .andExpect(status().isCreated());

        mvc.perform(get("/api/v1/handoffs/" + handoffId)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FULLY_RETURNED"))
                .andExpect(jsonPath("$.totalRemaining").value(0));

        // --- Close ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/close")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));

        // --- After close: further returns rejected, cancel rejected ---
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + bedId + ",\"quantity\":1}]}"))
                .andExpect(status().isConflict());
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/cancel")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        // --- Audit trail recorded the lifecycle ---
        mvc.perform(get("/api/v1/handoffs/" + handoffId + "/events")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type").value("HANDOFF_CREATED"));
    }

    @Test
    void requiresAuthentication() throws Exception {
        mvc.perform(get("/api/v1/handoffs")).andExpect(status().isUnauthorized());
    }

    @Test
    void decliningRequiresAReason() throws Exception {
        String token = registerAndLogin("decliner@example.com");
        int handoffId = createSimpleHandoff(token);
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();

        // Missing reason -> rejected by validation.
        mvc.perform(post("/api/v1/r/" + rtok + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgementName\":\"Someone\"}"))
                .andExpect(status().isBadRequest());

        // With a reason -> declined.
        mvc.perform(post("/api/v1/r/" + rtok + "/reject")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgementName\":\"Someone\",\"reason\":\"Quantity is wrong\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REJECTED"))
                .andExpect(jsonPath("$.rejectionReason").value("Quantity is wrong"));
    }

    @Test
    void missingItemsRequireRecipientConfirmationBeforeClose() throws Exception {
        String token = registerAndLogin("missing@example.com");
        String body = """
                {"title":"Tools","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Drill","quantity":2}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"R\"}")).andExpect(status().isOk());

        // Report both units missing -> fully accounted but flagged as missing.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"MISSING\"}]}"))
                .andExpect(status().isCreated());

        // Missing items mean the handoff is NOT fully returned.
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalMissing").value(2))
                .andExpect(jsonPath("$.hasUnconfirmedMissing").value(true));

        // Close is blocked until the recipient confirms the missing items.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                .header("Authorization", "Bearer " + token)).andExpect(status().isConflict());

        // Owner requests confirmation; recipient confirms via a fresh link.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/request-missing-confirmation")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok2 = emailSender.extractLastToken();
        // Confirming missing requires a typed acknowledgement name (proof of who confirmed).
        mvc.perform(post("/api/v1/r/" + rtok2 + "/confirm-missing")
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isBadRequest());
        mvc.perform(post("/api/v1/r/" + rtok2 + "/confirm-missing")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"acknowledgementName\":\"R\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.missingConfirmedAt").isNotEmpty())
                .andExpect(jsonPath("$.missingConfirmedByName").value("R"));

        // Confirmed, but closing with missing items still requires a reason.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                .header("Authorization", "Bearer " + token)).andExpect(status().isBadRequest());

        // With a reason, closing succeeds.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"Items written off as lost.\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void returnedAndMissingDoNotDoubleCount() throws Exception {
        String token = registerAndLogin("split@example.com");
        String body = """
                {"title":"Tables","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Table","quantity":15}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"R\"}")).andExpect(status().isOk());

        // 13 returned good + 2 missing = 15 accounted (no double counting).
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"itemId\":" + itemId + ",\"quantity\":13,\"condition\":\"GOOD\"},"
                                + "{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"MISSING\"}]}"))
                .andExpect(status().isCreated());

        // 2 missing → not fully returned, even though nothing is outstanding.
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalOutgoing").value(15))
                .andExpect(jsonPath("$.totalReturned").value(13))
                .andExpect(jsonPath("$.totalMissing").value(2))
                .andExpect(jsonPath("$.totalRemaining").value(0))
                .andExpect(jsonPath("$.items[0].returnedConfirmed").value(13))
                .andExpect(jsonPath("$.items[0].missing").value(2))
                .andExpect(jsonPath("$.items[0].remaining").value(0));
    }

    @Test
    void foundItemsRecoverFromMissingAndAllowClose() throws Exception {
        String token = registerAndLogin("found@example.com");
        String body = """
                {"title":"Recover","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Drill","quantity":5}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"R\"}"))
                .andExpect(status().isOk());

        // Return 3 good + 2 missing → not fully returned, 2 missing, remaining 0.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"itemId\":" + itemId + ",\"quantity\":3,\"condition\":\"GOOD\"},"
                                + "{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"MISSING\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalMissing").value(2));

        // The 2 missing are found and returned → recovery clears missing, now fully returned.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"RECOVERED\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("FULLY_RETURNED"))
                .andExpect(jsonPath("$.totalReturned").value(5))
                .andExpect(jsonPath("$.totalMissing").value(0))
                .andExpect(jsonPath("$.totalRemaining").value(0));

        // Over-recovering is rejected (can't find more than were missing).
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"RECOVERED\"}]}"))
                .andExpect(status().isConflict());

        // Now closeable with no missing.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void partialRecoveryLeavesTheRestMissing() throws Exception {
        String token = registerAndLogin("partial@example.com");
        String body = """
                {"title":"Partial","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Chair","quantity":5}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"R\"}"))
                .andExpect(status().isOk());

        // 2 good + 3 missing.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":["
                                + "{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"GOOD\"},"
                                + "{\"itemId\":" + itemId + ",\"quantity\":3,\"condition\":\"MISSING\"}]}"))
                .andExpect(status().isCreated());

        // Recover 2 of the 3 missing — 1 stays missing, still not fully returned.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"RECOVERED\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalReturned").value(4))
                .andExpect(jsonPath("$.totalMissing").value(1))
                .andExpect(jsonPath("$.totalRemaining").value(0));

        // Recover the last one → fully returned.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"RECOVERED\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("FULLY_RETURNED"))
                .andExpect(jsonPath("$.totalMissing").value(0));
    }

    /**
     * The return form sends only a Return qty (+ condition) and a Missing qty. Returning a
     * previously-missing item is a plain GOOD line — no special "recovered" condition — and
     * any un-returned remainder stays missing.
     */
    @Test
    void returningAMissingItemWithAGoodLineRecoversItAndLeavesTheRestMissing() throws Exception {
        String token = registerAndLogin("twoinput@example.com");
        String body = """
                {"title":"Two input","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Radio","quantity":2}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"R\"}"))
                .andExpect(status().isOk());

        // Both units reported missing.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"MISSING\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalMissing").value(2))
                .andExpect(jsonPath("$.totalRemaining").value(0));

        // One of the two missing units is found and returned — as a plain GOOD line.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"GOOD\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("PARTIALLY_RETURNED"))
                .andExpect(jsonPath("$.totalReturned").value(1))
                .andExpect(jsonPath("$.totalMissing").value(1))      // the other stays missing
                .andExpect(jsonPath("$.totalRemaining").value(0));

        // Returning more than is owed (only 1 unit left) is rejected.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":2,\"condition\":\"GOOD\"}]}"))
                .andExpect(status().isConflict());

        // The last missing unit comes back → fully returned, all conditions GOOD.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"GOOD\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.status").value("FULLY_RETURNED"))
                .andExpect(jsonPath("$.totalReturned").value(2))
                .andExpect(jsonPath("$.totalMissing").value(0));
    }

    /** Items still outstanding (not returned, not missing) must block closing outright. */
    @Test
    void cannotCloseWhileItemsAreOutstanding() throws Exception {
        String token = registerAndLogin("outstanding@example.com");
        String body = """
                {"title":"Outstanding","senderName":"S","recipientName":"R","recipientEmail":"r@x.com",
                 "items":[{"name":"Plate","quantity":7}]}""";
        MvcResult created = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String cj = created.getResponse().getContentAsString();
        int hid = JsonPath.read(cj, "$.id");
        int itemId = JsonPath.read(cj, "$.items[0].id");

        mvc.perform(post("/api/v1/handoffs/" + hid + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        String rtok = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + rtok + "/accept")
                .contentType(MediaType.APPLICATION_JSON).content("{\"acknowledgementName\":\"R\"}"))
                .andExpect(status().isOk());

        // Only 6 of 7 returned → 1 genuinely outstanding.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":6,\"condition\":\"GOOD\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + hid).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalRemaining").value(1))
                .andExpect(jsonPath("$.totalMissing").value(0));

        // Closing is rejected — a reason cannot override an outstanding item.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"reason\":\"close anyway\"}"))
                .andExpect(status().isConflict());

        // Return the last unit → now it closes with no reason needed.
        mvc.perform(post("/api/v1/handoffs/" + hid + "/returns")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1,\"condition\":\"GOOD\"}]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/handoffs/" + hid + "/close")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("CLOSED"));
    }

    @Test
    void recipientCannotRecordReturns() throws Exception {
        // The recipient returns endpoint has been removed — only the owner records returns.
        mvc.perform(post("/api/v1/r/any-token/returns")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[]}"))
                .andExpect(status().isNotFound());
    }

    @Test
    void invalidRecipientTokenIsNotFound() throws Exception {
        mvc.perform(get("/api/v1/r/does-not-exist")).andExpect(status().isNotFound());
    }

    @Test
    void recipientCannotAccessAnotherHandoffsData() throws Exception {
        // Owner A creates & submits a handoff.
        String tokenA = registerAndLogin("ownerA@example.com");
        int handoffId = createSimpleHandoff(tokenA);
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/submit")
                .header("Authorization", "Bearer " + tokenA)).andExpect(status().isOk());

        // Owner B must not read A's handoff.
        String tokenB = registerAndLogin("ownerB@example.com");
        mvc.perform(get("/api/v1/handoffs/" + handoffId)
                        .header("Authorization", "Bearer " + tokenB))
                .andExpect(status().isForbidden());
    }

    // ------------------------------------------------------------- helpers

    private String registerAndLogin(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\","
                + "\"displayName\":\"Owner\"}";
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.token");
    }

    private int createSimpleHandoff(String token) throws Exception {
        String body = """
                {
                  "title": "IT laptop handout",
                  "senderName": "IT Dept",
                  "recipientName": "New Hire",
                  "recipientEmail": "hire@example.com",
                  "items": [{"name": "Laptop", "quantity": 1, "serialNumber": "SN-1"}]
                }
                """;
        MvcResult res = mvc.perform(post("/api/v1/handoffs")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        return JsonPath.read(res.getResponse().getContentAsString(), "$.id");
    }

    private static java.math.BigDecimal remaining(String json, int index) {
        Number n = JsonPath.read(json, "$.items[" + index + "].remaining");
        return new java.math.BigDecimal(n.toString());
    }
}

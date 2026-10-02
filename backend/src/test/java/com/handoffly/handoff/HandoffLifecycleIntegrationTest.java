package com.handoffly.handoff;

import com.handoffly.testsupport.CapturingEmailSender;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
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

    @Autowired
    org.springframework.jdbc.core.JdbcTemplate jdbc;

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

    @Test
    void returnImportPrefillsButTheExistingReturnApiRecordsAndValidates() throws Exception {
        String token = registerAndLogin("importer@example.com");
        String body = """
                {"title":"Party kit","senderName":"Rentals","recipientName":"Client","recipientEmail":"c@example.com",
                 "items":[{"name":"Table","quantity":1},{"name":"Curtain","quantity":3},
                          {"name":"Joker Dress","quantity":2},{"name":"Balloon Filler","quantity":1}]}
                """;
        MvcResult created = mvc.perform(post("/api/v1/handoffs").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated()).andReturn();
        String createdJson = created.getResponse().getContentAsString();
        int handoffId = JsonPath.read(createdJson, "$.id");
        int curtainId = JsonPath.read(createdJson, "$.items[1].id");

        // A draft can't take returns, so importing for it is refused.
        mvc.perform(multipart("/api/v1/handoff-check/return-import")
                        .file(csv("Item,Qty\nTable,1\n")).param("handoffId", String.valueOf(handoffId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isConflict());

        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/submit")
                .header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(post("/api/v1/r/" + emailSender.extractLastToken() + "/accept")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"Client\"}")).andExpect(status().isOk());

        // Names differ in case/spacing/punctuation; one file item doesn't exist in the handoff;
        // Curtain is over-quantity (5 > 3 remaining).
        String file = "Item,Qty\nTABLE,1\ncurtain ,5\nJoker-Dress,2\nBalloon  Filler,1\nGhost Item,4\n";
        mvc.perform(multipart("/api/v1/handoff-check/return-import")
                        .file(csv(file)).param("handoffId", String.valueOf(handoffId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(5))
                .andExpect(jsonPath("$.rows[0].match").value("MATCHED"))
                .andExpect(jsonPath("$.rows[1].match").value("MATCHED"))
                .andExpect(jsonPath("$.rows[1].itemId").value(curtainId))
                .andExpect(jsonPath("$.rows[1].importedQuantity").value(5))
                .andExpect(jsonPath("$.rows[1].owedQuantity").value(3))
                .andExpect(jsonPath("$.rows[4].match").value("UNMATCHED"))
                .andExpect(jsonPath("$.rows[4].itemId").doesNotExist());

        // Importing creates nothing: no returns yet, and no new handoff items.
        mvc.perform(get("/api/v1/handoffs/" + handoffId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.returns.length()").value(0))
                .andExpect(jsonPath("$.items.length()").value(4));

        // The existing return API stays authoritative: the over-quantity is still rejected...
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + curtainId + ",\"quantity\":5}]}"))
                .andExpect(status().isConflict());
        // ...and the corrected (prefilled) quantity records exactly one return event.
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns")
                        .header("Authorization", "Bearer " + token).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"lines\":[{\"itemId\":" + curtainId + ",\"quantity\":2}]}"))
                .andExpect(status().isCreated());
        mvc.perform(get("/api/v1/handoffs/" + handoffId).header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.returns.length()").value(1))
                .andExpect(jsonPath("$.items[1].remaining").value(1));

        // Another owner can't import against this handoff.
        String other = registerAndLogin("importer2@example.com");
        mvc.perform(multipart("/api/v1/handoff-check/return-import")
                        .file(csv(file)).param("handoffId", String.valueOf(handoffId))
                        .header("Authorization", "Bearer " + other))
                .andExpect(status().isForbidden());
        // Unsupported file types are rejected.
        mvc.perform(multipart("/api/v1/handoff-check/return-import")
                        .file(new MockMultipartFile("file", "x.txt", "text/plain", "Table,1".getBytes()))
                        .param("handoffId", String.valueOf(handoffId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest());
    }

    @Test
    void standaloneHandoffCheckComparesTwoUploadedFilesWithoutAnyHandoff() throws Exception {
        String token = registerAndLogin("checker@example.com");
        jdbc.update("UPDATE app_user SET subscription_plan = 'YEARLY' WHERE email = ?", "checker@example.com");   // HandoffCheck is a Half-Yearly / Yearly feature
        MockMultipartFile fileA = new MockMultipartFile("fileA", "a.csv", "text/csv",
                "Item,Qty\nTable,100\nLight,10".getBytes());
        MockMultipartFile fileB = new MockMultipartFile("fileB", "b.csv", "text/csv",
                "Item,Qty\nTable,95\nGenerator,2".getBytes());

        mvc.perform(multipart("/api/v1/handoff-check/compare-files").file(fileA).file(fileB)
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.referenceLabel").value("File A"))
                .andExpect(jsonPath("$.targetLabel").value("File B"))
                .andExpect(jsonPath("$.lines[0].status").value("MISMATCH"))
                .andExpect(jsonPath("$.lines[0].difference").value(-5))
                .andExpect(jsonPath("$.lines[1].status").value("MISSING_IN_TARGET"))
                .andExpect(jsonPath("$.lines[2].status").value("EXTRA_IN_TARGET"));

        // Review step: extract one file into editable lines...
        mvc.perform(multipart("/api/v1/handoff-check/extract").file(csv("Item,Qty\nTable,100\nLight,10"))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].name").value("Table"))
                .andExpect(jsonPath("$[0].quantity").value(100));
        // ...then the corrected data is compared through the existing structured endpoint.
        mvc.perform(post("/api/v1/handoff-check").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"referenceLabel\":\"File A\",\"referenceLines\":[{\"name\":\"Table\",\"quantity\":100}],"
                                + "\"targetLabel\":\"File B\",\"targetLines\":[{\"name\":\"Table\",\"quantity\":100}]}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.allMatch").value(true));

        // A bad file is a clear 400 that names the file; anonymous callers are refused.
        mvc.perform(multipart("/api/v1/handoff-check/compare-files").file(fileA)
                        .file(new MockMultipartFile("fileB", "notes.txt", "text/plain", "Table,1".getBytes()))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("File B")));
        mvc.perform(multipart("/api/v1/handoff-check/compare-files").file(fileA).file(fileB))
                .andExpect(status().isUnauthorized());

        // Stateless: no handoff exists afterwards.
        mvc.perform(get("/api/v1/handoffs").header("Authorization", "Bearer " + token))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void proofOfHandoffPdfCanBeDownloadedAndEmailedByItsOwnerOnly() throws Exception {
        String token = registerAndLogin("pdfowner@example.com");
        int handoffId = createSimpleHandoff(token);
        String pdfUrl = "/api/v1/handoffs/" + handoffId + "/pdf";
        String emailUrl = "/api/v1/handoffs/" + handoffId + "/email-pdf";

        // A draft can be exported but is plainly marked as not final.
        assertThat(pdfText(downloadPdf(pdfUrl, token))).contains("Interim copy", "Draft");

        // Run the real lifecycle: submit → accept → full return → close.
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/submit").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());
        String reviewToken = emailSender.extractLastToken();
        mvc.perform(post("/api/v1/r/" + reviewToken + "/accept").contentType(MediaType.APPLICATION_JSON)
                .content("{\"acknowledgementName\":\"New Hire\"}")).andExpect(status().isOk());
        String detail = mvc.perform(get("/api/v1/handoffs/" + handoffId).header("Authorization", "Bearer " + token))
                .andReturn().getResponse().getContentAsString();
        int itemId = JsonPath.read(detail, "$.items[0].id");
        String code = JsonPath.read(detail, "$.publicCode");
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/returns").header("Authorization", "Bearer " + token)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"note\":\"All back\",\"lines\":[{\"itemId\":" + itemId + ",\"quantity\":1}]}"))
                .andExpect(status().isCreated());
        mvc.perform(post("/api/v1/handoffs/" + handoffId + "/close").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        // Download: content type, safe deterministic filename, never cached, real PDF content.
        MvcResult res = mvc.perform(get(pdfUrl).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andExpect(header().string("Content-Disposition",
                        org.hamcrest.Matchers.containsString("HandOffly-" + code + "-Proof-of-Handoff.pdf")))
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andReturn();
        byte[] pdf = res.getResponse().getContentAsByteArray();
        String text = pdfText(pdf);
        assertThat(new String(pdf, 0, 5)).isEqualTo("%PDF-");
        assertThat(text).contains("Reference: " + code, "ACKNOWLEDGED BY New Hire", "Laptop", "Serial SN-1",
                "TOTAL GIVEN 1 TOTAL RETURNED 1 TOTAL MISSING 0", "RETURN SUMMARY", "1 item returned.", "Note: All back");
        // A concise customer document: no audit trail, status banner or remaining column.
        assertThat(text).doesNotContain("pdfowner@example.com", "Handoff closed", "Review link", "Interim copy",
                "FINAL RECORD", "REMAINING", "Important events");

        // Only the owner may export or email it.
        String other = registerAndLogin("pdfother@example.com");
        mvc.perform(get(pdfUrl)).andExpect(status().isUnauthorized());
        mvc.perform(get(pdfUrl).header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());
        mvc.perform(post(emailUrl)).andExpect(status().isUnauthorized());
        mvc.perform(post(emailUrl).header("Authorization", "Bearer " + other)).andExpect(status().isForbidden());

        // Email defaults to the handoff's recipient and carries the PDF as an attachment.
        mvc.perform(post(emailUrl).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value("hire@example.com"))
                .andExpect(jsonPath("$.delivered").value(true));   // the capturing test sender stands in for a real one
        var mail = emailSender.getLastMessage();
        assertThat(mail.to()).isEqualTo("hire@example.com");
        assertThat(mail.subject()).isEqualTo("Proof of Handoff — " + code);
        assertThat(mail.textBody()).contains("Hello New Hire,", "Attached is the Proof-of-Handoff record for " + code + ".",
                "Handoff: IT laptop handout", "Attachment: HandOffly-" + code + "-Proof-of-Handoff.pdf", "Thank You,", "IT Dept");
        assertThat(mail.attachments()).hasSize(1);
        var attachment = mail.attachments().getFirst();
        assertThat(attachment.filename()).isEqualTo("HandOffly-" + code + "-Proof-of-Handoff.pdf");
        assertThat(attachment.contentType()).isEqualTo("application/pdf");
        assertThat(attachment.content().length).isGreaterThan(1000);
        assertThat(pdfText(attachment.content())).contains("TOTAL GIVEN 1", "Laptop").doesNotContain("Interim copy");

        // The sender can choose another address; an invalid one is rejected and nothing is sent.
        mvc.perform(post(emailUrl).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"accounts@example.com\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.sentTo").value("accounts@example.com"));
        assertThat(emailSender.getLastMessage().to()).isEqualTo("accounts@example.com");
        mvc.perform(post(emailUrl).header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"to\":\"not-an-email\"}"))
                .andExpect(status().isBadRequest());
        assertThat(emailSender.getLastMessage().to()).isEqualTo("accounts@example.com");
    }

    // ------------------------------------------------------------- helpers

    private byte[] downloadPdf(String url, String token) throws Exception {
        return mvc.perform(get(url).header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(content().contentType(MediaType.APPLICATION_PDF))
                .andReturn().getResponse().getContentAsByteArray();
    }

    private static String pdfText(byte[] pdf) throws Exception {
        try (var doc = org.apache.pdfbox.Loader.loadPDF(pdf)) {
            return new org.apache.pdfbox.text.PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "returns.csv", "text/csv", content.getBytes());
    }

    private String registerAndLogin(String email) throws Exception {
        String body = "{\"email\":\"" + email + "\",\"password\":\"password123\","
                + "\"displayName\":\"Owner\"}";
        MvcResult res = mvc.perform(post("/api/v1/auth/register")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated())
                .andReturn();
        // Registration leaves an account with no plan; these tests are about the handoff lifecycle, so put it on one (as a payment would).
        jdbc.update("UPDATE app_user SET subscription_plan = 'MONTHLY' WHERE email = ?", email);
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

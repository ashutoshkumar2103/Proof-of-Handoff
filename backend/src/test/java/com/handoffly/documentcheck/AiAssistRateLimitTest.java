package com.handoffly.documentcheck;

import com.handoffly.ai.StubAiModel;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;

import static com.handoffly.testsupport.TestDocuments.csv;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** AI Assist spends a small shared quota, so each customer may use it only so often; HandoffCheck itself is not limited by it. */
@Import(StubAiModel.Config.class)
@TestPropertySource(properties = {"handoffly.rate-limit.enabled=true", "handoffly.rate-limit.ai-assist-per-user=2"})
class AiAssistRateLimitTest extends ApiTestBase {

    @Autowired
    private StubAiModel ai;

    @Test
    void aCustomerOverTheirHourlyAllowanceGetsAFriendlyAnswerAndTheAiIsNotAskedAgain() throws Exception {
        ai.reset();
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        Account other = register(SubscriptionPlan.HALF_YEARLY);
        String url = "/api/v1/handoff-check/ai/column-mapping";

        for (int i = 0; i < 2; i++) {
            mvc.perform(as(customer, multipart(url).file(csv("a.csv", "Item,Qty\nChair,5\n")))).andExpect(status().isOk());
        }
        mvc.perform(as(customer, multipart(url).file(csv("a.csv", "Item,Qty\nChair,5\n")))).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value(AiMappingService.AI_RATE_LIMITED));
        assertThat(ai.calls()).hasSize(2);

        // It is that customer's allowance only, and the ordinary reading is not counted against it.
        mvc.perform(as(other, multipart(url).file(csv("a.csv", "Item,Qty\nChair,5\n")))).andExpect(status().isOk());
        assertThat(ai.calls()).hasSize(3);
        mvc.perform(as(customer, multipart("/api/v1/handoff-check/extract").file(csv("a.csv", "Item,Qty\nChair,5\n"))))
                .andExpect(status().isOk());
    }

    @Test
    void theItemNameSuggestionsSpendFromTheSameAllowanceAsTheColumnSuggestions() throws Exception {
        ai.reset();
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String names = "{\"fileA\":[\"Exam Pad\"],\"fileB\":[\"Exam Ped\"]}";
        String url = "/api/v1/handoff-check/ai/item-matching";

        mvc.perform(as(customer, multipart("/api/v1/handoff-check/ai/column-mapping").file(csv("a.csv", "Item,Qty\nChair,5\n")))).andExpect(status().isOk());
        mvc.perform(as(customer, post(url).contentType(MediaType.APPLICATION_JSON).content(names))).andExpect(status().isOk());   // the second of two
        mvc.perform(as(customer, post(url).contentType(MediaType.APPLICATION_JSON).content(names))).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value(AiMappingService.AI_RATE_LIMITED));
        assertThat(ai.calls()).hasSize(2);   // the third never reached the model
    }
}

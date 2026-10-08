package com.handoffly.documentcheck;

import com.handoffly.ai.AiUnavailableException.Reason;
import com.handoffly.ai.StubAiModel;
import com.handoffly.testsupport.ApiTestBase;
import com.handoffly.user.SubscriptionPlan;
import com.jayway.jsonpath.JsonPath;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.util.List;

import static com.handoffly.testsupport.TestDocuments.csv;
import static com.handoffly.testsupport.TestDocuments.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI Assist for HandoffCheck: an optional helper that suggests which columns hold the item and the quantity. The AI is replaced by a
 * stand-in that answers whatever the test says, so these tests prove what the application does with an answer — and that the ordinary
 * HandoffCheck is the same with or without one.
 */
@Import(StubAiModel.Config.class)
@TestPropertySource(properties = "handoffly.ai.gemini-api-key=test-only-secret-key-4f81c")
class AiAssistTest extends ApiTestBase {

    private static final String SECRET = "test-only-secret-key-4f81c";
    private static final String ASSIST = "/api/v1/handoff-check/ai/column-mapping";
    private static final String EXTRACT = "/api/v1/handoff-check/extract";
    private static final String COMPARE_FILES = "/api/v1/handoff-check/compare-files";

    /** The headings are ones the ordinary reading does not know, so on its own it takes the product CODE for the item. */
    private static final String ODD_HEADINGS = "SKU,Goods Supplied,Units Out\nP-1,Chair,5\nP-2,Table,2\nP-3,Lamp,7\n";
    private static final String RIGHT_ANSWER = "{\"headerRow\":0,\"suggestions\":["
            + "{\"column\":1,\"targetField\":\"ITEM\",\"confidence\":0.96,\"reason\":\"Names of things\"},"
            + "{\"column\":2,\"targetField\":\"QUANTITY\",\"confidence\":0.97,\"reason\":\"Whole numbers\"}]}";

    @Autowired
    private StubAiModel ai;

    @BeforeEach
    void freshStub() {
        ai.reset();
    }

    private ResultActions assist(Bearer who, MockMultipartFile file) throws Exception {
        return mvc.perform(as(who, multipart(ASSIST).file(file)));
    }

    private ResultActions extract(Bearer who, MockMultipartFile file, String... params) throws Exception {
        var request = multipart(EXTRACT).file(file);
        for (int i = 0; i < params.length; i += 2) request.param(params[i], params[i + 1]);
        return mvc.perform(as(who, request));
    }

    // ------------------------------------------------------------------ the suggestion

    @Test
    void aSoundAnswerIsShownAsASuggestionWithTheHeadingsAndSomeValuesToJudgeItBy() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        ai.answer(RIGHT_ANSWER);

        assist(customer, csv("odd.csv", ODD_HEADINGS)).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.item.column").value(1))
                .andExpect(jsonPath("$.item.columnName").value("Goods Supplied"))
                .andExpect(jsonPath("$.item.confidence").value(0.96))
                .andExpect(jsonPath("$.item.reason").value("Names of things"))
                .andExpect(jsonPath("$.item.sampleValues[0]").value("Chair"))
                .andExpect(jsonPath("$.quantity.column").value(2))
                .andExpect(jsonPath("$.quantity.columnName").value("Units Out"))
                .andExpect(jsonPath("$.quantity.sampleValues.length()").value(3))
                .andExpect(jsonPath("$.headerRow").value(0))
                .andExpect(jsonPath("$.itemsFound").value(3));
        assertThat(ai.calls()).hasSize(1);
    }

    @Test
    void aSuggestionChangesNothingUntilTheCustomerAcceptsItAndThenTheOrdinaryReadingUsesTheChosenColumns() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);
        ai.answer(RIGHT_ANSWER);

        // The suggestion carries no lines: there is nothing to import yet.
        String suggestion = assist(customer, file).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(suggestion).doesNotContain("\"lines\"").doesNotContain("Lamp\",\"quantity");

        // Reading the file the ordinary way is exactly what it was before: not the AI's idea, and no AI call.
        extract(customer, file).andExpect(status().isOk()).andExpect(jsonPath("$[0].name").value("P-1"));
        assertThat(ai.calls()).hasSize(1);

        // Only when the customer accepts is the suggestion used: the ordinary routine, with those columns.
        extract(customer, file, "itemColumn", "1", "quantityColumn", "2", "headerRow", "0").andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(3))
                .andExpect(jsonPath("$[0].name").value("Chair"))
                .andExpect(jsonPath("$[0].quantity").value(5))
                .andExpect(jsonPath("$[2].name").value("Lamp"));
        assertThat(ai.calls()).hasSize(1);
    }

    @Test
    void whatIsSentToTheAiIsAFewSanitizedRowsAndNothingAboutTheCustomer() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        StringBuilder big = new StringBuilder("Name,Contact,Qty,Notes\n");
        big.append("Chair,jane.doe@example.com,5,call +1 555 123 4567 now\n");
        big.append("Table,+91 98765 43210,2,").append("n".repeat(200)).append('\n');
        for (int i = 0; i < 40; i++) big.append("Row").append(i).append(",x,1,y\n");
        ai.answer("{\"headerRow\":0,\"suggestions\":[{\"column\":0,\"targetField\":\"ITEM\",\"confidence\":0.9,\"reason\":\"r\"},"
                + "{\"column\":2,\"targetField\":\"QUANTITY\",\"confidence\":0.9,\"reason\":\"r\"}]}");

        assist(customer, csv("secret-customer-list.csv", big.toString())).andExpect(status().isOk());

        StubAiModel.Call call = ai.calls().get(0);
        List<?> rows = JsonPath.read(call.data(), "$");
        assertThat(rows).hasSize(8);                                                  // the first rows, not the file
        assertThat(call.data()).doesNotContain("Row10", "Row39")
                .doesNotContain("jane.doe", "example.com", "555", "98765")           // contact details are hidden
                .contains("[hidden]")
                .doesNotContain("n".repeat(41));                                      // long cells are cut short
        String everything = call.instruction() + call.data();
        assertThat(everything).doesNotContain(customer.email(), customer.accountCode(), "secret-customer-list", SECRET)
                .doesNotContain(String.valueOf(customer.id()) + "\"");
    }

    // ------------------------------------------------------------------ when the AI does not help

    private void assertNoUsableSuggestion(ResultActions result, String message, boolean canRetry) throws Exception {
        result.andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value(message))
                .andExpect(jsonPath("$.canRetry").value(canRetry))
                .andExpect(jsonPath("$.item").doesNotExist())
                .andExpect(jsonPath("$.quantity").doesNotExist());
    }

    @Test
    void anAnswerThatIsNotUsableIsRefusedAndHandoffCheckCarriesOnAsNormal() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);

        for (String bad : new String[]{"I think column B is the item.", "{\"suggestions\":\"none\"}",
                "{\"headerRow\":0,\"suggestions\":[{\"column\":9,\"targetField\":\"ITEM\",\"confidence\":0.9,\"reason\":\"r\"}]}",
                "{\"headerRow\":0,\"suggestions\":[{\"column\":1,\"targetField\":\"ITEM\",\"confidence\":7,\"reason\":\"r\"}]}"}) {
            ai.answer(bad);
            assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_UNAVAILABLE, true);   // slow or unusable: asking again may work
        }
        assertThat(ai.calls()).hasSize(4);

        // Nothing about the ordinary reading, the review or the comparison depends on it.
        extract(customer, file).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(3));
        mvc.perform(as(customer, multipart(COMPARE_FILES).file(new MockMultipartFile("fileA", "a.csv", "text/csv", "Item,Qty\nChair,5\n".getBytes()))
                        .file(new MockMultipartFile("fileB", "b.csv", "text/csv", "Item,Qty\nChair,4\n".getBytes()))))
                .andExpect(status().isOk()).andExpect(jsonPath("$.summary.mismatched").value(1));
    }

    @Test
    void whateverGoesWrongWithTheProviderIsAFriendlyNotAvailableAndNeverTheProvidersOwnWords() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);

        ai.fail(Reason.UNAVAILABLE);                      // timeout, outage, quota, a refused key: all one category to the customer
        assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_UNAVAILABLE, true);   // slow or unusable: asking again may work
        ai.fail(Reason.INVALID_RESPONSE);
        assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_UNAVAILABLE, true);   // slow or unusable: asking again may work
        ai.fail(Reason.RATE_LIMITED);
        assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_RATE_LIMITED, false);
        ai.fail(Reason.NOTHING_FOUND);
        assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_NOTHING_FOUND, false);

        ai.reset();
        ai.configured(false);                             // no key on this server
        assertNoUsableSuggestion(assist(customer, file), AiMappingService.AI_NOT_CONFIGURED, false);
        assertThat(ai.calls()).isEmpty();

        extract(customer, file).andExpect(status().isOk());   // and the ordinary HandoffCheck is untouched by all of it
    }

    @Test
    void aSuggestionThatDoesNotFitTheRealFileIsNotTakenAtItsWord() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        // The AI says the quantity is the name column: the ordinary reading finds no numbers there, so there is nothing to offer.
        ai.answer("{\"headerRow\":0,\"suggestions\":[{\"column\":2,\"targetField\":\"ITEM\",\"confidence\":0.99,\"reason\":\"r\"},"
                + "{\"column\":1,\"targetField\":\"QUANTITY\",\"confidence\":0.99,\"reason\":\"r\"}]}");

        assertNoUsableSuggestion(assist(customer, csv("odd.csv", ODD_HEADINGS)), AiMappingService.AI_NOTHING_FOUND, false);
    }

    @Test
    void onlySpreadsheetsCanBeAssisted() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        assist(customer, pdf("list.pdf", "Chairs 5")).andExpect(status().isBadRequest());
        assist(customer, csv("notes.txt", "hello")).andExpect(status().isBadRequest());
        assertThat(ai.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ choosing columns by hand

    @Test
    void theColumnsAreCheckedBeforeTheyAreUsedWhoeverChoseThem() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);

        extract(customer, file, "itemColumn", "1").andExpect(status().isBadRequest());                      // one without the other
        extract(customer, file, "quantityColumn", "2").andExpect(status().isBadRequest());
        extract(customer, file, "itemColumn", "1", "quantityColumn", "1").andExpect(status().isBadRequest()); // the same column twice
        extract(customer, file, "itemColumn", "-1", "quantityColumn", "2").andExpect(status().isBadRequest());
        extract(customer, file, "itemColumn", "1", "quantityColumn", "99999").andExpect(status().isBadRequest());
        extract(customer, file, "itemColumn", "1", "quantityColumn", "2", "headerRow", "-5").andExpect(status().isBadRequest());
        // Columns that hold no quantities give the ordinary "no rows" answer, not an empty success.
        extract(customer, file, "itemColumn", "0", "quantityColumn", "1", "headerRow", "0").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.detail").value(containsString("No item and quantity rows")));
        // A PDF has no columns to choose.
        extract(customer, pdf("list.pdf", "Chairs 5"), "itemColumn", "0", "quantityColumn", "1").andExpect(status().isBadRequest());
    }

    @Test
    void theAcceptedColumnsFeedTheSameComparisonAsAnyOtherLines() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        String lines = extract(customer, csv("odd.csv", ODD_HEADINGS), "itemColumn", "1", "quantityColumn", "2", "headerRow", "0")
                .andReturn().getResponse().getContentAsString();
        String body = "{\"referenceLines\":" + lines + ",\"targetLines\":[{\"name\":\"chair\",\"quantity\":5},{\"name\":\"Table\",\"quantity\":3},"
                + "{\"name\":\"Sofa\",\"quantity\":1}]}";

        mvc.perform(as(customer, post("/api/v1/handoff-check").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.summary.matched").value(1))
                .andExpect(jsonPath("$.summary.mismatched").value(1))
                .andExpect(jsonPath("$.summary.missingInTarget").value(1))
                .andExpect(jsonPath("$.summary.extraInTarget").value(1));
    }

    // ------------------------------------------------------------------ who may use it

    @Test
    void itIsPartOfHandoffCheckSoItFollowsThePlanThatHasHandoffCheck() throws Exception {
        ai.answer(RIGHT_ANSWER);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.MONTHLY, SubscriptionPlan.QUARTERLY)) {
            Account customer = register(plan);
            assist(customer, file).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_required"));
        }
        assertThat(ai.calls()).isEmpty();                                   // refused before anything could be sent

        for (SubscriptionPlan plan : List.of(SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY)) {
            assist(register(plan), file).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        }
        assertThat(ai.calls()).hasSize(2);
    }

    @Test
    void anAccountWithNoPlanOrAnEndedPlanIsRefusedBeforeTheAi() throws Exception {
        assist(registerWithoutPlan(), csv("odd.csv", ODD_HEADINGS)).andExpect(status().isForbidden());
        assist(register(SubscriptionPlan.YEARLY), csv("odd.csv", ODD_HEADINGS)).andExpect(status().isOk());
        mvc.perform(multipart(ASSIST).file(csv("odd.csv", ODD_HEADINGS))).andExpect(status().isUnauthorized());
        assertThat(ai.calls()).hasSize(1);
    }

    // ------------------------------------------------------------------ the key

    @Test
    void theApiKeyIsNeverInAnythingTheBrowserReceives() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        ai.answer(RIGHT_ANSWER);
        MockMultipartFile file = csv("odd.csv", ODD_HEADINGS);

        List<String> bodies = List.of(
                assist(customer, file).andReturn().getResponse().getContentAsString(),
                extract(customer, file).andReturn().getResponse().getContentAsString(),
                mvc.perform(as(customer, get("/api/v1/auth/me"))).andReturn().getResponse().getContentAsString(),
                mvc.perform(get("/api/v1/public/plans")).andReturn().getResponse().getContentAsString());
        ai.fail(Reason.UNAVAILABLE);
        String failed = assist(customer, file).andReturn().getResponse().getContentAsString();
        for (String body : bodies) assertThat(body).doesNotContain(SECRET);
        assertThat(failed).doesNotContain(SECRET).doesNotContain("Exception").doesNotContain("google");
    }
}

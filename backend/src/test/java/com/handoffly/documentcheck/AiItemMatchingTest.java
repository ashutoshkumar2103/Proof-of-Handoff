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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.ResultActions;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static com.handoffly.testsupport.TestDocuments.csv;
import static com.handoffly.testsupport.TestDocuments.file;
import static com.handoffly.testsupport.TestDocuments.xlsx;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * AI Assist for the item names of two files: it only suggests which names look like the same item written differently. The AI is replaced
 * by a stand-in that answers whatever a test says, so these tests prove what the application does with an answer — that nothing broader than
 * a spelling variation is ever offered, that nothing changes until the customer accepts (and then only through the ordinary comparison, one
 * row per item), and that HandoffCheck is the same with or without the AI.
 */
@Import(StubAiModel.Config.class)
@TestPropertySource(properties = "handoffly.ai.gemini-api-key=test-only-secret-key-7c20e")
class AiItemMatchingTest extends ApiTestBase {

    private static final String SECRET = "test-only-secret-key-7c20e";
    private static final String MATCHING = "/api/v1/handoff-check/ai/item-matching";
    private static final String COMPARE = "/api/v1/handoff-check";
    private static final String EXTRACT = "/api/v1/handoff-check/extract";
    private static final String EXPORT = "/api/v1/handoff-check/export";

    private static final List<String> FILE_A = List.of("10th History Book", "Exam Pad", "10th Science Book", "Pen");
    private static final List<String> FILE_B = List.of("10th History Books", "Exam Ped", "10th Senence Book");

    @Autowired
    private StubAiModel ai;

    @BeforeEach
    void freshStub() {
        ai.reset();
    }

    private static String json(List<String> names) {
        return "[" + String.join(",", names.stream().map(n -> "\"" + n + "\"").toList()) + "]";
    }

    private ResultActions suggest(Bearer who, List<String> a, List<String> b) throws Exception {
        return mvc.perform(as(who, post(MATCHING).contentType(MediaType.APPLICATION_JSON)
                .content("{\"fileA\":" + json(a) + ",\"fileB\":" + json(b) + "}")));
    }

    /** The model's verdict on pair {@code id}: the same item or not, and which file (A or B) spelled it correctly. */
    private static String verdict(int id, boolean same, String canonicalFile, double confidence) {
        return "{\"id\":" + id + ",\"sameItem\":" + same + ",\"canonicalFile\":\"" + canonicalFile + "\",\"confidence\":" + confidence
                + ",\"reason\":\"Same item, spelled differently\"}";
    }

    private void answer(String... verdicts) {
        ai.answer("{\"verdicts\":[" + String.join(",", verdicts) + "]}");
    }

    /**
     * The pairs worth asking about for FILE_A and FILE_B are found by the application (not the model), in File A's order: 0 is the history
     * books, 1 the exam pads, 2 the science books. Here the model judges all three the same item, File A being spelled correctly.
     */
    private void theExampleAnswer() {
        answer(verdict(0, true, "A", 0.97), verdict(1, true, "A", 0.95), verdict(2, true, "A", 0.93));
    }

    // ------------------------------------------------------------------ the suggestion

    @Test
    void theVariationsOfTheExampleAreSuggestedForReviewWithWhichFileEachNameIsIn() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        theExampleAnswer();

        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(true))
                .andExpect(jsonPath("$.matches.length()").value(3))
                .andExpect(jsonPath("$.matches[1].from").value("Exam Ped"))
                .andExpect(jsonPath("$.matches[1].fromFile").value("File B"))
                .andExpect(jsonPath("$.matches[1].to").value("Exam Pad"))
                .andExpect(jsonPath("$.matches[1].toFile").value("File A"))
                .andExpect(jsonPath("$.matches[1].certain").value(true))
                .andExpect(jsonPath("$.matches[1].reason").value("Same item, spelled differently"))
                .andExpect(jsonPath("$.matches[0].from").value("10th History Books"))
                .andExpect(jsonPath("$.matches[0].to").value("10th History Book"))
                .andExpect(jsonPath("$.matches[2].from").value("10th Senence Book"))
                .andExpect(jsonPath("$.matches[2].to").value("10th Science Book"));
        assertThat(ai.calls()).hasSize(1);   // one request for all the pairs, not one per row
    }

    @Test
    void theAiIsGivenPairsOfItemNamesAndNothingElse() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        answer(verdict(0, true, "A", 0.95));

        suggest(customer, List.of("Exam Pad", "Pen", "ask bob@example.test", "call +1 555 010 0142", "Exam Pad"), FILE_B).andExpect(status().isOk());

        StubAiModel.Call call = ai.calls().getFirst();
        assertThat(call.data()).isEqualTo("[{\"id\":0,\"fileA\":\"Exam Pad\",\"fileB\":\"Exam Ped\"}]");   // the one pair: no emails, no numbers, no quantities
        assertThat(call.instruction() + call.data()).doesNotContain(customer.email(), customer.token(), SECRET, "bob@example.test");
    }

    @Test
    void everyVariationIsPutToTheModelSoOneCannotBeMissedBecauseItDidNotThinkOfIt() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        theExampleAnswer();

        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk());

        // The application found the three pairs itself, from the two lists; the model is only asked to judge them.
        assertThat(ai.calls().getFirst().data()).isEqualTo("[{\"id\":0,\"fileA\":\"10th History Book\",\"fileB\":\"10th History Books\"},"
                + "{\"id\":1,\"fileA\":\"Exam Pad\",\"fileB\":\"Exam Ped\"},{\"id\":2,\"fileA\":\"10th Science Book\",\"fileB\":\"10th Senence Book\"}]");
    }

    @Test
    void theWholeFlowOfTheExactCaseFromTheFilesToTheSingleRowResult() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        // File A: History Book 6, Pen 6, Exam Pad 6, Science Book 5. File B: History Books 4, Exam Ped 2, Senence Book 3.
        String a = mvc.perform(as(customer, multipart(EXTRACT).file(csv("a.csv", "Item,Qty\nHistory Book,6\nPen,6\nExam Pad,6\nScience Book,5\n"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String b = mvc.perform(as(customer, multipart(EXTRACT).file(csv("b.csv", "Item,Qty\nHistory Books,4\nExam Ped,2\nSenence Book,3\n"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        List<String> namesA = JsonPath.read(a, "$[*].name");
        List<String> namesB = JsonPath.read(b, "$[*].name");

        // History Book/Books is pair 0, Exam Pad/Ped pair 1, Science Book/Senence Book pair 2: the model says all three, File A spelled right.
        theExampleAnswer();
        String suggestions = suggest(customer, namesA, namesB).andExpect(status().isOk()).andExpect(jsonPath("$.matches.length()").value(3))
                .andReturn().getResponse().getContentAsString();

        // What the browser sends when the customer accepts all three: each suggestion's from and to, as the compare request's nameMatches.
        List<String> from = JsonPath.read(suggestions, "$.matches[*].from");
        List<String> to = JsonPath.read(suggestions, "$.matches[*].to");
        List<String> accepted = new java.util.ArrayList<>();
        for (int i = 0; i < from.size(); i++) accepted.add("{\"from\":\"" + from.get(i) + "\",\"to\":\"" + to.get(i) + "\"}");

        compare(customer, "{\"referenceLabel\":\"File A\",\"referenceLines\":" + a + ",\"targetLabel\":\"File B\",\"targetLines\":" + b
                + ",\"nameMatches\":[" + String.join(",", accepted) + "]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[0].name").value("History Book")).andExpect(jsonPath("$.lines[0].referenceQuantity").value(6))
                .andExpect(jsonPath("$.lines[0].targetQuantity").value(4)).andExpect(jsonPath("$.lines[0].difference").value(-2))
                .andExpect(jsonPath("$.lines[0].status").value("MISMATCH"))
                .andExpect(jsonPath("$.lines[1].name").value("Pen")).andExpect(jsonPath("$.lines[1].status").value("MISSING_IN_TARGET"))
                .andExpect(jsonPath("$.lines[2].name").value("Exam Pad")).andExpect(jsonPath("$.lines[2].referenceQuantity").value(6))
                .andExpect(jsonPath("$.lines[2].targetQuantity").value(2)).andExpect(jsonPath("$.lines[2].difference").value(-4))
                .andExpect(jsonPath("$.lines[2].status").value("MISMATCH"))
                .andExpect(jsonPath("$.lines[3].name").value("Science Book")).andExpect(jsonPath("$.lines[3].referenceQuantity").value(5))
                .andExpect(jsonPath("$.lines[3].targetQuantity").value(3)).andExpect(jsonPath("$.lines[3].difference").value(-2))
                .andExpect(jsonPath("$.lines[3].status").value("MISMATCH"))
                .andExpect(jsonPath("$.summary.missingInTarget").value(1)).andExpect(jsonPath("$.summary.extraInTarget").value(0));
    }

    @Test
    void aProofOfHandoffPdfHasNothingButItemsAndQuantitiesInTheSuggestionsAndTheComparison() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        long sent = createDraft(customer, "School supplies sent", "{\"name\":\"10th History Book\",\"quantity\":6},{\"name\":\"Pen\",\"quantity\":6},"
                + "{\"name\":\"Exam Pad\",\"quantity\":6},{\"name\":\"10th Science Book\",\"quantity\":5}");
        long received = createDraft(customer, "School supplies received", "{\"name\":\"10th History Books\",\"quantity\":4},"
                + "{\"name\":\"Exam Ped\",\"quantity\":2},{\"name\":\"10th Senence Book\",\"quantity\":3}");

        // The two PDFs as the customer downloads them; they also carry the reference, the sender, the recipient, dates, a title and a footer.
        String a = extractPdf(customer, sent);
        String b = extractPdf(customer, received);
        assertThat((List<String>) JsonPath.read(a, "$[*].name")).containsExactly("10th History Book", "Pen", "Exam Pad", "10th Science Book");
        assertThat((List<String>) JsonPath.read(b, "$[*].name")).containsExactly("10th History Books", "Exam Ped", "10th Senence Book");

        theExampleAnswer();
        suggest(customer, JsonPath.read(a, "$[*].name"), JsonPath.read(b, "$[*].name")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matches.length()").value(3));
        compare(customer, "{\"referenceLines\":" + a + ",\"targetLines\":" + b + ",\"nameMatches\":" + ACCEPTED + "}").andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].difference").value(-4))
                .andExpect(jsonPath("$.lines[?(@.name=='Pen')].status").value("MISSING_IN_TARGET"));
    }

    private long createDraft(Account owner, String title, String items) throws Exception {
        String body = "{\"title\":\"" + title + "\",\"senderName\":\"Sender Person\",\"recipientName\":\"Recipient Person\","
                + "\"recipientEmail\":\"r@example.test\",\"items\":[" + items + "]}";
        return ((Number) JsonPath.read(mvc.perform(as(owner, post("/api/v1/handoffs").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(), "$.id")).longValue();
    }

    private String extractPdf(Account owner, long handoffId) throws Exception {
        byte[] pdf = mvc.perform(as(owner, get("/api/v1/handoffs/" + handoffId + "/pdf"))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsByteArray();
        return mvc.perform(as(owner, multipart(EXTRACT).file(file("proof.pdf", pdf)))).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
    }

    // ------------------------------------------------------------------ nothing broader than a spelling variation

    @Test
    void differentItemsAreNeverEvenPutToTheModel() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        theExampleAnswer();   // were it asked, it would say yes to anything

        suggest(customer, List.of("10th History Book", "Chair", "Laptop", "Pen", "Exam Pad"),
                List.of("10th Science Book", "Table", "Laptop Bag", "Pencil", "Exam Sheet"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.matches.length()").value(0));
        suggest(customer, List.of("Chair 1", "10th Science Book", "Pen 0.5"), List.of("Chair 2", "9th Science Book", "Pen 0.7"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches.length()").value(0));
        assertThat(ai.calls()).isEmpty();   // no candidate, so nothing was asked
    }

    @Test
    void aPairTheModelCallsDifferentIsNotOffered() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        answer(verdict(0, false, "A", 0.9), verdict(1, true, "A", 0.95), verdict(2, false, "B", 0.99));

        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.matches.length()").value(1))
                .andExpect(jsonPath("$.matches[0].from").value("Exam Ped"));
    }

    @Test
    void theModelCannotBringAPairOfItsOwnOrLeaveOneOut() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);

        // An id that was not asked is ignored; the three that were asked are judged.
        answer(verdict(0, true, "A", 0.9), verdict(1, true, "A", 0.9), verdict(2, true, "A", 0.9), verdict(3, true, "A", 0.99), verdict(-1, true, "A", 0.99));
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.matches.length()").value(3));

        // An answer that stops before it has judged every pair is unusable, not a shorter list.
        answer(verdict(0, true, "A", 0.9), verdict(1, true, "A", 0.9));
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.canRetry").value(true))
                .andExpect(jsonPath("$.matches.length()").value(0));
    }

    @Test
    void aChainOfSuggestionsIsNotOffered() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        // Pairs: 0 = (Exam Pad, Exam Ped), 1 = (Exam Pad, Exam Pod), 2 = (Exam Pod, Exam Ped).
        // The model: Ped is Pad misspelled (A correct), and Pad is Pod misspelled (B correct): Ped -> Pad -> Pod is a chain.
        answer(verdict(0, true, "A", 0.95), verdict(1, true, "B", 0.95), verdict(2, false, "A", 0.9));

        suggest(customer, List.of("Exam Pad", "Exam Pod"), List.of("Exam Ped", "Exam Pod"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.matches[?(@.from=='Exam Ped')]").isEmpty());
    }

    @Test
    void aLessSureSuggestionIsOfferedAsPossibleNotAsCertainAndOneBelowTheFloorIsNotOffered() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        answer(verdict(0, true, "A", 0.3), verdict(1, true, "A", 0.62), verdict(2, true, "A", 0.9));

        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.matches.length()").value(2))
                .andExpect(jsonPath("$.matches[?(@.from=='Exam Ped')].certain").value(false))
                .andExpect(jsonPath("$.matches[?(@.from=='10th Senence Book')].certain").value(true));
    }

    @Test
    void theModelNamesWhichFileSpelledItCorrectlyAndThatNameIsTheOneKept() throws Exception {
        Account customer = register(SubscriptionPlan.HALF_YEARLY);
        // The model says File B's "Exam Pad" is right and File A's "Exam Ped" the mistake.
        answer(verdict(0, true, "B", 0.95));

        suggest(customer, List.of("Exam Ped"), List.of("Exam Pad")).andExpect(status().isOk())
                .andExpect(jsonPath("$.matches[0].from").value("Exam Ped")).andExpect(jsonPath("$.matches[0].fromFile").value("File A"))
                .andExpect(jsonPath("$.matches[0].to").value("Exam Pad")).andExpect(jsonPath("$.matches[0].toFile").value("File B"));
    }

    // ------------------------------------------------------------------ nothing changes until the customer accepts

    private static final String LINES_A = "[{\"name\":\"10th History Book\",\"quantity\":6},{\"name\":\"Exam Pad\",\"quantity\":6},"
            + "{\"name\":\"10th Science Book\",\"quantity\":5},{\"name\":\"Pen\",\"quantity\":6}]";
    private static final String LINES_B = "[{\"name\":\"10th History Books\",\"quantity\":4},{\"name\":\"Exam Ped\",\"quantity\":2},"
            + "{\"name\":\"10th Senence Book\",\"quantity\":3}]";
    private static final String ACCEPTED = "[{\"from\":\"10th History Books\",\"to\":\"10th History Book\"},{\"from\":\"Exam Ped\",\"to\":\"Exam Pad\"},"
            + "{\"from\":\"10th Senence Book\",\"to\":\"10th Science Book\"}]";

    private String comparison(String nameMatches) {
        return "{\"referenceLabel\":\"File A\",\"referenceLines\":" + LINES_A + ",\"targetLabel\":\"File B\",\"targetLines\":" + LINES_B
                + (nameMatches == null ? "" : ",\"nameMatches\":" + nameMatches) + "}";
    }

    private ResultActions compare(Bearer who, String body) throws Exception {
        return mvc.perform(as(who, post(COMPARE).contentType(MediaType.APPLICATION_JSON).content(body)));
    }

    @Test
    void askingForSuggestionsChangesNothingAndRejectingThemLeavesTheOriginalNames() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        theExampleAnswer();
        String before = compare(customer, comparison(null)).andReturn().getResponse().getContentAsString();

        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk());
        // Rejected: the comparison is asked for as before, with no accepted matches, and is exactly what it was.
        compare(customer, comparison(null)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(7))
                .andExpect(jsonPath("$.summary.missingInTarget").value(4))
                .andExpect(jsonPath("$.summary.extraInTarget").value(3))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Ped')].status").value("EXTRA_IN_TARGET"));
        assertThat(compare(customer, comparison(null)).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        assertThat(ai.calls()).hasSize(1);   // the comparison itself never asks the AI
    }

    @Test
    void acceptedMatchesMakeOneRowPerItemWithBothQuantitiesAndTheDifference() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);

        compare(customer, comparison(ACCEPTED)).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(4))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].referenceQuantity").value(6))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].targetQuantity").value(2))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].difference").value(-4))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].status").value("MISMATCH"))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].targetName").value("Exam Ped"))
                .andExpect(jsonPath("$.lines[?(@.name=='10th Science Book')].difference").value(-2))
                .andExpect(jsonPath("$.lines[?(@.name=='10th History Book')].difference").value(-2))
                .andExpect(jsonPath("$.lines[?(@.name=='Pen')].status").value("MISSING_IN_TARGET"))
                .andExpect(jsonPath("$.summary.mismatched").value(3))
                .andExpect(jsonPath("$.summary.missingInTarget").value(1))
                .andExpect(jsonPath("$.summary.extraInTarget").value(0))
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Ped')]").isEmpty());
    }

    @Test
    void acceptingOnlySomeMatchesAppliesOnlyThose() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);

        compare(customer, comparison("[{\"from\":\"Exam Ped\",\"to\":\"Exam Pad\"}]")).andExpect(status().isOk())
                .andExpect(jsonPath("$.lines[?(@.name=='Exam Pad')].status").value("MISMATCH"))
                .andExpect(jsonPath("$.lines[?(@.name=='10th Senence Book')].status").value("EXTRA_IN_TARGET"))   // not accepted: still its own item
                .andExpect(jsonPath("$.lines[?(@.name=='10th Science Book')].status").value("MISSING_IN_TARGET"));
    }

    @Test
    void theSameAlignmentWorksWhateverTheFilesWereReadFrom() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        // File A is a CSV and File B an Excel file: the lines are read by the ordinary extraction, and the matches apply to those lines.
        String a = mvc.perform(as(customer, multipart(EXTRACT).file(csv("a.csv", "Item,Qty\nExam Pad,6\nPen,6\n"))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String b = mvc.perform(as(customer, multipart(EXTRACT).file(xlsx("b.xlsx", new String[]{"Item", "Qty"}, new String[]{"Exam Ped", "2"}))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();

        compare(customer, "{\"referenceLines\":" + a + ",\"targetLines\":" + b + ",\"nameMatches\":[{\"from\":\"Exam Ped\",\"to\":\"Exam Pad\"}]}")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()").value(2))
                .andExpect(jsonPath("$.lines[0].name").value("Exam Pad"))
                .andExpect(jsonPath("$.lines[0].difference").value(-4))
                .andExpect(jsonPath("$.lines[1].status").value("MISSING_IN_TARGET"));
    }

    @Test
    void theExportedFileShowsTheSameOneRowPerItemAndHowEachFileSpelledIt() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String body = "{\"fileAName\":\"a.csv\",\"fileBName\":\"b.csv\",\"comparison\":" + comparison(ACCEPTED) + "}";

        String csv = new String(mvc.perform(as(customer, post(EXPORT).param("format", "CSV").contentType(MediaType.APPLICATION_JSON).content(body)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray(), StandardCharsets.UTF_8);

        assertThat(csv).contains("Exam Pad (File B: Exam Ped)").contains("10th Science Book (File B: 10th Senence Book)")
                .contains("Different quantity,3").contains("Missing in File B,1").contains("Extra in File B,0");
        assertThat(csv).doesNotContain("Exam Ped,—").doesNotContain("\nExam Ped,");   // File B's spelling is not a row of its own
    }

    @Test
    void anAcceptedMatchIsOnlyEverAboutNamesAndTooManyOfThemAreRefused() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        compare(customer, comparison("[{\"from\":\"\",\"to\":\"Exam Pad\"}]")).andExpect(status().isBadRequest());
        compare(customer, comparison("[{\"from\":\"Exam Ped\"}]")).andExpect(status().isBadRequest());
        String many = "[" + String.join(",", java.util.stream.IntStream.range(0, 501).mapToObj(i -> "{\"from\":\"a" + i + "\",\"to\":\"b" + i + "\"}").toList()) + "]";
        compare(customer, comparison(many)).andExpect(status().isBadRequest());
    }

    // ------------------------------------------------------------------ when the AI does not help, HandoffCheck still works

    @Test
    void whenTheAiIsUnavailableTheAnswerSaysSoAndTheComparisonIsUnaffected() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        String before = compare(customer, comparison(null)).andReturn().getResponse().getContentAsString();

        ai.fail(Reason.UNAVAILABLE);
        suggest(customer, FILE_A, FILE_B).andExpect(status().isOk())
                .andExpect(jsonPath("$.available").value(false))
                .andExpect(jsonPath("$.message").value(AiMappingService.AI_UNAVAILABLE))
                .andExpect(jsonPath("$.canRetry").value(true))
                .andExpect(jsonPath("$.matches.length()").value(0));
        ai.fail(Reason.RATE_LIMITED);
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.message").value(AiMappingService.AI_RATE_LIMITED)).andExpect(jsonPath("$.canRetry").value(false));
        ai.fail(Reason.INVALID_RESPONSE);
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.available").value(false)).andExpect(jsonPath("$.canRetry").value(true));
        ai.answer("The names are the same.");   // not JSON at all
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.available").value(false));
        ai.reset();
        ai.configured(false);
        suggest(customer, FILE_A, FILE_B).andExpect(jsonPath("$.message").value(AiMappingService.AI_NOT_CONFIGURED)).andExpect(jsonPath("$.canRetry").value(false));

        assertThat(compare(customer, comparison(null)).andReturn().getResponse().getContentAsString()).isEqualTo(before);
        compare(customer, comparison(ACCEPTED)).andExpect(status().isOk()).andExpect(jsonPath("$.lines.length()").value(4));   // and the customer may still match by hand
    }

    @Test
    void withNothingToLookAtNoRequestIsMade() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);

        suggest(customer, List.of(), FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true)).andExpect(jsonPath("$.matches.length()").value(0));
        suggest(customer, List.of("ask bob@example.test"), FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.matches.length()").value(0));
        assertThat(ai.calls()).isEmpty();
    }

    @Test
    void anAnswerWithTheWrongShapeIsAnUnusableAnswerNotAnError() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        for (String bad : new String[]{"{}", "[]", "{\"verdicts\":\"Exam Ped is Exam Pad\"}", "{\"verdicts\":{}}", "{\"matches\":[]}"}) {
            ai.answer(bad);
            suggest(customer, FILE_A, FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(false));
        }
    }

    @Test
    void tooManyOrBlankNamesAreRefusedBeforeTheAi() throws Exception {
        Account customer = register(SubscriptionPlan.YEARLY);
        List<String> tooMany = java.util.stream.IntStream.range(0, 201).mapToObj(i -> "Item " + i).toList();

        suggest(customer, tooMany, FILE_B).andExpect(status().isBadRequest());
        suggest(customer, List.of(" "), FILE_B).andExpect(status().isBadRequest());
        mvc.perform(as(customer, post(MATCHING).contentType(MediaType.APPLICATION_JSON).content("{\"fileA\":[\"Pen\"]}"))).andExpect(status().isBadRequest());
        assertThat(ai.calls()).isEmpty();
    }

    // ------------------------------------------------------------------ who may use it

    @Test
    void itFollowsTheHandoffCheckPlanRules() throws Exception {
        theExampleAnswer();
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.MONTHLY, SubscriptionPlan.QUARTERLY)) {
            suggest(register(plan), FILE_A, FILE_B).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("plan_required"));
        }
        assertThat(ai.calls()).isEmpty();
        for (SubscriptionPlan plan : List.of(SubscriptionPlan.HALF_YEARLY, SubscriptionPlan.YEARLY)) {
            suggest(register(plan), FILE_A, FILE_B).andExpect(status().isOk()).andExpect(jsonPath("$.available").value(true));
        }
        assertThat(ai.calls()).hasSize(2);
    }

    @Test
    void anAccountWithNoPlanOrNoSignInIsTurnedAwayAndTheKeyIsNeverShown() throws Exception {
        suggest(registerWithoutPlan(), FILE_A, FILE_B).andExpect(status().isForbidden());
        mvc.perform(post(MATCHING).contentType(MediaType.APPLICATION_JSON).content("{\"fileA\":[],\"fileB\":[]}")).andExpect(status().isUnauthorized());
        assertThat(ai.calls()).isEmpty();

        Account customer = register(SubscriptionPlan.YEARLY);
        ai.fail(Reason.UNAVAILABLE);
        String body = suggest(customer, FILE_A, FILE_B).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(SECRET).doesNotContain("Exception").doesNotContain("google");
        assertThat((Boolean) JsonPath.read(body, "$.available")).isFalse();
    }
}

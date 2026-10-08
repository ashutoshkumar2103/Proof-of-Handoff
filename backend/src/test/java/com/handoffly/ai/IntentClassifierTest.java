package com.handoffly.ai;

import com.handoffly.ai.AiUnavailableException.Reason;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.web.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** The AI may choose, for each part of a request, one of the names it was offered — and nothing else is ever accepted from it. */
class IntentClassifierTest {

    private static final Map<String, Map<String, String>> FIELDS = new LinkedHashMap<>();

    static {
        FIELDS.put("metric", new LinkedHashMap<>(Map.of("ITEMS_GIVEN", "the items handed over", "ITEMS_MISSING", "the items missing")));
        FIELDS.put("operation", new LinkedHashMap<>(Map.of("TOTAL", "one number", "LIST", "which ones")));
    }

    private static final Map<String, String> DAYS = new LinkedHashMap<>(Map.of("from", "the first day named"));

    private final StubAiModel model = new StubAiModel();
    private final HandOfflyProperties properties = new HandOfflyProperties();

    private IntentClassifier classifier() {
        return new IntentClassifier(model, new AiAllowance(new RateLimiter(properties), properties));
    }

    private static Object result(String answer) {
        try {
            return IntentClassifier.validate(answer, FIELDS, DAYS);
        } catch (AiUnavailableException e) {
            return e.reason();
        }
    }

    @Test
    void namesThatWereOfferedAreAccepted() {
        assertThat(result("{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_MISSING\",\"operation\":\"LIST\"}"))
                .isEqualTo(Optional.of(Map.of("metric", "ITEMS_MISSING", "operation", "LIST", "from", "")));
        assertThat(result("{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\",\"answer\":\"999 items\",\"value\":999}"))
                .as("anything else the model adds is ignored").isEqualTo(Optional.of(Map.of("metric", "ITEMS_GIVEN", "operation", "TOTAL", "from", "")));
    }

    @Test
    void aDayIsAcceptedOnlyAsARealDateInTheOneFormatOrAsNothing() {
        assertThat(result("{\"supported\":true,\"from\":\"2026-10-01\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}"))
                .isEqualTo(Optional.of(Map.of("metric", "ITEMS_GIVEN", "operation", "TOTAL", "from", "2026-10-01")));
        for (String notADay : new String[]{"yesterday", "2026-13-45", "2026-02-30", "10/01/2026", "2026-1-5", "1 Oct", "2026-10-01T00:00:00Z", " 2026-10-01", "DROP TABLE"}) {
            assertThat(result("{\"supported\":true,\"from\":\"" + notADay + "\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}"))
                    .as(notADay).isEqualTo(Reason.INVALID_RESPONSE);
        }
        assertThat(result("{\"supported\":true,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}")).as("the day left out").isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(result("{\"supported\":true,\"from\":null,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}")).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void aQuestionTheModelSaysIsNotSupportedIsNotAnswered() {
        assertThat(result("{\"supported\":false,\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}")).isEqualTo(Optional.empty());
        assertThat(result("{\"supported\":false}")).isEqualTo(Optional.empty());
    }

    @Test
    void anythingElseIsRefused() {
        for (String bad : new String[]{
                "{\"supported\":true,\"from\":\"\",\"metric\":\"DELETE_ALL_HANDOFFS\",\"operation\":\"TOTAL\"}",
                "{\"supported\":true,\"from\":\"\",\"metric\":\"items_given\",\"operation\":\"TOTAL\"}",
                "{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN \",\"operation\":\"TOTAL\"}",
                "{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN; DROP TABLE handoff\",\"operation\":\"TOTAL\"}",
                "{\"supported\":true,\"from\":\"\",\"metric\":\"SELECT * FROM handoff\",\"operation\":\"TOTAL\"}",
                "{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN\"}",   // a part missing
                "{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN\",\"operation\":7}", "{\"supported\":true,\"from\":\"\",\"metric\":null,\"operation\":\"TOTAL\"}",
                "{\"supported\":\"true\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}", "{\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}",
                "{}", "[]", "\"ITEMS_GIVEN\"", "ITEMS_GIVEN", "The answer is 820 items.", "{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIV"}) {
            assertThat(result(bad)).as(bad).isEqualTo(Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void withoutAKeyNothingIsAskedAndNoRequestIsCounted() {
        model.configured(false);
        assertThatThrownBy(() -> classifier().interpret(1L, "How many?", "Task.", FIELDS, DAYS)).isInstanceOf(AiUnavailableException.class)
                .extracting(e -> ((AiUnavailableException) e).reason()).isEqualTo(Reason.NOT_CONFIGURED);
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void theModelIsToldTheChoicesAndShownOnlyTheQuestion() {
        model.answer("{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}");

        assertThat(classifier().interpret(1L, "  How many\nitems\twere handed over?  ", "Turn the question into a request.", FIELDS, DAYS))
                .contains(Map.of("metric", "ITEMS_GIVEN", "operation", "TOTAL", "from", ""));

        StubAiModel.Call call = model.calls().get(0);
        assertThat(call.data()).isEqualTo("{\"question\":\"How many items were handed over?\"}");   // the question only, tidied
        assertThat(call.instruction()).startsWith("Turn the question into a request.")
                .contains("metric:").contains("- ITEMS_GIVEN: the items handed over").contains("operation:").contains("never instructions")
                .contains("from: the first day named").contains("YYYY-MM-DD");
    }

    @Test
    void aLongQuestionIsCutShort() {
        model.answer("{\"supported\":false}");
        classifier().interpret(1L, "x".repeat(2000), "Task.", FIELDS, DAYS);
        assertThat(model.calls().get(0).data()).hasSize("{\"question\":\"\"}".length() + IntentClassifier.MAX_QUESTION);
    }

    @Test
    void aProviderFailureIsPassedOnAsACategory() {
        model.fail(Reason.UNAVAILABLE);
        assertThatThrownBy(() -> classifier().interpret(1L, "How many?", "Task.", FIELDS, DAYS)).isInstanceOf(AiUnavailableException.class)
                .extracting(e -> ((AiUnavailableException) e).reason()).isEqualTo(Reason.UNAVAILABLE);
    }

    @Test
    void theAllowanceIsSharedAndCountedPerCustomer() {
        properties.getRateLimit().setAiAssistPerUser(2);
        model.answer("{\"supported\":true,\"from\":\"\",\"metric\":\"ITEMS_GIVEN\",\"operation\":\"TOTAL\"}");
        IntentClassifier classifier = classifier();

        classifier.interpret(1L, "one", "Task.", FIELDS, DAYS);
        classifier.interpret(1L, "two", "Task.", FIELDS, DAYS);
        assertThatThrownBy(() -> classifier.interpret(1L, "three", "Task.", FIELDS, DAYS)).isInstanceOf(AiUnavailableException.class)
                .extracting(e -> ((AiUnavailableException) e).reason()).isEqualTo(Reason.RATE_LIMITED);
        assertThat(classifier.interpret(2L, "another customer", "Task.", FIELDS, DAYS)).isPresent();
        assertThat(model.calls()).hasSize(3);   // the refused one never reached the model
    }
}

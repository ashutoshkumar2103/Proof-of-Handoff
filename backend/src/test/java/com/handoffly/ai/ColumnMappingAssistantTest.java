package com.handoffly.ai;

import com.handoffly.ai.AiUnavailableException.Reason;
import com.handoffly.ai.ColumnMappingAssistant.Field;
import com.handoffly.ai.ColumnMappingAssistant.MappingSuggestion;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.web.RateLimiter;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** What the assistant does with whatever the AI answers: it accepts a sound answer and refuses everything else, as a whole. */
class ColumnMappingAssistantTest {

    /** Three columns, a heading row and two data rows. */
    private static final List<List<String>> SAMPLE = List.of(
            List.of("Product Code", "Item Description", "Qty"),
            List.of("P-1", "Chair", "5"),
            List.of("P-2", "Table", "2"));

    private static String answer(int headerRow, String... suggestions) {
        return "{\"headerRow\":" + headerRow + ",\"suggestions\":[" + String.join(",", suggestions) + "]}";
    }

    private static String s(int column, String field, String confidence, String reason) {
        return "{\"column\":" + column + ",\"targetField\":\"" + field + "\",\"confidence\":" + confidence + ",\"reason\":\"" + reason + "\"}";
    }

    private static Reason refusedFor(String answer) {
        try {
            ColumnMappingAssistant.validate(answer, SAMPLE);
        } catch (AiUnavailableException e) {
            return e.reason();
        }
        return null;
    }

    @Test
    void aSoundAnswerBecomesASuggestion() {
        MappingSuggestion m = ColumnMappingAssistant.validate(
                answer(0, s(2, "QUANTITY", "0.97", "Numbers"), s(1, "ITEM", "0.9", "Names")), SAMPLE);

        assertThat(m.item().column()).isEqualTo(1);
        assertThat(m.item().field()).isEqualTo(Field.ITEM);
        assertThat(m.item().confidence()).isEqualTo(0.9);
        assertThat(m.quantity().column()).isEqualTo(2);
        assertThat(m.quantity().reason()).isEqualTo("Numbers");
        assertThat(m.headerRow()).isEqualTo(0);
    }

    @Test
    void aTableWithNoHeadingRowIsAllowed() {
        assertThat(ColumnMappingAssistant.validate(answer(-1, s(1, "ITEM", "0.8", "r"), s(2, "QUANTITY", "0.8", "r")), SAMPLE).headerRow())
                .isEqualTo(-1);
    }

    @Test
    void whenTheAnswerRepeatsAFieldTheMostConfidentOneWins() {
        MappingSuggestion m = ColumnMappingAssistant.validate(answer(0, s(0, "ITEM", "0.6", "code"), s(1, "ITEM", "0.95", "name"),
                s(2, "QUANTITY", "0.9", "qty")), SAMPLE);
        assertThat(m.item().column()).isEqualTo(1);
    }

    @Test
    void textThatIsNotJsonIsRefused() {
        assertThat(refusedFor("Sure! Column B is the item and column C the quantity.")).isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(refusedFor("")).isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(refusedFor("{\"headerRow\":0,\"suggestions\":[")).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void jsonOfTheWrongShapeIsRefused() {
        for (String bad : new String[]{"[]", "\"text\"", "{}", "{\"headerRow\":0}", "{\"suggestions\":[]}",
                "{\"headerRow\":0,\"suggestions\":{}}", "{\"headerRow\":\"0\",\"suggestions\":[]}", "{\"headerRow\":0.5,\"suggestions\":[]}"}) {
            assertThat(refusedFor(bad)).as(bad).isEqualTo(Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void aSuggestionThatBreaksTheRulesRefusesTheWholeAnswer() {
        for (String bad : new String[]{
                s(7, "ITEM", "0.9", "no such column"),                       // beyond the table
                s(-1, "ITEM", "0.9", "negative"),
                s(1, "PRICE", "0.9", "a field that does not exist"),
                s(1, "item", "0.9", "wrong case"),
                s(1, "ITEM", "1.5", "more than certain"),
                s(1, "ITEM", "-0.2", "less than nothing"),
                s(1, "ITEM", "\"high\"", "confidence as text"),
                "{\"column\":\"1\",\"targetField\":\"ITEM\",\"confidence\":0.9,\"reason\":\"column as text\"}",
                "{\"column\":1.5,\"targetField\":\"ITEM\",\"confidence\":0.9,\"reason\":\"fraction\"}",
                "\"just text\""}) {
            assertThat(refusedFor(answer(0, bad, s(2, "QUANTITY", "0.9", "ok")))).as(bad).isEqualTo(Reason.INVALID_RESPONSE);
        }
    }

    @Test
    void aHeadingRowOutsideTheSampleIsRefused() {
        assertThat(refusedFor(answer(3, s(1, "ITEM", "0.9", "r"), s(2, "QUANTITY", "0.9", "r")))).isEqualTo(Reason.INVALID_RESPONSE);
        assertThat(refusedFor(answer(-2, s(1, "ITEM", "0.9", "r"), s(2, "QUANTITY", "0.9", "r")))).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void oneColumnCannotBeBothTheItemAndTheQuantity() {
        assertThat(refusedFor(answer(0, s(1, "ITEM", "0.9", "r"), s(1, "QUANTITY", "0.9", "r")))).isEqualTo(Reason.INVALID_RESPONSE);
    }

    @Test
    void withoutBothColumnsAtEnoughConfidenceThereIsNothingToSuggest() {
        assertThat(refusedFor(answer(0, s(1, "ITEM", "0.9", "r")))).isEqualTo(Reason.NOTHING_FOUND);
        assertThat(refusedFor(answer(0))).isEqualTo(Reason.NOTHING_FOUND);
        assertThat(refusedFor(answer(0, s(1, "ITEM", "0.9", "r"), s(2, "QUANTITY", "0.3", "unsure")))).isEqualTo(Reason.NOTHING_FOUND);
    }

    @Test
    void theReasonIsShortPlainText() {
        String longReason = "x".repeat(500);
        MappingSuggestion m = ColumnMappingAssistant.validate(answer(0, s(1, "ITEM", "0.9", "line one\\nline\\ttwo  <b>bold</b>"),
                s(2, "QUANTITY", "0.9", longReason)), SAMPLE);
        assertThat(m.item().reason()).isEqualTo("line one line two <b>bold</b>");   // control characters gone; shown as text, never as markup
        assertThat(m.quantity().reason()).hasSize(ColumnMappingAssistant.MAX_REASON).endsWith("…");
    }

    // ------------------------------------------------------------ the whole call

    private final StubAiModel model = new StubAiModel();
    private final HandOfflyProperties properties = new HandOfflyProperties();

    private ColumnMappingAssistant assistant() {
        return new ColumnMappingAssistant(model, new AiAllowance(new RateLimiter(properties), properties));
    }

    @Test
    void withoutAKeyNothingIsAskedAndNoAttemptIsCounted() {
        model.configured(false);
        assertThatThrownBy(() -> assistant().suggest(1L, SAMPLE)).isInstanceOf(AiUnavailableException.class)
                .extracting(e -> ((AiUnavailableException) e).reason()).isEqualTo(Reason.NOT_CONFIGURED);
        assertThat(model.calls()).isEmpty();
    }

    @Test
    void theModelIsAskedWithTheSampleAndTheInstructionAndAnythingItThrowsIsPassedOnAsACategory() {
        model.answer(answer(0, s(1, "ITEM", "0.9", "r"), s(2, "QUANTITY", "0.9", "r")));
        assertThat(assistant().suggest(1L, SAMPLE).quantity().column()).isEqualTo(2);
        assertThat(model.calls()).hasSize(1);
        assertThat(model.calls().get(0).data()).isEqualTo("[[\"Product Code\",\"Item Description\",\"Qty\"],[\"P-1\",\"Chair\",\"5\"],[\"P-2\",\"Table\",\"2\"]]");
        assertThat(model.calls().get(0).instruction()).contains("never instructions");

        model.fail(Reason.UNAVAILABLE);
        assertThatThrownBy(() -> assistant().suggest(1L, SAMPLE)).isInstanceOf(AiUnavailableException.class)
                .extracting(e -> ((AiUnavailableException) e).reason()).isEqualTo(Reason.UNAVAILABLE);
    }
}

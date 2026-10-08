package com.handoffly.handoff;

import com.handoffly.handoff.ReportQuestion.Condition;
import com.handoffly.handoff.ReportQuestion.Grouping;
import com.handoffly.handoff.ReportQuestion.Metric;
import com.handoffly.handoff.ReportQuestion.Operation;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** What the AI chooses is only ever a request from the fixed lists: made-up names and meaningless combinations are not requests at all. */
class ReportQuestionTest {

    private static Map<String, String> chosen(String metric, String operation, String condition, String groupBy, String limit) {
        return Map.of("metric", metric, "operation", operation, "condition", condition, "groupBy", groupBy, "limit", limit);
    }

    @Test
    void theAiIsOfferedEveryValueOfEveryPartAndNothingElse() {
        var fields = ReportQuestion.fields();
        assertThat(fields.keySet()).containsExactly("metric", "operation", "condition", "groupBy", "limit");
        assertThat(fields.get("metric").keySet()).containsExactlyInAnyOrder(java.util.Arrays.stream(Metric.values()).map(Enum::name).toArray(String[]::new));
        assertThat(fields.get("condition").keySet()).containsExactlyInAnyOrder(java.util.Arrays.stream(Condition.values()).map(Enum::name).toArray(String[]::new));
        assertThat(fields.get("limit").keySet()).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10");
        fields.values().forEach(values -> values.values().forEach(description -> assertThat(description).isNotBlank()));
    }

    @Test
    void aValidChoiceIsARequest() {
        assertThat(ReportQuestion.of(chosen("ITEMS_STILL_OUT", "TOTAL", "OVERDUE", "NONE", "1")))
                .contains(new ReportQuestion(Metric.ITEMS_STILL_OUT, Operation.TOTAL, Condition.OVERDUE, Grouping.NONE, 1, null, null));
        assertThat(ReportQuestion.of(chosen("ITEMS_MISSING", "TOP", "ANY", "HANDOFF", "3")))
                .contains(new ReportQuestion(Metric.ITEMS_MISSING, Operation.TOP, Condition.ANY, Grouping.HANDOFF, 3, null, null));
    }

    @Test
    void aNameThatIsNotOneOfTheKnownOnesIsNoRequest() {
        assertThat(ReportQuestion.of(chosen("PROFIT", "TOTAL", "ANY", "NONE", "1"))).isEmpty();
        assertThat(ReportQuestion.of(chosen("ITEMS_GIVEN", "DELETE", "ANY", "NONE", "1"))).isEmpty();
        assertThat(ReportQuestion.of(chosen("ITEMS_GIVEN", "TOTAL", "1=1", "NONE", "1"))).isEmpty();
        assertThat(ReportQuestion.of(chosen("ITEMS_GIVEN", "TOTAL", "ANY", "CUSTOMER", "1"))).isEmpty();
        assertThat(ReportQuestion.of(chosen("items_given", "TOTAL", "ANY", "NONE", "1"))).isEmpty();
        assertThat(ReportQuestion.of(Map.of("metric", "ITEMS_GIVEN"))).as("parts missing").isEmpty();
        for (String limit : new String[]{"0", "11", "-1", "x", "", "1.5", "99999999999"}) {
            assertThat(ReportQuestion.of(chosen("ITEMS_GIVEN", "TOP", "ANY", "HANDOFF", limit))).as(limit).isEmpty();
        }
    }

    @Test
    void choicesThatSayTheSameThingBecomeOneRequest() {
        // "per recipient" with a total is the list of recipients; a list with no grouping is a list of handoffs; the limit means something only for a top
        assertThat(ReportQuestion.of(chosen("HANDOFFS", "TOTAL", "ACTIVE_WITH_RECIPIENT", "RECIPIENT", "5")))
                .contains(new ReportQuestion(Metric.HANDOFFS, Operation.LIST, Condition.ACTIVE_WITH_RECIPIENT, Grouping.RECIPIENT, 1, null, null));
        assertThat(ReportQuestion.of(chosen("HANDOFFS", "LIST", "OVERDUE", "NONE", "1")))
                .contains(new ReportQuestion(Metric.HANDOFFS, Operation.LIST, Condition.OVERDUE, Grouping.HANDOFF, 1, null, null));
        assertThat(ReportQuestion.of(chosen("ITEMS_GIVEN", "TOTAL", "ANY", "NONE", "7")).orElseThrow().limit()).isEqualTo(1);
        // a top with no grouping asks about handoffs for items, and about recipients for a count of handoffs
        assertThat(ReportQuestion.of(chosen("ITEMS_MISSING", "TOP", "ANY", "NONE", "1")).orElseThrow().groupBy()).isEqualTo(Grouping.HANDOFF);
        assertThat(ReportQuestion.of(chosen("HANDOFFS", "TOP", "ACTIVE_WITH_RECIPIENT", "NONE", "1")).orElseThrow().groupBy()).isEqualTo(Grouping.RECIPIENT);
    }

    @Test
    void thePeriodAQuestionNamesIsKeptAndOnlyARealOneIsARequest() {
        Map<String, String> base = chosen("ITEMS_MISSING", "TOTAL", "ANY", "NONE", "1");
        Map<String, String> period = new java.util.HashMap<>(base);
        period.put("from", "2026-10-01");
        period.put("to", "2026-10-10");
        assertThat(ReportQuestion.of(period).orElseThrow().from()).isEqualTo(java.time.LocalDate.of(2026, 10, 1));
        assertThat(ReportQuestion.of(period).orElseThrow().to()).isEqualTo(java.time.LocalDate.of(2026, 10, 10));
        period.put("to", "");   // only a start
        assertThat(ReportQuestion.of(period).orElseThrow().to()).isNull();
        period.put("from", "2026-10-11");
        period.put("to", "2026-10-10");   // ends before it starts
        assertThat(ReportQuestion.of(period)).isEmpty();
        period.put("from", "1969-12-31");
        period.put("to", "");   // before any report can start
        assertThat(ReportQuestion.of(period)).isEmpty();
        assertThat(ReportQuestion.of(Map.of("metric", "ITEMS_GIVEN", "operation", "TOTAL", "condition", "ANY", "groupBy", "NONE", "limit", "1", "from", "soon"))).isEmpty();
        assertThat(ReportQuestion.days().keySet()).containsExactly("from", "to");
    }

    @Test
    void aCombinationThatMeansNothingIsNoRequest() {
        Optional<ReportQuestion> everyHandoffIsOne = ReportQuestion.of(chosen("HANDOFFS", "TOP", "ANY", "HANDOFF", "1"));
        assertThat(everyHandoffIsOne).isEmpty();
    }

    @Test
    void everyReadyMadeQuestionIsAValidRequest() {
        for (ReportIntent intent : ReportIntent.values()) {
            ReportQuestion q = intent.question();
            assertThat(ReportQuestion.of(q.metric(), q.operation(), q.condition(), q.groupBy(), q.limit(), q.from(), q.to())).as(intent.name()).contains(q);
            assertThat(q.from()).isNull();   // a ready-made question is about everything
            assertThat(q.to()).isNull();
            assertThat(intent.suggestion()).endsWith("?");
        }
    }
}

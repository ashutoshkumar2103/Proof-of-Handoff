package com.handoffly.handoff;

import com.handoffly.handoff.HandoffDraftResolver.Account;
import com.handoffly.handoff.HandoffDraftResolver.Recipient;
import com.handoffly.handoff.HandoffDraftResolver.Source;
import com.handoffly.handoff.dto.AssistantPreviewResponse;
import com.handoffly.handoff.dto.AssistantPreviewResponse.Status;
import com.handoffly.handoff.dto.HandoffTemplateResponse;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * How a request is turned into a draft: no AI, no database, so every rule can be shown on its own. A recipient and the items are taken exactly as the
 * customer said them; the customer's earlier handoffs are looked at only when the request itself points at one.
 */
class HandoffDraftResolverTest {

    private static final Account ME = new Account("Asha Rao", "Rao Traders");
    private static final Function<String, List<Source>> NO_SOURCES = ref -> List.of();

    private static HandoffIntent create(String recipient, HandoffIntent.Item... items) {
        return new HandoffIntent(true, recipient, "", "", "", false, false, "", "", "", "", List.of(items), List.of());
    }

    private static HandoffIntent.Item item(String name, String quantity) {
        return new HandoffIntent.Item(name, quantity, "");
    }

    private static AssistantPreviewResponse resolve(HandoffIntent intent, Function<String, List<Source>> sources, Map<String, String> choices) {
        return HandoffDraftResolver.resolve(intent, ME, sources, choices);
    }

    @Test
    void singularAndPluralAreTheSameItemButDifferentItemsAreNot() {
        // still used to find which copied item a quantity change means
        assertThat(HandoffDraftResolver.key("Markers")).isEqualTo(HandoffDraftResolver.key("marker"));
        assertThat(HandoffDraftResolver.key("Glasses")).isEqualTo(HandoffDraftResolver.key("glasses"));
        assertThat(HandoffDraftResolver.key("Batteries")).isEqualTo(HandoffDraftResolver.key("battery"));
        assertThat(HandoffDraftResolver.key("Blue pen")).isNotEqualTo(HandoffDraftResolver.key("pen"));
    }

    @Test
    void aNewRequestIsTakenExactlyAsSaidWithNoQuestionsAndNoWarningsAboutWhatIsNew() {
        AssistantPreviewResponse r = resolve(create("Avinash", item("books", "10"), item("rental papers", "2")), NO_SOURCES, null);

        assertThat(r.status()).isEqualTo(Status.READY);
        assertThat(r.clarifications()).isEmpty();
        assertThat(r.draft().recipientName()).isEqualTo("Avinash");
        assertThat(r.draft().items()).extracting(i -> i.name() + ":" + i.quantity()).containsExactly("books:10", "rental papers:2");
        assertThat(r.draft().senderName()).isEqualTo("Asha Rao");   // who the customer is, as the ordinary form fills in
        // nothing says the recipient or an item is new, close to something, or missing from earlier handoffs
        assertThat(r.notes()).extracting(n -> n.text()).noneMatch(t -> t.contains("earlier") || t.contains("close to") || t.contains("new recipient") || t.contains("as written"));
    }

    @Test
    void theItemsKeepTheCustomersOwnWordingUnitAndCase() {
        HandoffIntent intent = create("Priya Nair", new HandoffIntent.Item("Mario Game", "1", ""), new HandoffIntent.Item("Prince of Persia", "1", ""),
                new HandoffIntent.Item("rice", "25", "kg"), item("10th Science Book", "3"));

        AssistantPreviewResponse r = resolve(intent, NO_SOURCES, null);

        assertThat(r.draft().items()).extracting(i -> i.name() + ":" + i.quantity() + ":" + i.unit())
                .containsExactly("Mario Game:1:", "Prince of Persia:1:", "rice:25:kg", "10th Science Book:3:");
        assertThat(r.clarifications()).isEmpty();
    }

    @Test
    void whatTheRequestDoesNotGiveStaysEmptyWithOnlyUsefulNotes() {
        AssistantPreviewResponse r = resolve(create("Priya", item("notebook", "")), NO_SOURCES, null);

        assertThat(r.status()).isEqualTo(Status.READY);
        assertThat(r.draft().recipientEmail()).isEmpty();   // nothing is made up
        assertThat(r.draft().items().get(0).quantity()).isEmpty();
        assertThat(r.notes()).extracting(n -> n.field() + ":" + n.text())
                .anyMatch(t -> t.startsWith("recipientEmail:")).anyMatch(t -> t.startsWith("items:") && t.contains("A quantity is needed for: notebook"));
    }

    @Test
    void noHistoryIsLookedAtUnlessTheRequestNamesAHandoff() {
        AtomicInteger lookups = new AtomicInteger();
        Function<String, List<Source>> counting = ref -> { lookups.incrementAndGet(); return List.of(); };

        resolve(create("Rahul", item("pen", "2"), item("books", "10")), counting, null);
        resolve(new HandoffIntent(true, "Rahul", "", "", "", true, true, "", "", "", "", List.of(), List.of()), counting, null);   // copy flags without a reference

        assertThat(lookups).hasValue(0);
    }

    @Test
    void aDuplicateTakesOnlyWhatTheTemplateGivesAndOffersTheSourceRecipientOnRequest() {
        HandoffTemplateResponse template = new HandoffTemplateResponse("Stationery (copy)", "OFFICE", "Term supplies", "Asha Rao", "Rao Traders",
                List.of(new HandoffTemplateResponse.Item("Pens", new BigDecimal("10.000"), "pcs"), new HandoffTemplateResponse.Item("Markers", new BigDecimal("4"), "pcs")));
        Source source = new Source("AK-12", template, new Recipient("Priya Nair", "priya@example.test", ""));
        HandoffIntent intent = new HandoffIntent(true, "", "", "", "12", true, true, "", "", "", "", List.of(),
                List.of(new HandoffIntent.Change("marker", "10")));

        AssistantPreviewResponse r = resolve(intent, ref -> ref.equals("12") ? List.of(source) : List.of(), null);

        assertThat(r.status()).isEqualTo(Status.READY);
        assertThat(r.draft().title()).isEqualTo("Stationery (copy)");
        assertThat(r.draft().recipientName()).isEqualTo("Priya Nair");
        assertThat(r.draft().recipientEmail()).isEqualTo("priya@example.test");
        assertThat(r.draft().items()).extracting(i -> i.name() + ":" + i.quantity()).containsExactly("Pens:10", "Markers:10");   // the change reached the right item
        assertThat(r.toString()).doesNotContain("AK-12");   // the source's reference is nowhere in the new draft
    }

    @Test
    void aDuplicateWithItsOwnRecipientOrExtraItemsKeepsWhatTheCustomerSaid() {
        HandoffTemplateResponse template = new HandoffTemplateResponse("Stationery (copy)", null, null, "Asha Rao", null,
                List.of(new HandoffTemplateResponse.Item("Pens", new BigDecimal("10"), "pcs")));
        Source source = new Source("AK-12", template, new Recipient("Priya Nair", "priya@example.test", ""));
        HandoffIntent intent = new HandoffIntent(true, "Avinash", "", "", "12", true, false, "", "", "", "",
                List.of(item("rental papers", "2")), List.of());

        AssistantPreviewResponse r = resolve(intent, ref -> List.of(source), null);

        assertThat(r.draft().recipientName()).isEqualTo("Avinash");        // not the source's: the customer named someone
        assertThat(r.draft().recipientEmail()).isEmpty();                   // and nothing of the source's recipient came along
        assertThat(r.draft().items()).extracting(i -> i.name() + ":" + i.quantity()).containsExactly("Pens:10", "rental papers:2");
    }

    @Test
    void aSourceThatIsMissingOrAmbiguousIsAQuestionWithAWayOn() {
        HandoffTemplateResponse t = new HandoffTemplateResponse("A", null, null, "Asha Rao", null, List.of());
        Source one = new Source("AK-12", t, new Recipient("P", "p@example.test", ""));
        Source two = new Source("ZZ-12", t, new Recipient("Q", "q@example.test", ""));
        HandoffIntent intent = new HandoffIntent(true, "", "", "", "12", true, true, "", "", "", "", List.of(), List.of());

        AssistantPreviewResponse none = resolve(intent, NO_SOURCES, null);
        assertThat(none.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(none.clarifications().get(0).question()).contains("couldn't find");
        assertThat(none.clarifications().get(0).options()).extracting(o -> o.id()).containsExactly("skip");

        AssistantPreviewResponse both = resolve(intent, ref -> List.of(one, two), null);
        assertThat(both.clarifications().get(0).options()).extracting(o -> o.id()).containsExactly("AK-12", "ZZ-12", "skip");

        AssistantPreviewResponse picked = resolve(intent, ref -> List.of(one, two), Map.of("source", "ZZ-12"));
        assertThat(picked.status()).isEqualTo(Status.READY);
        assertThat(picked.draft().recipientName()).isEqualTo("Q");

        AssistantPreviewResponse forged = resolve(intent, ref -> List.of(one, two), Map.of("source", "not-offered"));
        assertThat(forged.status()).isEqualTo(Status.NEEDS_INPUT);   // a choice nobody offered is ignored
    }

    @Test
    void aChangeThatFitsSeveralItemsAsksWhichAndOneThatFitsNoneSaysSo() {
        HandoffTemplateResponse t = new HandoffTemplateResponse("A", null, null, "Asha Rao", null, List.of(
                new HandoffTemplateResponse.Item("Blue marker", new BigDecimal("2"), null), new HandoffTemplateResponse.Item("Red marker", new BigDecimal("3"), null)));
        Source s = new Source("AK-1", t, new Recipient("P", "p@example.test", ""));
        HandoffIntent ambiguous = new HandoffIntent(true, "", "", "", "1", true, false, "", "", "", "", List.of(), List.of(new HandoffIntent.Change("marker", "9")));
        HandoffIntent missing = new HandoffIntent(true, "", "", "", "1", true, false, "", "", "", "", List.of(), List.of(new HandoffIntent.Change("stapler", "9")));

        AssistantPreviewResponse asked = resolve(ambiguous, ref -> List.of(s), null);
        assertThat(asked.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(asked.draft().items()).extracting(i -> i.quantity()).containsExactly("2", "3");   // nothing is changed until they say which

        AssistantPreviewResponse answered = resolve(ambiguous, ref -> List.of(s),
                Map.of(asked.clarifications().get(0).id(), asked.clarifications().get(0).options().get(1).id()));
        assertThat(answered.draft().items()).extracting(i -> i.quantity()).containsExactly("2", "9");

        AssistantPreviewResponse none = resolve(missing, ref -> List.of(s), null);
        assertThat(none.status()).isEqualTo(Status.READY);
        assertThat(none.notes()).anyMatch(n -> n.text().contains("nothing was changed"));
    }

    @Test
    void aRequestToSaveTheDraftIsReportedButChangesNothingInTheDraftItself() {
        HandoffIntent plain = create("Rahul", item("charger", "1"), item("mobile stand", "1"), item("power bank", "1"));
        HandoffIntent withSave = new HandoffIntent(true, "Rahul", "", "", "", false, false, "", "", "", "",
                List.of(item("charger", "1"), item("mobile stand", "1"), item("power bank", "1")), List.of(), true);

        AssistantPreviewResponse a = resolve(plain, NO_SOURCES, null);
        AssistantPreviewResponse b = resolve(withSave, NO_SOURCES, null);

        assertThat(a.saveRequested()).isFalse();   // nothing was said about saving: the customer presses Save Draft
        assertThat(b.saveRequested()).isTrue();
        assertThat(b.draft()).isEqualTo(a.draft());   // the words about saving are not part of the draft
        assertThat(b.draft().items()).extracting(i -> i.name() + ":" + i.quantity()).containsExactly("charger:1", "mobile stand:1", "power bank:1");
        assertThat(b.status()).isEqualTo(Status.READY);
        // an open question is still reported as one: the page saves only when nothing is left to answer
        HandoffIntent askedAndSave = new HandoffIntent(true, "", "", "", "12", true, true, "", "", "", "", List.of(), List.of(), true);
        AssistantPreviewResponse asked = resolve(askedAndSave, NO_SOURCES, null);
        assertThat(asked.status()).isEqualTo(Status.NEEDS_INPUT);
        assertThat(asked.saveRequested()).isTrue();
        assertThat(resolve(HandoffIntent.notSupported(), NO_SOURCES, null).saveRequested()).isFalse();
    }

    @Test
    void theSaveRequestSurvivesBeingReadFromTheModelAndCleaned() {
        String base = "{\"supported\":true,\"recipientName\":\"Rahul\",\"recipientEmail\":\"\",\"recipientPhone\":\"\",\"sourceReference\":\"\",\"copyItemsFromSource\":false,"
                + "\"recipientFromSource\":false,\"title\":\"\",\"purpose\":\"\",\"category\":\"\",\"dueDate\":\"\",\"items\":[],\"changes\":[]";
        assertThat(HandoffIntent.fromModelJson(base + ",\"saveDraft\":true}").cleaned().saveDraft()).isTrue();
        assertThat(HandoffIntent.fromModelJson(base + ",\"saveDraft\":false}").cleaned().saveDraft()).isFalse();
        assertThat(HandoffIntent.fromModelJson(base + "}").cleaned().saveDraft()).isFalse();   // left out: not asked
        org.junit.jupiter.api.Assertions.assertThrows(com.handoffly.ai.AiUnavailableException.class,
                () -> HandoffIntent.fromModelJson(base + ",\"saveDraft\":\"yes\"}"));   // present but not true/false: unusable
        assertThat(HandoffIntent.schema().toString()).contains("saveDraft");
    }

    @Test
    void textThatIsNotAHandoffRequestIsDeclined() {
        AssistantPreviewResponse r = resolve(HandoffIntent.notSupported(), NO_SOURCES, null);

        assertThat(r.status()).isEqualTo(Status.UNSUPPORTED);
        assertThat(r.draft()).isNull();
    }

    @Test
    void aModelAnswerIsAcceptedOnlyInTheExactShapeAndBadNumbersBecomeEmpty() {
        String good = "{\"supported\":true,\"recipientName\":\"R\",\"recipientEmail\":\"\",\"recipientPhone\":\"\",\"sourceReference\":\"\",\"copyItemsFromSource\":false,"
                + "\"recipientFromSource\":false,\"title\":\"\",\"purpose\":\"\",\"category\":\"\",\"dueDate\":\"2026-13-45\",\"items\":[{\"name\":\"Pen\",\"quantity\":\"-3\",\"unit\":\"\"},"
                + "{\"name\":\"Ink\",\"quantity\":\"a few\",\"unit\":\"\"},{\"name\":\"Tape\",\"quantity\":\"2.5\",\"unit\":\"\"}],\"changes\":[]}";
        HandoffIntent clean = HandoffIntent.fromModelJson(good).cleaned();
        assertThat(clean.dueDate()).isEmpty();   // not a real day
        assertThat(clean.items()).extracting(HandoffIntent.Item::quantity).containsExactly("", "", "2.5");

        org.junit.jupiter.api.Assertions.assertThrows(com.handoffly.ai.AiUnavailableException.class, () -> HandoffIntent.fromModelJson("not json"));
        org.junit.jupiter.api.Assertions.assertThrows(com.handoffly.ai.AiUnavailableException.class, () -> HandoffIntent.fromModelJson("{\"supported\":true}"));
        org.junit.jupiter.api.Assertions.assertThrows(com.handoffly.ai.AiUnavailableException.class,
                () -> HandoffIntent.fromModelJson(good.replace("\"copyItemsFromSource\":false", "\"copyItemsFromSource\":\"yes\"")));
    }
}

package com.handoffly.handoff;

import com.handoffly.attachment.AttachmentKind;
import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.common.domain.ActorType;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemResponse;
import com.handoffly.returns.dto.ReturnEventResponse;
import com.handoffly.returns.dto.ReturnLineResponse;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The Proof-of-Handoff PDF is a concise customer document, not an audit export. The renderer is a
 * pure function of the detail response, so it is exercised directly (no database) and read back
 * with PDFBox. The fixtures always carry a full event history, which must never appear.
 */
class HandoffPdfServiceTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant DAY2 = T0.plusSeconds(86_400);

    private final HandoffPdfService service = new HandoffPdfService(null, null);

    // ------------------------------------------------------------------ Fixtures

    /** An item with no description or identifiers, so its table row reads cleanly. */
    private static HandoffItemResponse plainItem(int id, String name, int given, int returned, int missing, ItemCondition c) {
        return new HandoffItemResponse((long) id, name, null, null, null, null, null, c, null,
                BigDecimal.valueOf(given), BigDecimal.valueOf(returned), BigDecimal.valueOf(missing), BigDecimal.ZERO,
                BigDecimal.valueOf(given - returned - missing));
    }

    private static HandoffItemResponse item(int id, String name) {
        return new HandoffItemResponse((long) id, name, "Description of " + name, "SKU-" + id, "SN-" + id, "AS-" + id,
                "pcs", ItemCondition.GOOD, "Note for " + name, new BigDecimal("5.000"), new BigDecimal("5.000"),
                BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
    }

    private static ReturnEventResponse returnEvent(int n, Instant at, String note, boolean confirmed, ReturnLineResponse... lines) {
        return new ReturnEventResponse((long) n, at, ActorType.USER, "owner@example.com", note, confirmed,
                confirmed ? at.plusSeconds(60) : null, confirmed ? "owner@example.com" : null, at, List.of(lines));
    }

    private static ReturnEventResponse returnEvent(int n, Instant at, String note, ReturnLineResponse... lines) {
        return returnEvent(n, at, note, true, lines);
    }

    private static ReturnLineResponse line(int itemId, String name, String qty, ItemCondition c, String note) {
        return new ReturnLineResponse(1L, (long) itemId, name, new BigDecimal(qty), c, note);
    }

    private static final List<AuditEventResponse> HISTORY = List.of(
            new AuditEventResponse(1L, AuditEventType.HANDOFF_CREATED, ActorType.USER, "owner@example.com", "Handoff created as draft.", T0),
            new AuditEventResponse(2L, AuditEventType.RECIPIENT_LINK_SENT, ActorType.SYSTEM, null, "Secure review link emailed to client@example.com.", T0.plusSeconds(60)),
            new AuditEventResponse(3L, AuditEventType.RECIPIENT_OPENED, ActorType.RECIPIENT, "Event Client", "Recipient opened the review link.", T0.plusSeconds(120)),
            new AuditEventResponse(4L, AuditEventType.ATTACHMENT_ADDED, ActorType.USER, "owner@example.com", "Attachment added: photo.png.", T0.plusSeconds(180)),
            new AuditEventResponse(5L, AuditEventType.HANDOFF_DISPUTED, ActorType.USER, "owner@example.com", "Handoff marked as disputed.", T0.plusSeconds(240)),
            new AuditEventResponse(6L, AuditEventType.HANDOFF_CLOSED, ActorType.USER, "owner@example.com", "Handoff closed.", T0.plusSeconds(300)));

    /** Totals are derived from the items, as the backend does, so the fixtures stay consistent. */
    private static HandoffDetailResponse detail(HandoffStatus status, List<HandoffItemResponse> items,
                                                List<ReturnEventResponse> returns, List<AttachmentResponse> attachments,
                                                Instant missingConfirmedAt) {
        BigDecimal given = BigDecimal.ZERO, back = BigDecimal.ZERO, remaining = BigDecimal.ZERO, missing = BigDecimal.ZERO;
        for (HandoffItemResponse i : items) {
            given = given.add(i.outgoing());
            back = back.add(i.returnedConfirmed());
            remaining = remaining.add(i.remaining());
            missing = missing.add(i.missing());
        }
        return new HandoffDetailResponse(987654L, "HO-1042", "Wedding rentals", "Event on Saturday", "EVENT", status,
                "Rentals Co", "Rentals Pvt Ltd", "Event Client", "client@example.com", "+91 99999 00000",
                "Event Client", T0.plusSeconds(600), null, T0.plusSeconds(300), T0.plusSeconds(600), T0.plusSeconds(86400),
                T0, T0, given, back, remaining, missing, status == HandoffStatus.CLOSED, false, false, null,
                missingConfirmedAt, null, null, null, null, List.of(), items, returns, attachments, HISTORY);
    }

    private static HandoffDetailResponse closed(List<HandoffItemResponse> items, List<ReturnEventResponse> returns) {
        return detail(HandoffStatus.CLOSED, items, returns, List.of(), null);
    }

    // --------------------------------------------------------------------- Helpers

    /** Whitespace-normalised text, so wrapped lines compare predictably. */
    private static String text(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return new PDFTextStripper().getText(doc).replaceAll("\\s+", " ");
        }
    }

    private static int pages(byte[] pdf) throws IOException {
        try (PDDocument doc = Loader.loadPDF(pdf)) {
            return doc.getNumberOfPages();
        }
    }

    private static int count(String haystack, String needle) {
        return haystack.split(java.util.regex.Pattern.quote(needle), -1).length - 1;
    }

    /** Everything the redesign removed. A closed handoff's PDF must contain none of it. */
    private static void assertNoAuditOrStatusNoise(String t) {
        assertThat(t).doesNotContain("Important events", "IMPORTANT EVENTS", "Handoff created", "Handoff closed",
                "Review link", "Recipient opened", "Attachment added", "marked as disputed", "Return submitted",
                "Return confirmed", "Status", "FINAL RECORD", "Interim copy", "REMAINING", "Remaining",
                "legally binding", "Typed acknowledgement", "Generated", "Category", "Expected return",
                "Created", "owner@example.com", "client@example.com", "987654");
    }

    // ----------------------------------------------------------------------- Tests

    @Test
    void aSimpleHandoffIsOnePageWithTheItemsTableAndNoReturnSection() throws IOException {
        byte[] pdf = service.render(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT,
                List.of(plainItem(1, "Laptop", 1, 0, 0, ItemCondition.GOOD)), List.of(), List.of(), null));
        String t = text(pdf);

        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(t).contains("HandOffly Proof of Handoff", "Reference: HO-1042", "Wedding rentals",
                "GIVEN BY Rentals Co Rentals Pvt Ltd", "RECEIVED BY Event Client", "ACKNOWLEDGED BY Event Client",
                "DATE GIVEN 1 Oct 2026", "ITEM GIVEN RETURNED MISSING CONDITION NOTE", "Laptop 1 0 1 not yet returned 0 Good —",
                "TOTAL GIVEN 1 TOTAL RETURNED 0 TOTAL MISSING 0");
        assertThat(t).doesNotContain("RETURN SUMMARY", "Important events", "Handoff created", "Review link");
        // Not closed, so it says so once, quietly, and shows what is still out.
        assertThat(t).contains("Interim copy", "Active With Recipient", "1 not yet returned", "NOT YET RETURNED 1");
    }

    @Test
    void aFullyReturnedClosedHandoffIsOnePageAndContainsNoneOfTheRemovedContent() throws IOException {
        byte[] pdf = service.render(closed(
                List.of(plainItem(1, "Chairs", 5, 5, 0, ItemCondition.GOOD), plainItem(2, "Tables", 5, 5, 0, ItemCondition.GOOD)),
                List.of(returnEvent(1, T0.plusSeconds(3600), null,
                        line(1, "Chairs", "5", ItemCondition.GOOD, null), line(2, "Tables", "5", ItemCondition.GOOD, null)))));
        String t = text(pdf);

        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(t).contains("Chairs 5 5 0 Good —", "Tables 5 5 0 Good —",
                "TOTAL GIVEN 10 TOTAL RETURNED 10 TOTAL MISSING 0",
                "RETURN SUMMARY", "1 Oct 2026: 10 items returned.", "Page 1 of 1", "Dates in UTC");
        assertNoAuditOrStatusNoise(t);
        // Removing it from the PDF must not touch the data it came from.
        assertThat(HISTORY).hasSize(6);
    }

    @Test
    void missingItemsAreShownAsMissingNeverAsRemaining() throws IOException {
        List<ReturnEventResponse> returns = List.of(
                returnEvent(1, T0.plusSeconds(3600), null, line(1, "Steel Plate", "6", ItemCondition.GOOD, null)),
                returnEvent(2, DAY2, null, line(1, "Steel Plate", "1", ItemCondition.MISSING, null)));
        List<HandoffItemResponse> items = List.of(plainItem(1, "Steel Plate", 7, 6, 1, ItemCondition.GOOD),
                plainItem(2, "Steel Glass", 7, 7, 0, ItemCondition.GOOD));

        // Once the recipient has confirmed it...
        String confirmed = text(service.render(detail(HandoffStatus.CLOSED, items, returns, List.of(), DAY2)));
        assertThat(confirmed).contains("Steel Plate 7 6 1 Good —", "Steel Glass 7 7 0 Good —",
                "TOTAL GIVEN 14 TOTAL RETURNED 13 TOTAL MISSING 1",
                "1 Oct 2026: 6 items returned.", "2 Oct 2026: 1 Steel Plate confirmed missing.");
        assertNoAuditOrStatusNoise(confirmed);
        // ...and before it.
        assertThat(text(service.render(detail(HandoffStatus.CLOSED, items, returns, List.of(), null))))
                .contains("1 Steel Plate reported missing.").doesNotContain("confirmed missing");
    }

    @Test
    void anItemBroughtBackLaterIsNoLongerReportedMissing() throws IOException {
        // 2 went missing on day 1, 1 was brought back on day 2: only 1 is still missing.
        List<ReturnEventResponse> returns = List.of(
                returnEvent(1, T0.plusSeconds(3600), null, line(1, "Steel Plate", "5", ItemCondition.GOOD, null),
                        line(1, "Steel Plate", "2", ItemCondition.MISSING, "lost")),
                returnEvent(2, DAY2, null, line(1, "Steel Plate", "1", ItemCondition.GOOD, null)));
        String t = text(service.render(closed(List.of(plainItem(1, "Steel Plate", 7, 6, 1, ItemCondition.GOOD)), returns)));

        assertThat(t).contains("Steel Plate 7 6 1 Good Missing: lost", "1 Oct 2026: 5 items returned; 2 Steel Plate reported missing.",
                "2 Oct 2026: 1 item returned.");
    }

    @Test
    void damagedItemsKeepTheirConditionAndNote() throws IOException {
        List<ReturnEventResponse> returns = List.of(returnEvent(1, T0.plusSeconds(3600), null,
                line(1, "Bedsheet Set", "20", ItemCondition.DAMAGED, "stained"),
                line(1, "Bedsheet Set", "20", ItemCondition.GOOD, null),
                line(2, "Lamp", "2", ItemCondition.DAMAGED, "cracked shade")));
        String t = text(service.render(closed(
                List.of(plainItem(1, "Bedsheet Set", 40, 40, 0, ItemCondition.GOOD), plainItem(2, "Lamp", 2, 2, 0, ItemCondition.GOOD)),
                returns)));

        assertThat(t).contains("Bedsheet Set 40 40 0 Good 20, Damaged 20 Damaged: stained",
                "Lamp 2 2 0 Damaged Damaged: cracked shade");
    }

    @Test
    void manyItemsContinueTheTableOnFurtherPagesWithTheHeaderRepeated() throws IOException {
        List<HandoffItemResponse> items = IntStream.rangeClosed(1, 150)
                .mapToObj(i -> plainItem(i, "Item number " + i, 5, 5, 0, ItemCondition.GOOD)).toList();
        byte[] pdf = service.render(closed(items, List.of()));
        String t = text(pdf);
        int pages = pages(pdf);

        assertThat(pages).isGreaterThan(2);
        assertThat(t).contains("Item number 1 5 5 0", "Item number 150 5 5 0", "Page 1 of " + pages, "Page " + pages + " of " + pages);
        assertThat(count(t, "ITEM GIVEN RETURNED MISSING CONDITION NOTE")).isGreaterThanOrEqualTo(pages - 1);
        assertThat(t).doesNotContain("GIVE N", "RETURNE D", "CONDITIO N");   // headings never wrap mid-word
    }

    @Test
    void multipleReturnsAreSummarisedPerDayInOrderNotAsTables() throws IOException {
        List<ReturnEventResponse> returns = List.of(
                returnEvent(3, DAY2, "third", line(1, "Chairs", "1", ItemCondition.GOOD, null)),
                returnEvent(1, T0.plusSeconds(3000), "first", line(1, "Chairs", "2", ItemCondition.GOOD, null)),
                returnEvent(2, T0.plusSeconds(6000), "second", line(1, "Chairs", "3", ItemCondition.GOOD, null)));
        byte[] pdf = service.render(closed(List.of(plainItem(1, "Chairs", 6, 6, 0, ItemCondition.GOOD)), returns));
        String t = text(pdf);

        // Two returns on 1 Oct become one line; the next day is its own line; order is chronological.
        assertThat(count(t, "1 Oct 2026:")).isEqualTo(1);
        assertThat(t).contains("1 Oct 2026: 5 items returned.", "2 Oct 2026: 1 item returned.");
        assertThat(t.indexOf("1 Oct 2026: 5 items")).isLessThan(t.indexOf("2 Oct 2026: 1 item"));
        assertThat(t).contains("Note: first", "Note: second", "Note: third");
        assertThat(t).doesNotContain("QUANTITY", "Return 1", "Return 2");   // no repeated per-return tables
        assertThat(pages(pdf)).isEqualTo(1);
    }

    @Test
    void returnsStillAwaitingConfirmationAreNotCountedInTheReturnSummary() throws IOException {
        HandoffItemResponse pending = new HandoffItemResponse(1L, "Chairs", null, null, null, null, null, ItemCondition.GOOD, null,
                BigDecimal.valueOf(5), BigDecimal.valueOf(2), BigDecimal.ZERO, BigDecimal.valueOf(2), BigDecimal.valueOf(3));
        String t = text(service.render(detail(HandoffStatus.PARTIALLY_RETURNED, List.of(pending), List.of(
                returnEvent(1, T0.plusSeconds(3600), null, line(1, "Chairs", "2", ItemCondition.GOOD, null)),
                returnEvent(2, DAY2, "waiting", false, line(1, "Chairs", "2", ItemCondition.GOOD, null))), List.of(), null)));

        assertThat(t).contains("1 Oct 2026: 2 items returned.", "+2 awaiting confirmation", "3 not yet returned");
        assertThat(t).doesNotContain("2 Oct 2026", "Note: waiting");
    }

    @Test
    void longNotesWrapAndContinueWithoutLosingText() throws IOException {
        String words = IntStream.rangeClosed(1, 900).mapToObj(i -> "word" + i).reduce("", (a, b) -> a + " " + b).trim();
        HandoffItemResponse longNoted = new HandoffItemResponse(1L, "Chairs", words, null, null, null, null,
                ItemCondition.GOOD, words, BigDecimal.TEN, BigDecimal.TEN, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO);
        byte[] pdf = service.render(closed(List.of(longNoted),
                List.of(returnEvent(1, T0.plusSeconds(3600), words, line(1, "Chairs", "10", ItemCondition.GOOD, words)))));
        String t = text(pdf);

        assertThat(pages(pdf)).isGreaterThan(1);
        assertThat(t).contains("word1 ", "word450 ");
        assertThat(count(t, "word900")).isGreaterThanOrEqualTo(3);   // description, item note + return-line note, return note
    }

    @Test
    void missingOptionalFieldsStillRender() throws IOException {
        HandoffItemResponse bare = new HandoffItemResponse(1L, "Laptop", null, null, null, null, null, null, null,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ONE);
        HandoffDetailResponse d = new HandoffDetailResponse(1L, "HO-1", null, null, null, HandoffStatus.DRAFT,
                "Sender", null, "Recipient", "r@example.com", null, null, null, null, null, null, null, null, null,
                BigDecimal.ONE, BigDecimal.ZERO, BigDecimal.ONE, BigDecimal.ZERO, false, false, false,
                null, null, null, null, null, null, List.of(), List.of(bare), null, null, null);
        byte[] pdf = service.render(d);
        String t = text(pdf);

        assertThat(pages(pdf)).isEqualTo(1);
        assertThat(t).contains("Reference: HO-1", "GIVEN BY Sender", "RECEIVED BY Recipient",
                "ACKNOWLEDGED BY Not yet acknowledged", "DATE GIVEN —", "Laptop 1 0 1 not yet returned 0 — —", "Interim copy", "Draft");
        assertThat(t).doesNotContain("Purpose", "RETURN SUMMARY", "attached");
    }

    @Test
    void attachmentsAreOneCompactLineOfNamesWithNoMetadata() throws IOException {
        List<AttachmentResponse> attachments = List.of(
                new AttachmentResponse(1L, AttachmentKind.EVIDENCE, "photo.png", "image/png", 465_408, ActorType.USER, "o", T0),
                new AttachmentResponse(2L, AttachmentKind.EVIDENCE, "damage.jpg", "image/jpeg", 12_000, ActorType.USER, "o", T0),
                new AttachmentResponse(3L, AttachmentKind.REFERENCE_DOCUMENT, "quotation.pdf", "application/pdf", 20_480, ActorType.USER, "o", T0));
        String t = text(service.render(detail(HandoffStatus.CLOSED, List.of(plainItem(1, "Chairs", 5, 5, 0, ItemCondition.GOOD)),
                List.of(), attachments, null)));

        assertThat(t).contains("Evidence attached: photo.png, damage.jpg", "Reference documents: quotation.pdf");
        assertThat(t).doesNotContain("KB", "image/png", "application/pdf", "454", "Uploaded", "UPLOADED", "ATTACHMENTS");
        assertThat(text(service.render(closed(List.of(plainItem(1, "Chairs", 5, 5, 0, ItemCondition.GOOD)), List.of()))))
                .doesNotContain("attached", "Reference documents");
    }

    @Test
    void aDeclinedHandoffSaysDeclinedWithTheReason() throws IOException {
        HandoffDetailResponse base = detail(HandoffStatus.REJECTED, List.of(plainItem(1, "Chairs", 5, 0, 0, ItemCondition.GOOD)),
                List.of(), List.of(), null);
        HandoffDetailResponse declined = new HandoffDetailResponse(base.id(), base.publicCode(), base.title(), base.purpose(),
                base.category(), base.status(), base.senderName(), base.senderOrganization(), base.recipientName(),
                base.recipientEmail(), base.recipientPhone(), "Event Client", T0, "Quantity does not match",
                base.outgoingAt(), null, null, T0, T0, base.totalOutgoing(), base.totalReturned(), base.totalRemaining(),
                base.totalMissing(), false, false, false, null, null, null, null, null, null, List.of(), base.items(),
                List.of(), List.of(), HISTORY);
        String t = text(service.render(declined));

        assertThat(t).contains("DECLINED BY Event Client", "Reason: Quantity does not match", "Interim copy", "Rejected");
        assertThat(t).doesNotContain("ACKNOWLEDGED BY");
    }

    @Test
    void textOutsideTheStandardFontDoesNotBreakRendering() throws IOException {
        assertThat(text(service.render(closed(List.of(plainItem(1, "टेबल Table 😀", 1, 1, 0, ItemCondition.GOOD)), List.of()))))
                .contains("Table");
    }

    private static final List<String> HEADINGS = List.of("ITEMS", "SUMMARY", "RETURN SUMMARY");

    @Test
    void noSectionHeadingIsEverStrandedAtTheBottomOfAPage() throws IOException {
        // Varying the item count moves every heading through every possible position on the page.
        for (int n = 1; n <= 60; n++) {
            List<HandoffItemResponse> items = IntStream.rangeClosed(1, n)
                    .mapToObj(i -> plainItem(i, "Item " + i, 5, 5, 0, ItemCondition.GOOD)).toList();
            byte[] pdf = service.render(closed(items, List.of(
                    returnEvent(1, T0.plusSeconds(3600), "n1", line(1, "Item 1", "5", ItemCondition.GOOD, null)))));
            try (PDDocument doc = Loader.loadPDF(pdf)) {
                for (int page = 1; page < doc.getNumberOfPages(); page++) {   // the last page may end however it likes
                    PDFTextStripper stripper = new PDFTextStripper();
                    stripper.setSortByPosition(true);
                    stripper.setStartPage(page);
                    stripper.setEndPage(page);
                    List<String> lines = stripper.getText(doc).lines().map(String::trim).filter(l -> !l.isEmpty()).toList();
                    String lastBeforeFooter = lines.get(lines.size() - 2);   // the final line is the page footer
                    assertThat(HEADINGS).as("%d items, page %d ends with: %s", n, page, lastBeforeFooter)
                            .doesNotContain(lastBeforeFooter);
                }
            }
        }
    }

    @Test
    void filenamesAreDeterministicAndSafe() {
        assertThat(HandoffPdfService.filenameFor("HO-1042")).isEqualTo("HandOffly-HO-1042-Proof-of-Handoff.pdf");
        assertThat(HandoffPdfService.filenameFor("../ho 1;rm \"x\"\r\n"))
                .matches("HandOffly-[A-Za-z0-9._-]+-Proof-of-Handoff\\.pdf")
                .doesNotContain("/", " ", ";", "\"", "\n");
        assertThat(HandoffPdfService.filenameFor(null)).isEqualTo("HandOffly-Handoff-Proof-of-Handoff.pdf");
    }
}

package com.handoffly.documentcheck;

import com.handoffly.attachment.AttachmentKind;
import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.audit.AuditEventType;
import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.domain.ActorType;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.handoff.HandoffPdfService;
import com.handoffly.handoff.HandoffStatus;
import com.handoffly.handoff.ItemCondition;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemResponse;
import com.handoffly.returns.dto.ReturnEventResponse;
import com.handoffly.returns.dto.ReturnLineResponse;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockMultipartFile;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static com.handoffly.testsupport.TestDocuments.file;
import static com.handoffly.testsupport.TestDocuments.pdf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * HandoffCheck reading HandOffly's own Proof-of-Handoff PDF — rendered by the real {@link HandoffPdfService} — must give the items and their
 * given quantities and nothing else: the header, the parties, the dates, the summary, the footer and the page numbers are not items.
 */
class ProofOfHandoffPdfExtractionTest {

    private static final Instant T0 = Instant.parse("2026-10-01T10:00:00Z");
    private final HandoffPdfService pdfService = new HandoffPdfService(null, null);
    private final DocumentLineExtractor extractor = new DocumentLineExtractor(new HandOfflyProperties());

    // ------------------------------------------------------------------ fixtures

    private static HandoffItemResponse item(int id, String name, String description, String sku, String given, String returned, String missing) {
        BigDecimal g = new BigDecimal(given), r = new BigDecimal(returned), m = new BigDecimal(missing);
        return new HandoffItemResponse((long) id, name, description, sku, null, null, "pcs", ItemCondition.GOOD, null, g, r, m,
                BigDecimal.ZERO, g.subtract(r).subtract(m));
    }

    private static HandoffItemResponse plain(int id, String name, String given) {
        return item(id, name, null, null, given, "0", "0");
    }

    private static HandoffDetailResponse detail(HandoffStatus status, List<HandoffItemResponse> items, List<ReturnEventResponse> returns,
                                                List<AttachmentResponse> attachments) {
        BigDecimal given = BigDecimal.ZERO, back = BigDecimal.ZERO, remaining = BigDecimal.ZERO, missing = BigDecimal.ZERO;
        for (HandoffItemResponse i : items) {
            given = given.add(i.outgoing());
            back = back.add(i.returnedConfirmed());
            remaining = remaining.add(i.remaining());
            missing = missing.add(i.missing());
        }
        List<AuditEventResponse> history = List.of(new AuditEventResponse(1L, AuditEventType.HANDOFF_CREATED, ActorType.USER,
                "owner@example.com", "Handoff created as draft.", T0));
        return new HandoffDetailResponse(7L, "AK-12", "Education kit", "Term loan for the school", "OTHER", status,
                "Abhinay Kumar", "Kumar Education Pvt Ltd", "Event Client", "client@example.com", "+91 99999 00000",
                "Event Client", T0.plusSeconds(600), null, T0.plusSeconds(300), T0.plusSeconds(600), T0.plusSeconds(86400),
                T0, T0, given, back, remaining, missing, status == HandoffStatus.CLOSED, false, false, null,
                null, null, null, null, null, List.of(), items, returns, attachments, history);
    }

    private static HandoffDetailResponse detail(HandoffStatus status, List<HandoffItemResponse> items) {
        return detail(status, items, List.of(), List.of());
    }

    private List<DocumentLine> read(HandoffDetailResponse d) {
        return extractor.extract(file("proof.pdf", pdfService.render(d)));
    }

    private static List<String> names(List<DocumentLine> lines) {
        return lines.stream().map(DocumentLine::name).toList();
    }

    private static List<String> quantities(List<DocumentLine> lines) {
        return lines.stream().map(l -> l.quantity().stripTrailingZeros().toPlainString()).toList();
    }

    // ------------------------------------------------------------------ the document's own text is never an item

    @Test
    void onlyTheItemsAndTheirGivenQuantitiesAreReadFromAFullProofOfHandoff() {
        List<HandoffItemResponse> items = List.of(
                item(1, "Chairs", "Blue plastic", "CH-1", "10", "4", "0"),
                item(2, "Study tables", null, null, "2", "0", "0"),
                item(3, "Whiteboard markers (black)", "Box of 12", "WM-9", "24", "10", "2"));
        List<ReturnEventResponse> returns = List.of(new ReturnEventResponse(1L, T0.plusSeconds(90_000), ActorType.USER, "owner@example.com",
                "Brought back by the driver", true, T0.plusSeconds(90_060), "owner@example.com", T0,
                List.of(new ReturnLineResponse(1L, 1L, "Chairs", new BigDecimal("4"), ItemCondition.GOOD, "two scuffed"))));
        List<AttachmentResponse> attachments = List.of(new AttachmentResponse(1L, AttachmentKind.EVIDENCE, "delivery-photo.png", "image/png", 1024L,
                ActorType.USER, "owner@example.com", T0));

        List<DocumentLine> lines = read(detail(HandoffStatus.PARTIALLY_RETURNED, items, returns, attachments));

        assertThat(names(lines)).containsExactly("Chairs", "Study tables", "Whiteboard markers (black)");
        assertThat(quantities(lines)).containsExactly("10", "2", "24");   // what was given: not the returned (4) or the not-yet-returned (6)
        // None of the document's other text became an item.
        for (String notAnItem : new String[]{"Reference", "AK-12", "Abhinay", "Kumar", "Event Client", "Oct", "2026", "Page", "HandOffly",
                "Proof of Handoff", "Interim copy", "Purpose", "Term loan", "Total", "Summary", "Blue plastic", "CH-1", "Box of 12", "delivery-photo"}) {
            assertThat(names(lines)).noneMatch(n -> n.contains(notAnItem));
        }
    }

    @Test
    void theStatusOfTheHandoffMakesNoDifference() {
        List<HandoffItemResponse> items = List.of(plain(1, "Projector", "1"), plain(2, "Screen", "1"));
        for (HandoffStatus status : new HandoffStatus[]{HandoffStatus.DRAFT, HandoffStatus.AWAITING_RECIPIENT, HandoffStatus.ACTIVE_WITH_RECIPIENT,
                HandoffStatus.PARTIALLY_RETURNED, HandoffStatus.CLOSED, HandoffStatus.REJECTED, HandoffStatus.CANCELLED}) {
            List<DocumentLine> lines = read(detail(status, items));
            assertThat(names(lines)).as(status.name()).containsExactly("Projector", "Screen");
            assertThat(quantities(lines)).as(status.name()).containsExactly("1", "1");
        }
    }

    @Test
    void itemsThatFollowEachOtherWithNoDescriptionAreStillOneRowEach() {
        List<HandoffItemResponse> items = IntStream.rangeClosed(1, 8).mapToObj(i -> plain(i, "Item number " + i, String.valueOf(i * 3))).toList();

        List<DocumentLine> lines = read(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT, items));

        assertThat(names(lines)).containsExactlyElementsOf(items.stream().map(HandoffItemResponse::name).toList());
        assertThat(quantities(lines)).containsExactly("3", "6", "9", "12", "15", "18", "21", "24");
    }

    @Test
    void theShortestRowsOfAClosedHandoffAreStillOneRowEach() {
        // Everything came back, so no row has a second line in its Returned cell: the rows are as close together as they get.
        List<HandoffItemResponse> items = IntStream.rangeClosed(1, 12).mapToObj(i -> item(i, "Returned item " + i, null, null, String.valueOf(i), String.valueOf(i), "0")).toList();

        List<DocumentLine> lines = read(detail(HandoffStatus.CLOSED, items));

        assertThat(names(lines)).containsExactlyElementsOf(items.stream().map(HandoffItemResponse::name).toList());
        assertThat(quantities(lines)).containsExactly("1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    }

    @Test
    void aLongNameThatWrapsIsOneItemAndItsDescriptionIsNotPartOfIt() {
        String longName = "Extra large conference table with folding legs and a very long catalogue name that wraps onto a second line";
        List<HandoffItemResponse> items = List.of(
                item(1, longName, "Walnut finish, seats ten, delivered flat packed with all of its fittings in a separate box", "TB-77", "3", "0", "0"),
                plain(2, "Chair", "30"));

        List<DocumentLine> lines = read(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT, items));

        assertThat(names(lines)).containsExactly(longName, "Chair");
        assertThat(quantities(lines)).containsExactly("3", "30");
    }

    @Test
    void aLongListThatRunsOverSeveralPagesIsReadInFull() {
        List<HandoffItemResponse> items = IntStream.rangeClosed(1, 70)
                .mapToObj(i -> item(i, "Part " + i, i % 3 == 0 ? "Spare, boxed" : null, i % 5 == 0 ? "SKU-" + i : null, String.valueOf(i), "0", "0")).toList();
        byte[] bytes = pdfService.render(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT, items));

        List<DocumentLine> lines = extractor.extract(file("long.pdf", bytes));

        assertThat(names(lines)).containsExactlyElementsOf(items.stream().map(HandoffItemResponse::name).toList());
        assertThat(quantities(lines)).containsExactlyElementsOf(items.stream().map(i -> i.outgoing().toPlainString()).toList());
    }

    @Test
    void namesWithFiguresAndSymbolsAndDecimalQuantitiesComeThroughAsTheyAre() {
        List<HandoffItemResponse> items = List.of(plain(1, "2x4 lumber (3 m)", "40"), plain(2, "Cable CAT-6, 5 m", "12"), plain(3, "Paint 20 L", "2.5"));

        List<DocumentLine> lines = read(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT, items));

        assertThat(names(lines)).containsExactly("2x4 lumber (3 m)", "Cable CAT-6, 5 m", "Paint 20 L");
        assertThat(quantities(lines)).containsExactly("40", "12", "2.5");
    }

    @Test
    void aProofOfHandoffWithNoItemsGivesNoRowsRatherThanItsHeaderText() {
        assertThatThrownBy(() -> read(detail(HandoffStatus.DRAFT, List.of()))).isInstanceOf(BadRequestException.class)
                .hasMessageContaining("No item and quantity rows");
    }

    // ------------------------------------------------------------------ any other PDF: document text is still never an item

    private static MockMultipartFile otherPdf(String... lines) {
        return pdf("other.pdf", lines);
    }

    @Test
    void headersDatesPageMarkersFootersAndLabelledFieldsInAnyPdfAreNotItems() {
        List<DocumentLine> lines = extractor.extract(otherPdf(
                "Reference: HO-1", "Recipient: Event Client", "Date given 8 Oct 2026", "Delivered on 08/10/2026",
                "Table 4", "Chairs: 12", "Curtain x 2", "Joker Dress - 3",
                "Page 1 of 1", "Acme Rentals · AK-1 · Quotation · Page 1 of 1", "Total 4", "36 4 0 32"));

        assertThat(names(lines)).containsExactly("Table", "Chairs", "Curtain", "Joker Dress", "Total");
        assertThat(quantities(lines)).containsExactly("4", "12", "2", "3", "4");
    }

    @Test
    void aPdfOfNothingButDocumentTextHasNoItems() {
        assertThatThrownBy(() -> extractor.extract(otherPdf("Reference: AK-1", "Prepared for Kumar", "Issued 3 Oct 2026", "Page 1 of 2")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void aCodeJoinedToANumberByAHyphenIsNotAnItemAndItsQuantity() {
        assertThatThrownBy(() -> extractor.extract(otherPdf("HO-1", "AK-15", "Ref HO-7")))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void theFiguresAreWhatTheHandoffGaveAcrossTwoDifferentHandoffsToo() {
        List<DocumentLine> a = read(detail(HandoffStatus.CLOSED, List.of(item(1, "Laptop", null, null, "5", "5", "0"))));
        List<DocumentLine> b = read(detail(HandoffStatus.ACTIVE_WITH_RECIPIENT, List.of(item(1, "Laptop", null, null, "5", "0", "0"))));
        assertThat(quantities(a)).isEqualTo(quantities(b)).containsExactly("5");
    }
}

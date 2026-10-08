package com.handoffly.handoff;

import com.handoffly.attachment.AttachmentKind;
import com.handoffly.attachment.dto.AttachmentResponse;
import com.handoffly.common.error.ApiException;
import com.handoffly.handoff.dto.EmailPdfResponse;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemResponse;
import com.handoffly.notification.NotificationService;
import com.handoffly.returns.dto.ReturnEventResponse;
import com.handoffly.returns.dto.ReturnLineResponse;
import org.openpdf.text.Chunk;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.Rectangle;
import org.openpdf.text.pdf.BaseFont;
import org.openpdf.text.pdf.PdfContentByte;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfPageEventHelper;
import org.openpdf.text.pdf.PdfTemplate;
import org.openpdf.text.pdf.PdfWriter;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.awt.Color;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/**
 * Renders the concise, customer-facing Proof-of-Handoff document: who gave, who received, what was
 * given, returned and missing, in what condition, plus any important notes. It is deliberately not
 * an audit export — the full history stays in the application. Generated on demand from the same
 * server-side data the detail page shows ({@link HandoffService#getDetail}, which also enforces
 * ownership); nothing is stored. {@link #render} is a pure function of the detail response so the
 * layout can be tested without a database.
 */
@Service
public class HandoffPdfService {

    private static final Logger log = LoggerFactory.getLogger(HandoffPdfService.class);

    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final String NONE = "—";
    /** Room a text-only section (heading plus one line) needs to start on the current page. */
    private static final float MIN_SECTION_SPACE = 80f;
    /** Height a section heading takes, including its spacing. */
    private static final float HEADING_HEIGHT = 36f;

    private static final Color PRIMARY = new Color(0x25, 0x63, 0xEB);
    private static final Color INK = new Color(0x1A, 0x20, 0x27);
    private static final Color MUTED = new Color(0x6B, 0x72, 0x80);
    private static final Color BORDER = new Color(0xD9, 0xDE, 0xE5);
    private static final Color PANEL = new Color(0xF1, 0xF5, 0xF9);
    private static final Color WARNING = new Color(0xB4, 0x53, 0x09);
    private static final Color DANGER = new Color(0xB9, 0x1C, 0x1C);

    private static final Font BRAND = new Font(Font.HELVETICA, 20, Font.BOLD, PRIMARY);
    private static final Font TITLE = new Font(Font.HELVETICA, 13, Font.BOLD, INK);
    private static final Font SECTION = new Font(Font.HELVETICA, 10, Font.BOLD, PRIMARY);
    private static final Font BODY = new Font(Font.HELVETICA, 9, Font.NORMAL, INK);
    private static final Font BODY_BOLD = new Font(Font.HELVETICA, 9, Font.BOLD, INK);
    private static final Font SMALL = new Font(Font.HELVETICA, 8, Font.NORMAL, MUTED);
    private static final Font HEAD = new Font(Font.HELVETICA, 7, Font.BOLD, MUTED);
    private static final Font ALERT = new Font(Font.HELVETICA, 9, Font.BOLD, DANGER);

    private final HandoffService handoffService;
    private final NotificationService notificationService;

    public HandoffPdfService(HandoffService handoffService, NotificationService notificationService) {
        this.handoffService = handoffService;
        this.notificationService = notificationService;
    }

    /** A rendered PDF and the safe filename to present it under. */
    public record PdfFile(String filename, byte[] content) {}

    /** Generates the PDF for a handoff the user owns. */
    public PdfFile generate(Long userId, Long handoffId) {
        return toFile(handoffService.getDetail(userId, handoffId));
    }

    /**
     * Emails the PDF as an attachment — only ever on an explicit request. Sends to {@code to}
     * when given, otherwise to the handoff's recipient.
     * @return the address used, and whether a real email was actually delivered
     */
    public EmailPdfResponse emailPdf(Long userId, Long handoffId, String to) {
        HandoffDetailResponse detail = handoffService.getDetail(userId, handoffId);
        String destination = to == null || to.isBlank() ? detail.recipientEmail() : to.trim();
        PdfFile file = toFile(detail);
        try {
            boolean delivered = notificationService.sendHandoffPdf(destination, detail.recipientName(),
                    detail.senderName(), detail.publicCode(), detail.title(), file.filename(), file.content());
            return new EmailPdfResponse(destination, delivered);
        } catch (RuntimeException e) {
            log.error("Could not email the Proof-of-Handoff PDF for {}", detail.publicCode(), e);
            throw new ApiException(HttpStatus.BAD_GATEWAY, "email_failed",
                    "The email could not be sent. Check the mail configuration and try again.");
        }
    }

    /** Deterministic, filesystem-safe name derived only from the public code. */
    static String filenameFor(String publicCode) {
        return filenameFor(publicCode, "pdf");
    }

    /** The same name for another format of the same record (the spreadsheet). */
    static String filenameFor(String publicCode, String extension) {
        String code = publicCode == null ? "" : publicCode.replaceAll("[^A-Za-z0-9._-]+", "-")
                .replaceAll("^-+|-+$", "");
        return "HandOffly-" + (code.isEmpty() ? "Handoff" : code) + "-Proof-of-Handoff." + extension;
    }

    private PdfFile toFile(HandoffDetailResponse detail) {
        return new PdfFile(filenameFor(detail.publicCode()), render(detail));
    }

    // ------------------------------------------------------------------ Rendering

    public byte[] render(HandoffDetailResponse d) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        Document document = new Document(PageSize.A4, 40, 40, 40, 50);
        try {
            PdfWriter writer = PdfWriter.getInstance(document, out);
            writer.setPageEvent(new Footer(d.publicCode()));
            document.addTitle("Proof of Handoff — " + d.publicCode());
            document.addSubject(clean(d.title()));
            document.addAuthor("HandOffly");
            document.addCreator("HandOffly");
            document.open();

            header(document, d);
            parties(document, d);
            items(document, writer, d);
            summary(document, writer, d);
            returnSummary(document, writer, d);
            attachments(document, d);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not render the Proof-of-Handoff PDF.", e);
        } finally {
            if (document.isOpen()) document.close();
        }
        return out.toByteArray();
    }

    private static void header(Document doc, HandoffDetailResponse d) throws DocumentException {
        PdfPTable bar = new PdfPTable(new float[]{3, 2});
        bar.setWidthPercentage(100);

        PdfPCell left = plain();
        left.addElement(new Paragraph("HandOffly", BRAND));
        left.addElement(new Paragraph("Proof of Handoff", TITLE));
        bar.addCell(left);

        PdfPCell right = plain();
        right.addElement(rightAligned("Reference: " + clean(d.publicCode()), BODY_BOLD));
        if (!isBlank(d.title())) right.addElement(rightAligned(clean(d.title()), BODY));
        bar.addCell(right);
        bar.setSpacingAfter(4);
        doc.add(bar);

        PdfPTable rule = new PdfPTable(1);
        rule.setWidthPercentage(100);
        PdfPCell line = new PdfPCell(new Phrase(" ", SMALL));
        line.setBorder(Rectangle.BOTTOM);
        line.setBorderColor(PRIMARY);
        line.setBorderWidth(1.5f);
        line.setFixedHeight(3);
        rule.addCell(line);
        doc.add(rule);

        // A closed handoff needs no status. Anything else is flagged once, quietly, so an in-progress
        // copy can never be mistaken for the final record.
        if (d.status() != HandoffStatus.CLOSED) {
            Paragraph note = new Paragraph("Interim copy — this handoff is not closed (status: "
                    + humanize(d.status().name()) + "); figures may still change.", new Font(Font.HELVETICA, 8, Font.NORMAL, WARNING));
            note.setSpacingBefore(6);
            doc.add(note);
        }
        if (!isBlank(d.purpose())) {
            Paragraph purpose = new Paragraph("Purpose: " + clean(d.purpose()), SMALL);
            purpose.setSpacingBefore(6);
            doc.add(purpose);
        }
    }

    private static void parties(Document doc, HandoffDetailResponse d) throws DocumentException {
        boolean declined = d.status() == HandoffStatus.REJECTED;
        PdfPTable t = new PdfPTable(new float[]{1.4f, 1.3f, 1.3f, 1f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(12);
        t.setKeepTogether(true);
        t.addCell(partyCell("Given by", d.senderName(), d.senderOrganization()));
        t.addCell(partyCell("Received by", d.recipientName(), null));
        t.addCell(partyCell(declined ? "Declined by" : "Acknowledged by",
                isBlank(d.acknowledgementName()) ? "Not yet acknowledged" : d.acknowledgementName(), null));
        t.addCell(partyCell("Date given", day(d.outgoingAt()), null));
        doc.add(t);
        if (declined && !isBlank(d.rejectionReason())) {
            Paragraph reason = new Paragraph("Reason: " + clean(d.rejectionReason()), BODY);
            reason.setSpacingBefore(4);
            doc.add(reason);
        }
    }

    private static void items(Document doc, PdfWriter w, HandoffDetailResponse d) throws DocumentException {
        Map<Long, HandoffItemResponse> byId = new HashMap<>();
        orEmpty(d.items()).forEach(i -> byId.put(i.id(), i));
        Map<Long, ItemReturns> returned = confirmedReturns(d, byId);

        PdfPTable t = new PdfPTable(new float[]{3.2f, 1.1f, 1.5f, 1.2f, 1.9f, 2.9f});
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setHeaderRows(1);
        for (String h : new String[]{"Item", "Given", "Returned", "Missing", "Condition", "Note"}) {
            t.addCell(headCell(h, h.equals("Given") || h.equals("Returned") || h.equals("Missing")
                    ? Element.ALIGN_RIGHT : Element.ALIGN_LEFT));
        }
        if (byId.isEmpty()) {
            PdfPCell none = bodyCell("No items.", BODY);
            none.setColspan(6);
            t.addCell(none);
        }
        for (HandoffItemResponse i : orEmpty(d.items())) {
            ItemReturns ir = returned.get(i.id());
            PdfPCell name = bodyCell(null, BODY);
            name.addElement(new Paragraph(clean(i.name()), BODY_BOLD));
            if (!isBlank(i.description())) name.addElement(new Paragraph(clean(i.description()), SMALL));
            String ids = identifiers(i);
            if (!ids.isEmpty()) name.addElement(new Paragraph(ids, SMALL));
            t.addCell(name);

            t.addCell(numCell(qty(i.outgoing()), BODY));
            // Only an unfinished handoff has anything beyond returned/missing to say here.
            PdfPCell back = bodyCell(null, BODY);
            back.addElement(rightAligned(qty(i.returnedConfirmed()), BODY));
            if (signum(i.returnedPending()) > 0) back.addElement(rightAligned("+" + qty(i.returnedPending()) + " awaiting confirmation", SMALL));
            if (signum(i.remaining()) > 0) back.addElement(rightAligned(qty(i.remaining()) + " not yet returned", SMALL));
            t.addCell(back);
            t.addCell(numCell(qty(i.missing()), signum(i.missing()) > 0 ? ALERT : BODY));
            t.addCell(bodyCell(conditionLabel(i, ir), BODY));
            t.addCell(bodyCell(noteText(i, ir), BODY));
        }
        addSection(doc, w, "Items", t);
    }

    private static void summary(Document doc, PdfWriter w, HandoffDetailResponse d) throws DocumentException {
        boolean outstanding = signum(d.totalRemaining()) > 0;   // never true once a handoff is closed
        PdfPTable t = new PdfPTable(outstanding ? 4 : 3);
        t.setWidthPercentage(100);
        t.setSpacingBefore(6);
        t.setKeepTogether(true);
        t.addCell(statCell("Total given", qty(d.totalOutgoing())));
        t.addCell(statCell("Total returned", qty(d.totalReturned())));
        t.addCell(statCell("Total missing", qty(d.totalMissing() == null ? BigDecimal.ZERO : d.totalMissing())));
        if (outstanding) t.addCell(statCell("Not yet returned", qty(d.totalRemaining())));
        addSection(doc, w, "Summary", t);
    }

    /** One short line per day, not a table per return: what came back, what is missing, any return note. */
    private static void returnSummary(Document doc, PdfWriter w, HandoffDetailResponse d) throws DocumentException {
        TreeMap<LocalDate, DayGroup> days = returnDays(d);
        if (days.isEmpty()) return;

        section(doc, w, "Return summary");
        String missingWord = missingWord(d);
        days.forEach((date, g) -> {
            List<String> parts = dayParts(g, missingWord);
            Paragraph p = new Paragraph();
            p.setSpacingAfter(3);
            p.add(new Chunk(DAY.format(date) + ":  ", BODY_BOLD));
            p.add(new Chunk(String.join("; ", parts) + (parts.isEmpty() ? "" : "."), BODY));
            for (String note : g.notes) {
                p.add(Chunk.NEWLINE);
                p.add(new Chunk("Note: " + note, SMALL));
            }
            try {
                doc.add(p);
            } catch (DocumentException e) {
                throw new IllegalStateException(e);
            }
        });
    }

    /** Per day, what came back and what is reported missing (confirmed returns only), oldest first; days with nothing to say are left out. */
    static TreeMap<LocalDate, DayGroup> returnDays(HandoffDetailResponse d) {
        Map<Long, HandoffItemResponse> byId = new HashMap<>();
        orEmpty(d.items()).forEach(i -> byId.put(i.id(), i));

        TreeMap<LocalDate, DayGroup> days = new TreeMap<>();
        for (ReturnEventResponse r : orEmpty(d.returns())) {
            if (!r.confirmed() || r.occurredAt() == null) continue;
            DayGroup g = days.computeIfAbsent(r.occurredAt().atZone(ZoneOffset.UTC).toLocalDate(), k -> new DayGroup());
            for (ReturnLineResponse l : orEmpty(r.lines())) {
                if (l.condition() == ItemCondition.MISSING) {
                    HandoffItemResponse item = byId.get(l.itemId());
                    // An item brought back later is no longer missing, so it is not reported as such.
                    if (item != null && signum(item.missing()) > 0) g.missing.merge(clean(l.itemName()), l.quantity(), BigDecimal::add);
                } else {
                    g.returned = g.returned.add(l.quantity());
                }
            }
            String note = clean(r.note());
            if (!note.isEmpty() && !g.notes.contains(note)) g.notes.add(note);
        }
        days.values().removeIf(DayGroup::isEmpty);
        return days;
    }

    /** "confirmed missing" once the recipient has confirmed it, "reported missing" before. */
    static String missingWord(HandoffDetailResponse d) {
        return d.missingConfirmedAt() != null ? "confirmed missing" : "reported missing";
    }

    /** What one day's line says: how many items came back and which were missing (the notes go on lines of their own). */
    static List<String> dayParts(DayGroup g, String missingWord) {
        List<String> parts = new ArrayList<>();
        if (g.returned.signum() > 0) {
            parts.add(qty(g.returned) + (g.returned.compareTo(BigDecimal.ONE) == 0 ? " item returned" : " items returned"));
        }
        g.missing.forEach((item, q) -> parts.add(qty(q) + " " + item + " " + missingWord));
        return parts;
    }

    /** A compact line per kind of attachment — names only; nothing is embedded. */
    private static void attachments(Document doc, HandoffDetailResponse d) throws DocumentException {
        addAttachmentLine(doc, "Evidence attached", attachmentNames(d, false));
        addAttachmentLine(doc, "Reference documents", attachmentNames(d, true));
    }

    /** The file names of the evidence (or of the reference documents). */
    static List<String> attachmentNames(HandoffDetailResponse d, boolean referenceDocuments) {
        List<String> names = new ArrayList<>();
        for (AttachmentResponse a : orEmpty(d.attachments())) {
            if ((a.kind() == AttachmentKind.REFERENCE_DOCUMENT) == referenceDocuments) names.add(clean(a.originalFilename()));
        }
        return names;
    }

    private static void addAttachmentLine(Document doc, String label, List<String> names) throws DocumentException {
        if (names.isEmpty()) return;
        Paragraph p = new Paragraph();
        p.setSpacingBefore(8);
        p.add(new Chunk(label + ": ", BODY_BOLD));
        p.add(new Chunk(String.join(", ", names), BODY));
        doc.add(p);
    }

    // ----------------------------------------------------------- Per-item figures

    static final class DayGroup {
        BigDecimal returned = BigDecimal.ZERO;
        final Map<String, BigDecimal> missing = new LinkedHashMap<>();
        final List<String> notes = new ArrayList<>();

        boolean isEmpty() {
            return returned.signum() == 0 && missing.isEmpty() && notes.isEmpty();
        }
    }

    /** What actually came back for one item, by condition, and the notes recorded against those lines. */
    record ItemReturns(Map<ItemCondition, BigDecimal> byCondition, List<String> notes) {}

    static Map<Long, ItemReturns> confirmedReturns(HandoffDetailResponse d, Map<Long, HandoffItemResponse> items) {
        Map<Long, ItemReturns> out = new HashMap<>();
        for (ReturnEventResponse r : orEmpty(d.returns())) {
            if (!r.confirmed()) continue;
            for (ReturnLineResponse l : orEmpty(r.lines())) {
                ItemReturns ir = out.computeIfAbsent(l.itemId(),
                        k -> new ItemReturns(new EnumMap<>(ItemCondition.class), new ArrayList<>()));
                boolean missingLine = l.condition() == ItemCondition.MISSING;
                if (!missingLine) ir.byCondition().merge(l.condition(), l.quantity(), BigDecimal::add);
                HandoffItemResponse item = items.get(l.itemId());
                boolean stillMissing = item != null && signum(item.missing()) > 0;
                if (!isBlank(l.note()) && (!missingLine || stillMissing)) {
                    String note = (l.condition() == ItemCondition.GOOD ? "" : humanize(l.condition().name()) + ": ") + clean(l.note());
                    if (!ir.notes().contains(note)) ir.notes().add(note);
                }
            }
        }
        return out;
    }

    /** The condition of what came back; "Good 4, Damaged 2" when it differs; as-given if nothing returned yet. */
    static String conditionLabel(HandoffItemResponse i, ItemReturns ir) {
        Map<ItemCondition, BigDecimal> by = ir == null ? Map.of() : ir.byCondition();
        if (by.isEmpty()) {
            boolean allMissing = signum(i.outgoing()) > 0 && i.missing() != null && i.missing().compareTo(i.outgoing()) == 0;
            return allMissing || i.condition() == null ? NONE : humanize(i.condition().name());
        }
        if (by.size() == 1) return humanize(by.keySet().iterator().next().name());
        return by.entrySet().stream().map(e -> humanize(e.getKey().name()) + " " + qty(e.getValue()))
                .collect(Collectors.joining(", "));
    }

    static String noteText(HandoffItemResponse i, ItemReturns ir) {
        List<String> notes = new ArrayList<>();
        if (!isBlank(i.notes())) notes.add(clean(i.notes()));
        if (ir != null) notes.addAll(ir.notes());
        return notes.isEmpty() ? NONE : String.join("; ", notes);
    }

    // ------------------------------------------------------------------- Layout

    /** A heading followed by plain text: needs a little room to stay with it. */
    private static void section(Document doc, PdfWriter writer, String title) throws DocumentException {
        if (writer.getVerticalPosition(true) - doc.bottom() < MIN_SECTION_SPACE) doc.newPage();
        heading(doc, title);
    }

    /**
     * A heading together with its table. The table is measured first so the heading is never left
     * alone at the bottom of a page: a table kept whole needs room for all of it, a splittable one
     * needs its header and first row. Otherwise both start on the next page.
     */
    private static void addSection(Document doc, PdfWriter writer, String title, PdfPTable table)
            throws DocumentException {
        table.setTotalWidth((doc.right() - doc.left()) * table.getWidthPercentage() / 100f);
        table.calculateHeights(true);
        float page = doc.top() - doc.bottom();
        float firstBlock;
        if (table.getKeepTogether()) {
            firstBlock = Math.min(table.getTotalHeight(), page - HEADING_HEIGHT);
        } else {
            int firstBody = Math.min(table.getHeaderRows(), table.size() - 1);
            firstBlock = table.getHeaderHeight() + table.getRowHeight(firstBody);
        }
        float needed = HEADING_HEIGHT + table.spacingBefore() + firstBlock;
        if (writer.getVerticalPosition(true) - doc.bottom() < needed) doc.newPage();
        heading(doc, title);
        doc.add(table);
    }

    private static void heading(Document doc, String title) throws DocumentException {
        Paragraph p = new Paragraph(title.toUpperCase(Locale.ENGLISH), SECTION);
        p.setSpacingBefore(14);
        p.setSpacingAfter(2);
        p.setKeepTogether(true);
        doc.add(p);
    }

    // ------------------------------------------------------------------- Cells

    /**
     * An empty, borderless cell to fill with addElement. (A cell created from a Phrase loses that
     * text as soon as elements are added to it, so multi-line cells are built from elements only.)
     */
    private static PdfPCell plain() {
        PdfPCell c = new PdfPCell();
        c.setBorder(Rectangle.NO_BORDER);
        c.setPadding(0);
        return c;
    }

    private static PdfPCell boxed() {
        PdfPCell c = new PdfPCell();
        c.setBorderColor(BORDER);
        c.setBackgroundColor(PANEL);
        c.setPadding(7);
        return c;
    }

    private static Paragraph rightAligned(String text, Font font) {
        Paragraph p = new Paragraph(text, font);
        p.setAlignment(Element.ALIGN_RIGHT);
        return p;
    }

    private static PdfPCell headCell(String text, int align) {
        PdfPCell c = new PdfPCell(new Phrase(text.toUpperCase(Locale.ENGLISH), HEAD));
        c.setBackgroundColor(PANEL);
        c.setBorderColor(BORDER);
        c.setPadding(4);
        c.setHorizontalAlignment(align);
        return c;
    }

    /** A body cell; pass null text to add elements to it yourself. */
    private static PdfPCell bodyCell(String text, Font font) {
        PdfPCell c = text == null ? new PdfPCell() : new PdfPCell(new Phrase(text, font));
        c.setLeading(0f, 1.5f);   // same line spacing as the Paragraphs used in multi-line cells, so rows line up
        c.setBorderColor(BORDER);
        c.setPadding(5);
        c.setVerticalAlignment(Element.ALIGN_MIDDLE);
        return c;
    }

    private static PdfPCell numCell(String text, Font font) {
        PdfPCell c = bodyCell(text, font);
        c.setHorizontalAlignment(Element.ALIGN_RIGHT);
        return c;
    }

    private static PdfPCell statCell(String label, String value) {
        PdfPCell c = boxed();
        c.setPadding(6);
        c.addElement(new Paragraph(label.toUpperCase(Locale.ENGLISH), HEAD));
        c.addElement(new Paragraph(value, new Font(Font.HELVETICA, 13, Font.BOLD, INK)));
        return c;
    }

    private static PdfPCell partyCell(String role, String name, String detail) {
        PdfPCell c = boxed();
        c.setPadding(6);
        c.addElement(new Paragraph(role.toUpperCase(Locale.ENGLISH), HEAD));
        c.addElement(new Paragraph(orNone(name), BODY_BOLD));
        if (!isBlank(detail)) c.addElement(new Paragraph(clean(detail), SMALL));
        return c;
    }

    // ----------------------------------------------------------------- Formatting

    static String identifiers(HandoffItemResponse i) {
        List<String> ids = new ArrayList<>();
        if (!isBlank(i.sku())) ids.add("SKU " + clean(i.sku()));
        if (!isBlank(i.serialNumber())) ids.add("Serial " + clean(i.serialNumber()));
        if (!isBlank(i.assetNumber())) ids.add("Asset " + clean(i.assetNumber()));
        return String.join(" · ", ids);
    }

    static String qty(BigDecimal v) {
        return v == null ? NONE : v.stripTrailingZeros().toPlainString();
    }

    static int signum(BigDecimal v) {
        return v == null ? 0 : v.signum();
    }

    /** Date only, in UTC (the footer says so). */
    static String day(Instant at) {
        return at == null ? NONE : DAY.format(at.atZone(ZoneOffset.UTC));
    }

    static <T> List<T> orEmpty(List<T> list) {
        return list == null ? List.of() : list;
    }

    static boolean isBlank(String s) {
        return s == null || s.isBlank();
    }

    private static String orNone(String s) {
        return isBlank(s) ? NONE : clean(s);
    }

    /** Drops control characters that would corrupt the layout; keeps line breaks as spaces. */
    static String clean(String s) {
        return s == null ? "" : s.replaceAll("[\\r\\n\\t]+", " ").replaceAll("\\p{Cntrl}", "").trim();
    }

    static String humanize(String enumName) {
        return Arrays.stream(enumName.split("_"))
                .map(w -> w.isEmpty() ? w : w.charAt(0) + w.substring(1).toLowerCase(Locale.ENGLISH))
                .collect(Collectors.joining(" "));
    }

    // --------------------------------------------------------------------- Footer

    /** Draws "HandOffly · HO-1 · Proof of Handoff" and "Page n of N" on every page. */
    private static final class Footer extends PdfPageEventHelper {
        private final String code;
        private PdfTemplate total;
        private BaseFont font;

        Footer(String code) {
            this.code = code;
        }

        @Override
        public void onOpenDocument(PdfWriter writer, Document document) {
            try {
                font = BaseFont.createFont(BaseFont.HELVETICA, BaseFont.WINANSI, false);
            } catch (DocumentException | IOException e) {
                throw new IllegalStateException(e);
            }
            total = writer.getDirectContent().createTemplate(40, 12);
        }

        @Override
        public void onEndPage(PdfWriter writer, Document document) {
            PdfContentByte cb = writer.getDirectContent();
            float y = document.bottom() - 22;
            cb.saveState();
            cb.setColorFill(MUTED);
            cb.beginText();
            cb.setFontAndSize(font, 8);
            cb.setTextMatrix(document.left(), y);
            cb.showText("HandOffly · " + code + " · Proof of Handoff · Dates in UTC");
            cb.endText();

            String label = "Page " + writer.getPageNumber() + " of ";
            float width = font.getWidthPoint(label, 8);
            float x = document.right() - width - 14;
            cb.beginText();
            cb.setFontAndSize(font, 8);
            cb.setTextMatrix(x, y);
            cb.showText(label);
            cb.endText();
            cb.addTemplate(total, x + width, y);
            cb.restoreState();
        }

        @Override
        public void onCloseDocument(PdfWriter writer, Document document) {
            total.beginText();
            total.setFontAndSize(font, 8);
            total.setColorFill(MUTED);
            total.setTextMatrix(0, 0);
            total.showText(String.valueOf(writer.getPageNumber() - 1));
            total.endText();
        }
    }
}

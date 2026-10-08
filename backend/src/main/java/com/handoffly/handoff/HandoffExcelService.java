package com.handoffly.handoff;

import com.handoffly.handoff.HandoffPdfService.DayGroup;
import com.handoffly.handoff.HandoffPdfService.ItemReturns;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemResponse;
import org.apache.poi.ss.usermodel.BorderStyle;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.FillPatternType;
import org.apache.poi.ss.usermodel.Font;
import org.apache.poi.ss.usermodel.IndexedColors;
import org.apache.poi.ss.usermodel.PrintSetup;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.VerticalAlignment;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;

import static com.handoffly.handoff.HandoffPdfService.attachmentNames;
import static com.handoffly.handoff.HandoffPdfService.clean;
import static com.handoffly.handoff.HandoffPdfService.confirmedReturns;
import static com.handoffly.handoff.HandoffPdfService.conditionLabel;
import static com.handoffly.handoff.HandoffPdfService.day;
import static com.handoffly.handoff.HandoffPdfService.humanize;
import static com.handoffly.handoff.HandoffPdfService.identifiers;
import static com.handoffly.handoff.HandoffPdfService.isBlank;
import static com.handoffly.handoff.HandoffPdfService.noteText;
import static com.handoffly.handoff.HandoffPdfService.orEmpty;
import static com.handoffly.handoff.HandoffPdfService.qty;
import static com.handoffly.handoff.HandoffPdfService.signum;

/**
 * The same single Proof-of-Handoff document as {@link HandoffPdfService}, as one spreadsheet page: the same sections in the same order
 * (heading and reference, parties, items, summary, return summary, attachments) with the same words — because every derived value
 * (condition, notes, what came back each day, identifiers) comes from the PDF's own helpers, not a second calculation. It is one
 * handoff's record, not a report: nothing here lists other handoffs or the audit history. Built on demand from
 * {@link HandoffService#getDetail} (which also enforces ownership), in any status, and never stored. Text goes in as text cells, never
 * formulas, so a name that starts with "=" stays a name.
 */
@Service
public class HandoffExcelService {

    public static final String CONTENT_TYPE = "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet";
    private static final DateTimeFormatter DAY = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH);
    private static final int CHARACTER = 256;   // POI measures a column's width in 1/256 of a character
    /** The columns: item, given, returned, missing, not yet returned, condition, note. */
    private static final int[] WIDTHS = {38, 11, 11, 11, 17, 22, 44};
    private static final int LAST_COLUMN = WIDTHS.length - 1;

    private final HandoffService handoffService;

    public HandoffExcelService(HandoffService handoffService) {
        this.handoffService = handoffService;
    }

    /** A rendered workbook and the safe filename to present it under. */
    public record ExcelFile(String filename, byte[] content) {}

    /** Generates the workbook for a handoff the user owns. */
    public ExcelFile generate(Long userId, Long handoffId) {
        HandoffDetailResponse detail = handoffService.getDetail(userId, handoffId);
        return new ExcelFile(HandoffPdfService.filenameFor(detail.publicCode(), "xlsx"), render(detail));
    }

    /** A pure function of the detail response, so the layout can be tested without a database. */
    public byte[] render(HandoffDetailResponse d) {
        try (XSSFWorkbook book = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            Sheet sheet = book.createSheet("Proof of Handoff");
            Styles s = new Styles(book);
            for (int c = 0; c <= LAST_COLUMN; c++) sheet.setColumnWidth(c, WIDTHS[c] * CHARACTER);

            int r = header(sheet, s, d, 0);
            r = parties(sheet, s, d, r + 1);
            r = items(sheet, s, d, r + 1);
            r = summary(sheet, s, d, r + 1);
            r = returnSummary(sheet, s, d, r + 1);
            attachments(sheet, s, d, r);

            sheet.setFitToPage(true);   // printed, it is one page wide like the PDF
            PrintSetup print = sheet.getPrintSetup();
            print.setPaperSize(PrintSetup.A4_PAPERSIZE);
            print.setFitWidth((short) 1);
            print.setFitHeight((short) 0);
            book.write(out);
            return out.toByteArray();
        } catch (IOException e) {
            throw new UncheckedIOException("Could not build the handoff spreadsheet.", e);
        }
    }

    // ------------------------------------------------------------------ sections (the PDF's, in its order)

    private static int header(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        text(sheet.createRow(r++), 0, "HandOffly", s.brand);
        text(sheet.createRow(r++), 0, "Proof of Handoff", s.title);
        text(sheet.createRow(r++), 0, "Reference: " + clean(d.publicCode()), s.bold);
        if (!isBlank(d.title())) text(sheet.createRow(r++), 0, clean(d.title()), s.plain);
        if (d.status() != HandoffStatus.CLOSED) {
            // Said once, so an in-progress copy cannot be mistaken for the final record (as the PDF does).
            text(sheet.createRow(r++), 0, "Interim copy — this handoff is not closed (status: "
                    + humanize(d.status().name()) + "); figures may still change.", s.warning);
        }
        if (!isBlank(d.purpose())) text(sheet.createRow(r++), 0, "Purpose: " + clean(d.purpose()), s.muted);
        return r;
    }

    private static int parties(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        boolean declined = d.status() == HandoffStatus.REJECTED;
        r = pair(sheet, s, r, "Given by", clean(d.senderName()) + (isBlank(d.senderOrganization()) ? "" : " · " + clean(d.senderOrganization())));
        r = pair(sheet, s, r, "Received by", clean(d.recipientName()));
        r = pair(sheet, s, r, declined ? "Declined by" : "Acknowledged by",
                isBlank(d.acknowledgementName()) ? "Not yet acknowledged" : clean(d.acknowledgementName()));
        r = pair(sheet, s, r, "Date given", day(d.outgoingAt()));
        if (declined && !isBlank(d.rejectionReason())) r = pair(sheet, s, r, "Reason", clean(d.rejectionReason()));
        return r;
    }

    private static int items(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        r = heading(sheet, s, "Items", r);
        Row head = sheet.createRow(r++);
        String[] titles = {"Item", "Given", "Returned", "Missing", "Not yet returned", "Condition", "Note"};
        for (int c = 0; c < titles.length; c++) text(head, c, titles[c], c >= 1 && c <= 4 ? s.headerRight : s.header);

        Map<Long, HandoffItemResponse> byId = new java.util.HashMap<>();
        orEmpty(d.items()).forEach(i -> byId.put(i.id(), i));
        Map<Long, ItemReturns> returned = confirmedReturns(d, byId);
        if (byId.isEmpty()) {
            text(sheet.createRow(r++), 0, "No items.", s.plain);
        }
        for (HandoffItemResponse i : orEmpty(d.items())) {
            ItemReturns ir = returned.get(i.id());
            Row row = sheet.createRow(r++);
            // The item's name, then what describes it, one to a line — as in the PDF's item cell.
            List<String> lines = new ArrayList<>(List.of(clean(i.name())));
            if (!isBlank(i.description())) lines.add(clean(i.description()));
            String ids = identifiers(i);
            if (!ids.isEmpty()) lines.add(ids);
            text(row, 0, String.join("\n", lines), s.wrap);
            number(row, 1, i.outgoing(), s.number);
            number(row, 2, i.returnedConfirmed(), s.number);
            number(row, 3, i.missing(), signum(i.missing()) > 0 ? s.alert : s.number);
            if (signum(i.remaining()) > 0) number(row, 4, i.remaining(), s.number);
            text(row, 5, conditionLabel(i, ir), s.wrap);
            String note = noteText(i, ir);
            if (signum(i.returnedPending()) > 0) {
                note = ("—".equals(note) ? "" : note + "; ") + qty(i.returnedPending()) + " awaiting confirmation";
            }
            text(row, 6, note, s.wrap);
        }
        return r;
    }

    private static int summary(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        r = heading(sheet, s, "Summary", r);
        r = figure(sheet, s, r, "Total given", d.totalOutgoing());
        r = figure(sheet, s, r, "Total returned", d.totalReturned());
        r = figure(sheet, s, r, "Total missing", d.totalMissing() == null ? BigDecimal.ZERO : d.totalMissing());
        if (signum(d.totalRemaining()) > 0) r = figure(sheet, s, r, "Not yet returned", d.totalRemaining());
        return r;
    }

    /** One short line per day, as in the PDF: what came back, what is missing, any return note. */
    private static int returnSummary(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        TreeMap<LocalDate, DayGroup> days = HandoffPdfService.returnDays(d);
        if (days.isEmpty()) return r - 1;
        r = heading(sheet, s, "Return summary", r);
        String missingWord = HandoffPdfService.missingWord(d);
        for (Map.Entry<LocalDate, DayGroup> e : days.entrySet()) {
            List<String> parts = HandoffPdfService.dayParts(e.getValue(), missingWord);
            text(sheet.createRow(r++), 0, DAY.format(e.getKey()) + ":  " + String.join("; ", parts) + (parts.isEmpty() ? "" : "."), s.plain);
            for (String note : e.getValue().notes) text(sheet.createRow(r++), 0, "Note: " + note, s.muted);
        }
        return r;
    }

    /** A line per kind of attachment — names only; nothing is embedded. */
    private static void attachments(Sheet sheet, Styles s, HandoffDetailResponse d, int r) {
        List<String> evidence = attachmentNames(d, false);
        List<String> reference = attachmentNames(d, true);
        if (evidence.isEmpty() && reference.isEmpty()) return;
        r++;
        if (!evidence.isEmpty()) text(sheet.createRow(r++), 0, "Evidence attached: " + String.join(", ", evidence), s.plain);
        if (!reference.isEmpty()) text(sheet.createRow(r), 0, "Reference documents: " + String.join(", ", reference), s.plain);
    }

    // ------------------------------------------------------------------ cells

    private static int heading(Sheet sheet, Styles s, String title, int r) {
        text(sheet.createRow(r), 0, title.toUpperCase(Locale.ENGLISH), s.section);
        return r + 1;
    }

    /** A label in the first column and its value beside it (the text runs on across the empty cells to its right). */
    private static int pair(Sheet sheet, Styles s, int r, String label, String value) {
        Row row = sheet.createRow(r);
        text(row, 0, label, s.bold);
        text(row, 1, value, s.plain);
        return r + 1;
    }

    private static int figure(Sheet sheet, Styles s, int r, String label, BigDecimal value) {
        Row row = sheet.createRow(r);
        text(row, 0, label, s.bold);
        number(row, 1, value, s.number);
        return r + 1;
    }

    /** Text as a text cell — never a formula — with control characters flattened (a line break is kept where a cell asks for one). */
    private static void text(Row row, int column, String value, CellStyle style) {
        String clean = value == null ? "" : value.replaceAll("[\\r\\t]+", " ").replaceAll("[\\p{Cntrl}&&[^\\n]]", "").trim();
        if (!clean.isEmpty()) {
            row.createCell(column).setCellValue(clean);
            row.getCell(column).setCellStyle(style);
        }
    }

    private static void number(Row row, int column, BigDecimal value, CellStyle style) {
        if (value != null) {
            row.createCell(column).setCellValue(value.doubleValue());
            row.getCell(column).setCellStyle(style);
        }
    }

    /** The workbook's few styles, made once (a workbook has a limit on how many it may hold). */
    private static final class Styles {
        final CellStyle brand;
        final CellStyle title;
        final CellStyle section;
        final CellStyle bold;
        final CellStyle plain;
        final CellStyle muted;
        final CellStyle warning;
        final CellStyle header;
        final CellStyle headerRight;
        final CellStyle wrap;
        final CellStyle number;
        final CellStyle alert;

        Styles(XSSFWorkbook book) {
            brand = styled(book, font(book, 18, true, IndexedColors.ROYAL_BLUE));
            title = styled(book, font(book, 13, true, IndexedColors.BLACK));
            section = styled(book, font(book, 10, true, IndexedColors.ROYAL_BLUE));
            bold = styled(book, font(book, 10, true, IndexedColors.BLACK));
            plain = styled(book, font(book, 10, false, IndexedColors.BLACK));
            muted = styled(book, font(book, 9, false, IndexedColors.GREY_50_PERCENT));
            warning = styled(book, font(book, 9, false, IndexedColors.DARK_YELLOW));
            header = styled(book, font(book, 9, true, IndexedColors.GREY_50_PERCENT));
            header.setFillForegroundColor(IndexedColors.GREY_25_PERCENT.getIndex());
            header.setFillPattern(FillPatternType.SOLID_FOREGROUND);
            header.setBorderBottom(BorderStyle.THIN);
            headerRight = book.createCellStyle();
            headerRight.cloneStyleFrom(header);
            headerRight.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            wrap = styled(book, font(book, 10, false, IndexedColors.BLACK));
            wrap.setWrapText(true);
            wrap.setVerticalAlignment(VerticalAlignment.TOP);
            number = styled(book, font(book, 10, false, IndexedColors.BLACK));
            number.setDataFormat(book.getCreationHelper().createDataFormat().getFormat("0.###"));
            number.setAlignment(org.apache.poi.ss.usermodel.HorizontalAlignment.RIGHT);
            number.setVerticalAlignment(VerticalAlignment.TOP);
            alert = book.createCellStyle();
            alert.cloneStyleFrom(number);
            alert.setFont(font(book, 10, true, IndexedColors.RED));
        }

        private static Font font(XSSFWorkbook book, int size, boolean bold, IndexedColors color) {
            Font f = book.createFont();
            f.setFontHeightInPoints((short) size);
            f.setBold(bold);
            f.setColor(color.getIndex());
            return f;
        }

        private static CellStyle styled(XSSFWorkbook book, Font font) {
            CellStyle style = book.createCellStyle();
            style.setFont(font);
            return style;
        }
    }
}

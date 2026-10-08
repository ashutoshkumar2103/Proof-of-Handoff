package com.handoffly.documentcheck;

import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.pdfbox.text.TextPosition;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Reads the item table out of HandOffly's own Proof-of-Handoff PDF, for {@link DocumentLineExtractor}. That document is more than a list of
 * lines — it has a header, parties, a summary and a footer, and each item is a cell several lines tall — so reading it line by line would turn
 * its other text into items. Here the table is found by its heading and its column headings (ITEM, GIVEN, RETURNED, MISSING, CONDITION, NOTE,
 * the ones {@code HandoffPdfService} writes) and read by where each word sits: an item is the bold name in the Item column, its quantity is
 * the figure under Given (what the handoff handed over). The description and identifiers under a name, the Returned / Missing figures, the
 * condition and note, the parties, the summary and the footer are never read as items.
 *
 * <p>It only answers for a document it recognises as that PDF, and only with rows it is sure of: if the names and the Given figures do not
 * pair up one to one, it gives no rows at all rather than a guess, and the customer fills the rows in by hand.
 */
final class ProofOfHandoffPdf {

    private static final List<String> HEADINGS = List.of("ITEM", "GIVEN", "RETURNED", "MISSING", "CONDITION", "NOTE");
    private static final Pattern FIGURE = Pattern.compile("\\d+(?:\\.\\d+)?");
    private static final float SAME_LINE = 2f;
    /** A Given figure sits under its heading, at the right edge; this is how far from it (in points) it may be and still be that column's. */
    private static final float COLUMN_REACH = 22f;
    /** A wrapped name's lines are a line apart (1.5 times the font size); the next item is further off than that, by its cell padding and its other lines. */
    private static final float WRAP_GAP = 1.8f;

    /** One word of the PDF: where it is, whether it is bold, and what it says. */
    private record Word(int page, float x, float end, float y, float size, boolean bold, String text) {}

    /** A visual line: the words that share a baseline, left to right. */
    private record Line(int page, float y, List<Word> words) {
        String text() {
            return String.join(" ", words.stream().map(Word::text).toList());
        }
    }

    private ProofOfHandoffPdf() {
    }

    /**
     * @return empty when this is not a HandOffly Proof-of-Handoff PDF (the caller reads it as any other PDF); otherwise the rows
     *         {@code [item name, given]} — none at all when the table could not be read with certainty
     */
    static Optional<List<List<String>>> itemRows(PDDocument document) throws IOException {
        List<Line> lines = lines(words(document));
        if (!isProofOfHandoff(lines)) {
            return Optional.empty();
        }
        List<List<String>> rows = new ArrayList<>();
        for (int page = 1; page <= document.getNumberOfPages(); page++) {
            List<List<String>> onPage = pageItems(lines, page);
            if (onPage == null) {
                return Optional.of(List.of());   // this page's table is not certain: no rows, not a guess
            }
            rows.addAll(onPage);
        }
        return Optional.of(rows);
    }

    // ------------------------------------------------------------------ recognising the document

    private static boolean isProofOfHandoff(List<Line> lines) {
        boolean title = lines.stream().anyMatch(l -> l.page() == 1 && l.text().equals("Proof of Handoff"));
        boolean brand = lines.stream().anyMatch(l -> l.page() == 1 && l.text().startsWith("HandOffly"));
        return title && brand && lines.stream().anyMatch(ProofOfHandoffPdf::isTableHeader);
    }

    private static boolean isTableHeader(Line l) {
        return l.words().stream().map(Word::text).toList().equals(HEADINGS);
    }

    private static boolean endsTable(Line l) {
        String text = l.text();
        return text.equals("SUMMARY") || text.equals("RETURN SUMMARY") || text.startsWith("HandOffly ·");
    }

    // ------------------------------------------------------------------ one page of the table

    /** The rows of the table on one page, none if it has no table there, or null if it is there but cannot be read for sure. */
    private static List<List<String>> pageItems(List<Line> all, int page) {
        List<Line> lines = all.stream().filter(l -> l.page() == page).toList();
        int header = -1;
        for (int i = 0; i < lines.size(); i++) {
            if (isTableHeader(lines.get(i))) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            return List.of();
        }
        Line head = lines.get(header);
        float itemEdge = head.words().get(1).x() - 4f;        // the Item column ends before the Given heading begins
        Word given = head.words().get(1);
        Word returned = head.words().get(2);
        Word missing = head.words().get(3);
        float tableEnd = head.words().get(4).x() - 2f;        // Condition and Note are further right and never read

        List<Line> itemColumn = new ArrayList<>();
        List<Word> givenFigures = new ArrayList<>();
        for (int i = header + 1; i < lines.size() && !endsTable(lines.get(i)); i++) {
            List<Word> inItem = new ArrayList<>();
            for (Word w : lines.get(i).words()) {
                if (w.end() <= itemEdge) {
                    inItem.add(w);
                } else if (w.end() <= tableEnd && FIGURE.matcher(w.text()).matches() && nearest(w, given, returned, missing) == given) {
                    givenFigures.add(w);
                }
            }
            if (!inItem.isEmpty()) {
                itemColumn.add(new Line(page, lines.get(i).y(), inItem));
            }
        }
        List<String> names = names(itemColumn, givenFigures.size());
        if (names == null) {
            return null;   // the names and the Given figures do not pair up one to one
        }
        givenFigures.sort(Comparator.comparingDouble(Word::y));
        List<List<String>> rows = new ArrayList<>();
        for (int i = 0; i < names.size(); i++) {
            rows.add(List.of(names.get(i), givenFigures.get(i).text()));
        }
        return rows;
    }

    /** Which of the three figure columns a word belongs to, by its right edge against theirs (null if it is near none of them). */
    private static Word nearest(Word w, Word... headings) {
        Word best = null;
        float distance = COLUMN_REACH;
        for (Word h : headings) {
            float d = Math.abs(w.end() - h.end());
            if (d <= distance) {
                best = h;
                distance = d;
            }
        }
        return best;
    }

    /**
     * The item names in the Item column: the bold lines (a description or identifier under a name is not bold). A name that wraps onto a
     * second bold line is one name — told apart from the next item's name by the gap between the lines. When the grouping gives a different
     * count from the number of figures, one name per bold line is tried; if that does not fit either, there is no certain answer (null).
     */
    private static List<String> names(List<Line> itemColumn, int expected) {
        List<Line> bold = itemColumn.stream().filter(l -> l.words().stream().allMatch(Word::bold)).toList();
        List<String> grouped = new ArrayList<>();
        StringBuilder current = null;
        Line previous = null;
        for (Line l : bold) {
            boolean continues = previous != null && previous == lastLineOf(itemColumn, l)
                    && l.y() - previous.y() <= l.words().get(0).size() * WRAP_GAP;
            if (continues && current != null) {
                current.append(' ').append(l.text());
            } else {
                if (current != null) grouped.add(current.toString());
                current = new StringBuilder(l.text());
            }
            previous = l;
        }
        if (current != null) grouped.add(current.toString());
        if (grouped.size() == expected) return grouped;
        List<String> single = bold.stream().map(Line::text).toList();
        return single.size() == expected ? single : null;
    }

    /** The line directly above {@code l} in the Item column (null for the first), so a name is only continued by the very next line. */
    private static Line lastLineOf(List<Line> itemColumn, Line l) {
        int at = itemColumn.indexOf(l);
        return at <= 0 ? null : itemColumn.get(at - 1);
    }

    // ------------------------------------------------------------------ reading the words

    private static List<Word> words(PDDocument document) throws IOException {
        List<Word> words = new ArrayList<>();
        PDFTextStripper stripper = new PDFTextStripper() {
            @Override
            protected void writeString(String text, List<TextPosition> positions) {
                if (text.isBlank() || positions.isEmpty()) return;
                TextPosition first = positions.get(0);
                TextPosition last = positions.get(positions.size() - 1);
                String font = first.getFont() == null || first.getFont().getName() == null ? "" : first.getFont().getName();
                words.add(new Word(getCurrentPageNo(), first.getXDirAdj(), last.getXDirAdj() + last.getWidthDirAdj(), first.getYDirAdj(),
                        first.getFontSizeInPt(), font.toLowerCase(Locale.ROOT).contains("bold"), text.trim()));
            }
        };
        stripper.setSortByPosition(true);
        stripper.getText(document);   // the words are collected as it reads; the text itself is not used
        return words;
    }

    /** Words that share a baseline form a line; lines are in page order, top to bottom. */
    private static List<Line> lines(List<Word> words) {
        List<Word> sorted = new ArrayList<>(words);
        sorted.sort(Comparator.comparingInt(Word::page).thenComparingDouble(Word::y).thenComparingDouble(Word::x));
        List<Line> lines = new ArrayList<>();
        List<Word> current = new ArrayList<>();
        for (Word w : sorted) {
            if (!current.isEmpty() && (current.get(0).page() != w.page() || Math.abs(current.get(0).y() - w.y()) > SAME_LINE)) {
                lines.add(line(current));
                current = new ArrayList<>();
            }
            current.add(w);
        }
        if (!current.isEmpty()) lines.add(line(current));
        return lines;
    }

    private static Line line(List<Word> words) {
        List<Word> ordered = new ArrayList<>(words);
        ordered.sort(Comparator.comparingDouble(Word::x));
        return new Line(ordered.get(0).page(), ordered.get(0).y(), ordered);
    }
}

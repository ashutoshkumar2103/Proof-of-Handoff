package com.handoffly.documentcheck;

import com.handoffly.ai.AiUnavailableException;
import com.handoffly.ai.ColumnMappingAssistant;
import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.AiItemMatchResponse;
import com.handoffly.documentcheck.dto.AiMappingResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

/**
 * AI Assist for HandoffCheck (optional): suggests which columns of an uploaded spreadsheet hold the item and the quantity, and which item
 * names of two files are probably the same item spelled differently, for the customer to review. It sits beside {@link DocumentCheckService} and touches nothing in it: the comparison, the file reading and every
 * rule about them are the existing, deterministic code, and this class only ever produces a suggestion to look at.
 */
@Service
public class AiMappingService {

    private static final Logger log = LoggerFactory.getLogger(AiMappingService.class);

    private final DocumentLineExtractor extractor;
    private final ColumnMappingAssistant assistant;

    public AiMappingService(DocumentLineExtractor extractor, ColumnMappingAssistant assistant) {
        this.extractor = extractor;
        this.assistant = assistant;
    }

    private static final int AI_SAMPLE_ROWS = 8;
    private static final int AI_SAMPLE_COLUMNS = 12;
    private static final int AI_CELL_LENGTH = 40;
    private static final int AI_SAMPLE_VALUES = 3;
    private static final String HIDDEN = "[hidden]";
    private static final Pattern EMAIL = Pattern.compile("\\S+@\\S+\\.\\S+");
    private static final Pattern PHONE = Pattern.compile("\\+?\\d[\\d\\s().-]{6,}\\d");

    static final String AI_NOT_CONFIGURED = "AI assistance isn't switched on for this server. You can carry on with the rows as they were read, or edit them by hand.";
    static final String AI_UNAVAILABLE = "AI assistance is temporarily unavailable. You can carry on with the rows as they were read, or edit them by hand.";
    static final String AI_RATE_LIMITED = "AI assistance has been used a lot recently. Please try again in a little while, or edit the rows by hand.";
    static final String AI_NOTHING_FOUND = "AI assistance couldn't tell which columns hold the item and the quantity. You can edit the rows by hand.";

    /**
     * AI Assist for one uploaded spreadsheet: suggests which columns hold the item and the quantity, for the customer to review.
     * Read-only and optional — it reads nothing into HandoffCheck, and whatever goes wrong (no key, no answer, over quota, an answer that
     * does not hold up) comes back as an ordinary "not available" answer, never an error, so the normal flow is never affected. Only a few
     * sanitized sample rows leave this server (see {@link #aiSample}). The suggestion is also tried with the ordinary reading, and is
     * refused if that finds no items — the AI is never taken at its word.
     */
    public AiMappingResponse suggestColumns(Long userId, MultipartFile file) {
        List<List<String>> rows = extractor.readRows(file, DocumentLineExtractor.SPREADSHEET_TYPES);
        List<List<String>> sample = aiSample(rows);
        try {
            ColumnMappingAssistant.MappingSuggestion suggestion = assistant.suggest(userId, sample);
            DocumentLineExtractor.ColumnMapping mapping = new DocumentLineExtractor.ColumnMapping(
                    suggestion.item().column(), suggestion.quantity().column(), suggestion.headerRow());
            int found = extractor.linesFrom(rows, mapping).lines().size();
            if (found == 0) {
                log.info("AI Assist's columns found no items in the file, so the suggestion was dropped");
                return AiMappingResponse.unavailable(AI_NOTHING_FOUND, false);   // the columns do not hold items and quantities in this file
            }
            return new AiMappingResponse(true, null, view(suggestion.item(), sample, suggestion.headerRow()),
                    view(suggestion.quantity(), sample, suggestion.headerRow()), suggestion.headerRow(), found, false);
        } catch (AiUnavailableException e) {
            // Why there is no suggestion — a category only, never what the provider or the file said — so an operator can tell a missing key
            // from a slow or refused provider from an answer that did not hold up.
            log.info("AI Assist gave no suggestion: {}", e.reason());
            return AiMappingResponse.unavailable(switch (e.reason()) {
                case NOT_CONFIGURED -> AI_NOT_CONFIGURED;
                case RATE_LIMITED -> AI_RATE_LIMITED;
                case NOTHING_FOUND -> AI_NOTHING_FOUND;
                case UNAVAILABLE, INVALID_RESPONSE -> AI_UNAVAILABLE;
            }, e.reason() == AiUnavailableException.Reason.UNAVAILABLE || e.reason() == AiUnavailableException.Reason.INVALID_RESPONSE);
        } catch (BadRequestException e) {
            return AiMappingResponse.unavailable(AI_NOTHING_FOUND, false);   // the suggestion could not be applied to this file
        }
    }

    private static final int AI_MAX_NAMES = 150;
    private static final int AI_MAX_PAIRS = 60;
    static final double CERTAIN = 0.85;   // from this confidence a suggestion is offered as a likely match; below it, only as a possible one

    /**
     * AI Assist for the item names of the two files being compared: which name of one is the same item as a name of the other, spelled
     * differently (a typing mistake, capitals, a plural). The pairs worth asking about are found here, deterministically: every name of File A
     * with every name of File B that {@link ItemNameVariation} allows as a variation (never a different word or number). The model is then asked,
     * in one request and only when the customer asks, to judge those pairs — same item, or not — and which spelling is the correct one; it
     * never brings a pair of its own, so it cannot widen the match, and a variation cannot be missed because the model did not think of it.
     * Only the names are sent. Read-only and optional like the column suggestion: nothing is compared or changed here (the comparison stays the
     * existing deterministic one, with the matches the customer accepts), and whatever goes wrong comes back as an ordinary "not available"
     * answer, never an error.
     */
    public AiItemMatchResponse suggestItemMatches(Long userId, List<String> fileA, List<String> fileB) {
        List<String> namesA = namesForAi(fileA);
        List<String> namesB = namesForAi(fileB);
        List<ColumnMappingAssistant.NamePair> candidates = new ArrayList<>();
        for (String a : namesA) {
            for (String b : namesB) {
                if (candidates.size() < AI_MAX_PAIRS && ItemNameVariation.isVariation(a, b)) {
                    candidates.add(new ColumnMappingAssistant.NamePair(a, b));
                }
            }
        }
        if (candidates.isEmpty()) {
            return AiItemMatchResponse.found(List.of());   // no names that could be the same item: nothing to ask, so no request is made
        }
        try {
            List<AiItemMatchResponse.Match> matches = assistant.suggestNameMatches(userId, candidates).stream()
                    .map((m) -> new AiItemMatchResponse.Match(m.from(), namesA.contains(m.from()) ? "File A" : "File B",
                            m.to(), namesA.contains(m.to()) ? "File A" : "File B", m.confidence(), m.confidence() >= CERTAIN, m.reason()))
                    .toList();
            return AiItemMatchResponse.found(matches);
        } catch (AiUnavailableException e) {
            log.info("AI Assist gave no item-name suggestion: {}", e.reason());   // the category only, as for the columns
            return AiItemMatchResponse.unavailable(switch (e.reason()) {
                case NOT_CONFIGURED -> AI_NOT_CONFIGURED;
                case RATE_LIMITED -> AI_RATE_LIMITED;
                case NOTHING_FOUND -> AI_NOTHING_FOUND;
                case UNAVAILABLE, INVALID_RESPONSE -> AI_UNAVAILABLE;
            }, e.reason() == AiUnavailableException.Reason.UNAVAILABLE || e.reason() == AiUnavailableException.Reason.INVALID_RESPONSE);
        }
    }

    /** The distinct item names of one file, the first few, leaving out anything that looks like an email address or a phone number. */
    private static List<String> namesForAi(List<String> names) {
        return names.stream().map(String::strip).filter((n) -> !n.isEmpty() && !EMAIL.matcher(n).find()
                        && !(PHONE.matcher(n).find() && n.chars().anyMatch((ch) -> "+ ().-".indexOf(ch) >= 0)))
                .distinct().limit(AI_MAX_NAMES).toList();
    }

    /**
     * The only part of a file that is ever sent to the AI: the first few rows, a limited number of columns, each cell cut short, and
     * anything that looks like an email address or a phone number hidden. No file name, no account, no handoff data.
     */
    private static List<List<String>> aiSample(List<List<String>> rows) {
        List<List<String>> sample = new ArrayList<>();
        for (List<String> row : rows.subList(0, Math.min(rows.size(), AI_SAMPLE_ROWS))) {
            List<String> cells = new ArrayList<>();
            for (String cell : row.subList(0, Math.min(row.size(), AI_SAMPLE_COLUMNS))) {
                cells.add(sanitizeCell(cell));
            }
            sample.add(cells);
        }
        return sample;
    }

    private static String sanitizeCell(String cell) {
        String c = cell == null ? "" : cell.strip();
        if (EMAIL.matcher(c).find() || PHONE.matcher(c).find() && c.chars().anyMatch(ch -> "+ ().-".indexOf(ch) >= 0)) {
            return HIDDEN;
        }
        return c.length() <= AI_CELL_LENGTH ? c : c.substring(0, AI_CELL_LENGTH);
    }

    /** A suggestion as the customer sees it: the column's heading, and a few values from it so they can judge it themselves. */
    private static AiMappingResponse.Suggestion view(ColumnMappingAssistant.ColumnSuggestion s, List<List<String>> sample, int headerRow) {
        String heading = headerRow >= 0 && headerRow < sample.size() && s.column() < sample.get(headerRow).size()
                ? sample.get(headerRow).get(s.column()) : "";
        List<String> values = new ArrayList<>();
        for (int r = headerRow + 1; r < sample.size() && values.size() < AI_SAMPLE_VALUES; r++) {
            List<String> row = sample.get(r);
            if (s.column() < row.size() && !row.get(s.column()).isBlank()) {
                values.add(row.get(s.column()));
            }
        }
        return new AiMappingResponse.Suggestion(s.column(), heading.isBlank() ? "Column " + columnLetter(s.column()) : heading,
                s.confidence(), s.reason(), values);
    }

    private static String columnLetter(int index) {
        StringBuilder name = new StringBuilder();
        for (int i = index; i >= 0; i = i / 26 - 1) {
            name.insert(0, (char) ('A' + i % 26));
        }
        return name.toString();
    }
}

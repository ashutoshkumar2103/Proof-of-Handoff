package com.handoffly.documentcheck;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentField;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.documentcheck.dto.NameMatch;
import com.handoffly.documentcheck.dto.ItemImportPreview;
import com.handoffly.documentcheck.dto.ReturnImportResult;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffAction;
import com.handoffly.handoff.HandoffItem;
import com.handoffly.handoff.HandoffService;
import com.handoffly.handoff.dto.HandoffDetailResponse;
import com.handoffly.handoff.dto.HandoffItemResponse;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.multipart.MultipartFile;

import java.math.BigDecimal;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.Set;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reusable comparison engine (HandoffCheck): compares a reference document against a
 * target that is either an existing handoff or another inline document. It never
 * duplicates handoff data — when a handoff is the target its live items/fields are read
 * directly. Results are computed and returned; persistence can be layered on later.
 */
@Service
public class DocumentCheckService {

    private static final int MAX_ITEM_NAME = 300;
    private static final int MAX_QUANTITY_DECIMALS = 3;
    private static final int MAX_QUANTITY_INTEGER_DIGITS = 16;

    static final String FILE_A_LABEL = "File A";
    static final String FILE_B_LABEL = "File B";

    private final HandoffService handoffService;
    private final DocumentLineExtractor extractor;

    public DocumentCheckService(HandoffService handoffService, DocumentLineExtractor extractor) {
        this.handoffService = handoffService;
        this.extractor = extractor;
    }

    /**
     * Return-import mode: extracts item/quantity lines from an uploaded file and matches them to
     * the items of the given handoff. Read-only — no return is created here; the client
     * prefills the existing return form and submits through the existing return API.
     */
    @Transactional(readOnly = true)
    public ReturnImportResult matchReturnImport(Long userId, Long handoffId, MultipartFile file) {
        HandoffDetailResponse handoff = handoffService.getDetail(userId, handoffId);
        if (!handoff.availableActions().contains(HandoffAction.RECORD_RETURN.name())) {
            throw new ConflictException("Returns can't be recorded on this handoff right now.");
        }
        List<DocumentLine> lines = extractor.extract(file);

        Map<String, List<HandoffItemResponse>> itemsByKey = new LinkedHashMap<>();
        for (HandoffItemResponse item : handoff.items()) {
            itemsByKey.computeIfAbsent(normalizeName(item.name()), k -> new ArrayList<>()).add(item);
        }

        List<ReturnImportResult.Row> rows = new ArrayList<>();
        for (DocumentLine line : lines) {
            List<HandoffItemResponse> hits = itemsByKey.getOrDefault(normalizeName(line.name()), List.of());
            if (hits.size() == 1) {
                HandoffItemResponse item = hits.getFirst();
                // Same quantity ReturnService allows to come back: remaining plus currently missing.
                rows.add(new ReturnImportResult.Row(line.name(), line.quantity(),
                        ReturnImportResult.MatchState.MATCHED, item.id(), item.name(),
                        item.remaining().add(item.missing())));
            } else {
                rows.add(new ReturnImportResult.Row(line.name(), line.quantity(),
                        hits.isEmpty() ? ReturnImportResult.MatchState.UNMATCHED
                                : ReturnImportResult.MatchState.AMBIGUOUS,
                        null, null, null));
            }
        }
        return new ReturnImportResult(file.getOriginalFilename(), rows);
    }

    @Transactional(readOnly = true)
    public CompareResult compare(Long userId, CompareRequest request) {
        List<DocumentLine> targetLines;
        List<DocumentField> targetFields;
        String targetLabel;

        if (request.handoffId() != null) {
            Handoff handoff = handoffService.getOwnedHandoff(request.handoffId(), userId);
            targetLines = handoff.getItems().stream()
                    .map(this::toLine).toList();
            targetFields = deriveHandoffFields(handoff, request.targetFields());
            targetLabel = request.targetLabel() != null ? request.targetLabel()
                    : "Handoff " + handoff.getPublicCode();
        } else {
            if (request.targetLines() == null || request.targetLines().isEmpty()) {
                throw new BadRequestException(
                        "Provide either a handoffId or targetLines to compare against.");
            }
            targetLines = request.targetLines();
            targetFields = request.targetFields() != null ? request.targetFields() : List.of();
            targetLabel = request.targetLabel() != null ? request.targetLabel() : "Document B";
        }

        return compareDocuments(
                request.referenceLabel() != null ? request.referenceLabel() : "Reference",
                request.referenceLines(),
                request.referenceFields() != null ? request.referenceFields() : List.of(),
                targetLabel, targetLines, targetFields, request.nameMatches());
    }

    /** Standalone HandoffCheck: extracts both uploaded files and compares them. Stateless. */
    public CompareResult compareFiles(MultipartFile fileA, MultipartFile fileB) {
        return compareDocuments(
                FILE_A_LABEL, extractLabelled(FILE_A_LABEL, fileA), List.of(),
                FILE_B_LABEL, extractLabelled(FILE_B_LABEL, fileB), List.of(), null);
    }

    /**
     * Reads an item list (CSV or Excel) for the New Handoff screen, for the customer to review before it is added
     * to the ordinary item table. Uses the same extraction as HandoffCheck. Read-only: no handoff is created or
     * changed here — the items go through the normal create/edit flow once the customer accepts them. Rows that
     * cannot become an item (no quantity, a quantity that is not above zero or has more than three decimals, a name
     * too long) are left out and counted; a repeated name is flagged, not merged.
     */
    public ItemImportPreview previewItemImport(MultipartFile file) {
        DocumentLineExtractor.Extraction found = extractor.extractReport(file, DocumentLineExtractor.SPREADSHEET_TYPES);
        int skipped = found.skippedRows();
        List<ItemImportPreview.Line> lines = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (DocumentLine line : found.lines()) {
            if (!isUsableAsItem(line)) {
                skipped++;
                continue;
            }
            lines.add(new ItemImportPreview.Line(line.name(), line.quantity(), !seen.add(normalizeName(line.name()))));
        }
        if (lines.isEmpty()) {
            throw new BadRequestException("No usable items were found in the file. Use a header row with an item (or "
                    + "name) column and a quantity column, and quantities above zero.");
        }
        return new ItemImportPreview(file.getOriginalFilename(), lines, skipped);
    }

    /** What a handoff item accepts: a name up to 300 characters and a quantity above zero, up to 3 decimals. */
    private static boolean isUsableAsItem(DocumentLine line) {
        BigDecimal q = line.quantity();
        return line.name().length() <= MAX_ITEM_NAME && q.signum() > 0
                && q.stripTrailingZeros().scale() <= MAX_QUANTITY_DECIMALS
                && q.precision() - q.scale() <= MAX_QUANTITY_INTEGER_DIGITS;
    }

    /** Extracts item/quantity lines from one uploaded file (the review step before comparing). */
    public List<DocumentLine> extractLines(MultipartFile file) {
        return extractor.extract(file);
    }

    /**
     * The same, with the columns the customer chose (for example by accepting an AI suggestion). Either no column is given — the
     * ordinary reading — or both the item column and the quantity column are, and the rows are then read by the ordinary routine
     * with those columns. Nothing the AI said is used except through this.
     */
    public List<DocumentLine> extractLines(MultipartFile file, Integer itemColumn, Integer quantityColumn, Integer headerRow) {
        if (itemColumn == null && quantityColumn == null && headerRow == null) {
            return extractLines(file);
        }
        if (itemColumn == null || quantityColumn == null) {
            throw new BadRequestException("Give both the item column and the quantity column.");
        }
        return extractor.extract(file, new DocumentLineExtractor.ColumnMapping(itemColumn, quantityColumn,
                headerRow == null ? -1 : headerRow));
    }

    /** The one comparison routine: every mode (inline, handoff target, files) ends up here. */
    private CompareResult compareDocuments(String referenceLabel,
                                           List<DocumentLine> referenceLines, List<DocumentField> referenceFields,
                                           String targetLabel,
                                           List<DocumentLine> targetLines, List<DocumentField> targetFields,
                                           List<NameMatch> nameMatches) {
        List<CompareResult.LineComparison> lineResults = compareLines(referenceLines, targetLines, nameMatches);
        List<CompareResult.FieldComparison> fieldResults = compareFields(referenceFields, targetFields);
        return new CompareResult(referenceLabel, targetLabel, summarize(lineResults), lineResults, fieldResults);
    }

    /** Names which file failed, so "No item and quantity rows…" is attributable. */
    private List<DocumentLine> extractLabelled(String label, MultipartFile file) {
        try {
            return extractor.extract(file);
        } catch (BadRequestException e) {
            throw new BadRequestException(label + ": " + e.getMessage());
        }
    }

    /**
     * Compares the two lists item by item: one row for each logical item, with both quantities side by side. Items are paired by
     * {@link #normalizeName} of the name the customer's accepted {@code nameMatches} give them (none, unless the customer accepted some),
     * so two spellings accepted as one item are one row — never a Missing and an Extra for the same item.
     */
    private List<CompareResult.LineComparison> compareLines(List<DocumentLine> reference, List<DocumentLine> target,
                                                            List<NameMatch> nameMatches) {
        Map<String, String> canonical = canonicalNames(nameMatches);
        Map<String, Item> refByKey = index(reference, canonical);
        Map<String, Item> tgtByKey = index(target, canonical);

        // Preserve reference order first, then any extra target lines.
        List<CompareResult.LineComparison> results = new ArrayList<>();
        for (Map.Entry<String, Item> e : refByKey.entrySet()) {
            Item ref = e.getValue();
            Item tgt = tgtByKey.get(e.getKey());
            if (tgt == null) {
                results.add(new CompareResult.LineComparison(ref.name(), ref.spelling(), null,
                        ref.quantity(), null, null, CompareResult.MatchStatus.MISSING_IN_TARGET));
            } else {
                boolean match = quantitiesEqual(ref.quantity(), tgt.quantity());
                results.add(new CompareResult.LineComparison(ref.name(), ref.spelling(), tgt.spelling(),
                        ref.quantity(), tgt.quantity(), difference(ref.quantity(), tgt.quantity()),
                        match ? CompareResult.MatchStatus.MATCH : CompareResult.MatchStatus.MISMATCH));
            }
        }
        for (Map.Entry<String, Item> e : tgtByKey.entrySet()) {
            if (!refByKey.containsKey(e.getKey())) {
                Item tgt = e.getValue();
                results.add(new CompareResult.LineComparison(tgt.name(), null, tgt.spelling(),
                        null, tgt.quantity(), null, CompareResult.MatchStatus.EXTRA_IN_TARGET));
            }
        }
        return results;
    }

    private List<CompareResult.FieldComparison> compareFields(List<DocumentField> reference,
                                                              List<DocumentField> target) {
        Map<String, DocumentField> tgtByKey = new LinkedHashMap<>();
        for (DocumentField f : target) {
            tgtByKey.put(normalize(f.label()), f);
        }
        List<CompareResult.FieldComparison> results = new ArrayList<>();
        for (DocumentField ref : reference) {
            DocumentField tgt = tgtByKey.get(normalize(ref.label()));
            if (tgt == null) {
                results.add(new CompareResult.FieldComparison(
                        ref.label(), ref.value(), null, CompareResult.MatchStatus.MISSING_IN_TARGET));
            } else {
                boolean match = valuesEqual(ref.value(), tgt.value());
                results.add(new CompareResult.FieldComparison(
                        ref.label(), ref.value(), tgt.value(),
                        match ? CompareResult.MatchStatus.MATCH : CompareResult.MatchStatus.MISMATCH));
            }
        }
        return results;
    }

    private CompareResult.Summary summarize(List<CompareResult.LineComparison> lines) {
        int matched = 0, mismatched = 0, missing = 0, extra = 0;
        for (var l : lines) {
            switch (l.status()) {
                case MATCH -> matched++;
                case MISMATCH -> mismatched++;
                case MISSING_IN_TARGET -> missing++;
                case EXTRA_IN_TARGET -> extra++;
            }
        }
        boolean allMatch = mismatched == 0 && missing == 0 && extra == 0;
        return new CompareResult.Summary(lines.size(), matched, mismatched, missing, extra, allMatch);
    }

    private List<DocumentField> deriveHandoffFields(Handoff handoff, List<DocumentField> provided) {
        List<DocumentField> fields = new ArrayList<>();
        if (handoff.getDueAt() != null) {
            fields.add(new DocumentField("Delivery date", handoff.getDueAt().toString()));
        }
        if (provided != null) {
            fields.addAll(provided);
        }
        return fields;
    }

    private DocumentLine toLine(HandoffItem item) {
        return new DocumentLine(item.getName(), item.getQuantity());
    }

    /** One item of one document: its name for the comparison, the total quantity (a repeated name adds up), and how this document wrote it if differently. */
    private record Item(String name, BigDecimal quantity, Set<String> writtenAs) {
        /** The spellings this document used that differ from {@code name}, or null when it wrote it the same way. */
        String spelling() {
            return writtenAs.isEmpty() ? null : String.join(" / ", writtenAs);
        }
    }

    /** The accepted spellings by the name they replace (as {@link #normalizeName} reads it). Nothing the customer sent is ever followed further than one step. */
    private static Map<String, String> canonicalNames(List<NameMatch> nameMatches) {
        Map<String, String> canonical = new LinkedHashMap<>();
        if (nameMatches != null) {
            for (NameMatch m : nameMatches) {
                String from = normalizeName(m.from());
                if (!from.isEmpty() && !from.equals(normalizeName(m.to()))) {
                    canonical.putIfAbsent(from, m.to().strip());
                }
            }
        }
        return canonical;
    }

    private Map<String, Item> index(List<DocumentLine> lines, Map<String, String> canonical) {
        Map<String, Item> map = new LinkedHashMap<>();
        for (DocumentLine line : lines) {
            String name = canonical.getOrDefault(normalizeName(line.name()), line.name());
            String key = normalizeName(name);
            Set<String> writtenAs = new LinkedHashSet<>();
            if (!normalizeName(line.name()).equals(key)) {
                writtenAs.add(line.name().strip());   // an accepted spelling match was used: keep what this document actually said
            }
            // If a name repeats, sum the quantities so totals compare correctly.
            map.merge(key, new Item(name, line.quantity(), writtenAs), (a, b) -> {
                Set<String> both = new LinkedHashSet<>(a.writtenAs());
                both.addAll(b.writtenAs());
                return new Item(a.name(), nullableAdd(a.quantity(), b.quantity()), both);
            });
        }
        return map;
    }

    private static boolean quantitiesEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
    }

    private static BigDecimal difference(BigDecimal reference, BigDecimal target) {
        return reference == null || target == null ? null : target.subtract(reference);
    }

    private static boolean valuesEqual(String a, String b) {
        return normalize(a).equals(normalize(b));
    }

    private static BigDecimal nullableAdd(BigDecimal a, BigDecimal b) {
        if (a == null) return b;
        if (b == null) return a;
        return a.add(b);
    }

    private static String normalize(String s) {
        return s == null ? "" : s.trim().toLowerCase();
    }

    /**
     * Item-name key: ignores case, surrounding/repeated whitespace and simple punctuation
     * ("Joker-Dress", "joker  dress" → "joker dress"). Deliberately not fuzzy: "Table" and
     * "Tables" stay different, so unrelated items are never silently merged — two spellings are one item only when the customer has
     * accepted that (see {@link NameMatch}).
     */
    static String normalizeName(String s) {
        if (s == null) return "";
        String folded = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}

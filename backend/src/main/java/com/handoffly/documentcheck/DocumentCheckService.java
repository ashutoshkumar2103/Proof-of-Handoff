package com.handoffly.documentcheck;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentField;
import com.handoffly.documentcheck.dto.DocumentLine;
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
import java.util.LinkedHashMap;
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
                targetLabel, targetLines, targetFields);
    }

    /** Standalone HandoffCheck: extracts both uploaded files and compares them. Stateless. */
    public CompareResult compareFiles(MultipartFile fileA, MultipartFile fileB) {
        return compareDocuments(
                FILE_A_LABEL, extractLabelled(FILE_A_LABEL, fileA), List.of(),
                FILE_B_LABEL, extractLabelled(FILE_B_LABEL, fileB), List.of());
    }

    /** Extracts item/quantity lines from one uploaded file (the review step before comparing). */
    public List<DocumentLine> extractLines(MultipartFile file) {
        return extractor.extract(file);
    }

    /** The one comparison routine: every mode (inline, handoff target, files) ends up here. */
    private CompareResult compareDocuments(String referenceLabel,
                                           List<DocumentLine> referenceLines, List<DocumentField> referenceFields,
                                           String targetLabel,
                                           List<DocumentLine> targetLines, List<DocumentField> targetFields) {
        List<CompareResult.LineComparison> lineResults = compareLines(referenceLines, targetLines);
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

    private List<CompareResult.LineComparison> compareLines(List<DocumentLine> reference,
                                                            List<DocumentLine> target) {
        Map<String, DocumentLine> refByKey = indexByName(reference);
        Map<String, DocumentLine> tgtByKey = indexByName(target);

        // Preserve reference order first, then any extra target lines.
        Map<String, Boolean> seen = new LinkedHashMap<>();
        List<CompareResult.LineComparison> results = new ArrayList<>();

        for (Map.Entry<String, DocumentLine> e : refByKey.entrySet()) {
            seen.put(e.getKey(), true);
            DocumentLine ref = e.getValue();
            DocumentLine tgt = tgtByKey.get(e.getKey());
            if (tgt == null) {
                results.add(new CompareResult.LineComparison(
                        ref.name(), ref.quantity(), null, null, CompareResult.MatchStatus.MISSING_IN_TARGET));
            } else {
                boolean match = quantitiesEqual(ref.quantity(), tgt.quantity());
                results.add(new CompareResult.LineComparison(
                        ref.name(), ref.quantity(), tgt.quantity(),
                        difference(ref.quantity(), tgt.quantity()),
                        match ? CompareResult.MatchStatus.MATCH : CompareResult.MatchStatus.MISMATCH));
            }
        }
        for (Map.Entry<String, DocumentLine> e : tgtByKey.entrySet()) {
            if (!seen.containsKey(e.getKey())) {
                DocumentLine tgt = e.getValue();
                results.add(new CompareResult.LineComparison(
                        tgt.name(), null, tgt.quantity(), null, CompareResult.MatchStatus.EXTRA_IN_TARGET));
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

    private Map<String, DocumentLine> indexByName(List<DocumentLine> lines) {
        Map<String, DocumentLine> map = new LinkedHashMap<>();
        for (DocumentLine line : lines) {
            // If a name repeats, sum the quantities so totals compare correctly.
            map.merge(normalizeName(line.name()), line, (a, b) ->
                    new DocumentLine(a.name(), nullableAdd(a.quantity(), b.quantity())));
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
     * "Tables" stay different, so unrelated items are never silently merged.
     */
    private static String normalizeName(String s) {
        if (s == null) return "";
        String folded = Normalizer.normalize(s, Normalizer.Form.NFKC).toLowerCase(Locale.ROOT);
        return folded.replaceAll("[^\\p{L}\\p{N}]+", " ").trim();
    }
}

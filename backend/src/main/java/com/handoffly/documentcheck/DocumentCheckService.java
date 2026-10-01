package com.handoffly.documentcheck;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.documentcheck.dto.CompareRequest;
import com.handoffly.documentcheck.dto.CompareResult;
import com.handoffly.documentcheck.dto.DocumentField;
import com.handoffly.documentcheck.dto.DocumentLine;
import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffItem;
import com.handoffly.handoff.HandoffService;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reusable comparison engine (HandoffCheck): compares a reference document against a
 * target that is either an existing handoff or another inline document. It never
 * duplicates handoff data — when a handoff is the target its live items/fields are read
 * directly. Results are computed and returned; persistence can be layered on later.
 */
@Service
public class DocumentCheckService {

    private final HandoffService handoffService;

    public DocumentCheckService(HandoffService handoffService) {
        this.handoffService = handoffService;
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

        List<CompareResult.LineComparison> lineResults = compareLines(request.referenceLines(), targetLines);
        List<CompareResult.FieldComparison> fieldResults = compareFields(
                request.referenceFields() != null ? request.referenceFields() : List.of(), targetFields);

        return new CompareResult(
                request.referenceLabel() != null ? request.referenceLabel() : "Reference",
                targetLabel,
                summarize(lineResults),
                lineResults,
                fieldResults);
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
                        ref.name(), ref.quantity(), null, CompareResult.MatchStatus.MISSING_IN_TARGET));
            } else {
                boolean match = quantitiesEqual(ref.quantity(), tgt.quantity());
                results.add(new CompareResult.LineComparison(
                        ref.name(), ref.quantity(), tgt.quantity(),
                        match ? CompareResult.MatchStatus.MATCH : CompareResult.MatchStatus.MISMATCH));
            }
        }
        for (Map.Entry<String, DocumentLine> e : tgtByKey.entrySet()) {
            if (!seen.containsKey(e.getKey())) {
                DocumentLine tgt = e.getValue();
                results.add(new CompareResult.LineComparison(
                        tgt.name(), null, tgt.quantity(), CompareResult.MatchStatus.EXTRA_IN_TARGET));
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
            map.merge(normalize(line.name()), line, (a, b) ->
                    new DocumentLine(a.name(), nullableAdd(a.quantity(), b.quantity())));
        }
        return map;
    }

    private static boolean quantitiesEqual(BigDecimal a, BigDecimal b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        return a.compareTo(b) == 0;
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
}

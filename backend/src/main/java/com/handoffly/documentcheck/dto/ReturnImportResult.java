package com.handoffly.documentcheck.dto;

import java.math.BigDecimal;
import java.util.List;

/**
 * Lines extracted from an uploaded file, each matched (deterministically) against the items
 * of one handoff. Nothing is persisted — the client uses this to prefill the existing return
 * form, and the normal return API still validates and records the return.
 */
public record ReturnImportResult(String fileName, List<Row> rows) {

    public enum MatchState { MATCHED, AMBIGUOUS, UNMATCHED }

    /**
     * @param owedQuantity what can still come back for the matched item (remaining plus any
     *                     currently missing), or null when there is no single match
     */
    public record Row(
            String importedName,
            BigDecimal importedQuantity,
            MatchState match,
            Long itemId,
            String itemName,
            BigDecimal owedQuantity
    ) {}
}

package com.handoffly.returns;

import com.handoffly.handoff.Handoff;
import com.handoffly.handoff.HandoffItem;
import com.handoffly.handoff.ItemCondition;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-side aggregation of returns — the single place "how much came back / is missing"
 * is computed, so the numbers never drift.
 *
 * <p>Model: each confirmed line is either RETURNED (any condition except MISSING) or
 * MISSING. For an item,
 * <pre>
 *   returnedGood   = Σ returned line quantities
 *   declaredMissing = Σ MISSING line quantities
 *   netMissing     = min(declaredMissing, outgoing − returnedGood)   // never negative
 *   remaining      = outgoing − returnedGood − netMissing
 * </pre>
 * So returning a unit that was previously missing simply increases returnedGood, which
 * reduces netMissing — no special "recovered" record is needed; any un-returned remainder
 * stays missing.
 */
@Service
@Transactional(readOnly = true)
public class ReturnQueryService {

    private final ReturnEventRepository returnEventRepository;
    private final ReturnLineRepository returnLineRepository;

    public ReturnQueryService(ReturnEventRepository returnEventRepository,
                              ReturnLineRepository returnLineRepository) {
        this.returnEventRepository = returnEventRepository;
        this.returnLineRepository = returnLineRepository;
    }

    /** itemId -> quantity returned (all conditions EXCEPT missing) in confirmed events. */
    public Map<Long, BigDecimal> confirmedReturnedByItem(Long handoffId) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (ReturnEvent event : eventsForHandoff(handoffId)) {
            if (!event.isConfirmed()) continue;
            for (ReturnLine line : event.getLines()) {
                if (line.getCondition() == ItemCondition.MISSING) continue;
                map.merge(line.getItem().getId(), line.getQuantity(), BigDecimal::add);
            }
        }
        return map;
    }

    /** itemId -> total quantity DECLARED missing (raw MISSING lines) in confirmed events. */
    public Map<Long, BigDecimal> declaredMissingByItem(Long handoffId) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (ReturnEvent event : eventsForHandoff(handoffId)) {
            if (!event.isConfirmed()) continue;
            for (ReturnLine line : event.getLines()) {
                if (line.getCondition() == ItemCondition.MISSING) {
                    map.merge(line.getItem().getId(), line.getQuantity(), BigDecimal::add);
                }
            }
        }
        return map;
    }

    /** itemId -> total quantity returned in UNCONFIRMED events (awaiting confirmation). */
    public Map<Long, BigDecimal> pendingReturnedByItem(Long handoffId) {
        return toMap(returnLineRepository.sumByHandoffAndConfirmed(handoffId, false));
    }

    public List<ReturnEvent> eventsForHandoff(Long handoffId) {
        return returnEventRepository.findByHandoffIdOrderByOccurredAtAscIdAsc(handoffId);
    }

    /** The single net-missing formula: declared missing, capped by what is not yet returned. */
    public static BigDecimal netMissing(BigDecimal outgoing, BigDecimal returnedGood, BigDecimal declaredMissing) {
        BigDecimal owed = outgoing.subtract(returnedGood);
        if (owed.signum() < 0) owed = BigDecimal.ZERO;
        BigDecimal net = declaredMissing.min(owed);
        return net.signum() < 0 ? BigDecimal.ZERO : net;
    }

    /** itemId -> net missing quantity (positive entries only), for a loaded handoff. */
    public Map<Long, BigDecimal> netMissingByItem(Handoff handoff) {
        Map<Long, BigDecimal> returnedGood = confirmedReturnedByItem(handoff.getId());
        Map<Long, BigDecimal> declared = declaredMissingByItem(handoff.getId());
        Map<Long, BigDecimal> net = new HashMap<>();
        for (HandoffItem item : handoff.getItems()) {
            BigDecimal n = netMissing(item.getQuantity(),
                    returnedGood.getOrDefault(item.getId(), BigDecimal.ZERO),
                    declared.getOrDefault(item.getId(), BigDecimal.ZERO));
            if (n.signum() > 0) net.put(item.getId(), n);
        }
        return net;
    }

    /** Total net missing across all items of a handoff. */
    public BigDecimal netMissingTotal(Handoff handoff) {
        BigDecimal total = BigDecimal.ZERO;
        for (BigDecimal q : netMissingByItem(handoff).values()) total = total.add(q);
        return total;
    }

    private Map<Long, BigDecimal> toMap(List<ReturnLineRepository.ItemReturnTotal> totals) {
        Map<Long, BigDecimal> map = new HashMap<>();
        for (var t : totals) {
            map.put(t.getItemId(), t.getTotal() == null ? BigDecimal.ZERO : t.getTotal());
        }
        return map;
    }
}

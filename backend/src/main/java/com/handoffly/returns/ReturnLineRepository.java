package com.handoffly.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.handoffly.handoff.ItemCondition;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

public interface ReturnLineRepository extends JpaRepository<ReturnLine, Long> {

    /** Returned totals per item for a handoff, filtered by confirmation state. */
    @Query("select rl.item.id as itemId, sum(rl.quantity) as total "
            + "from ReturnLine rl "
            + "where rl.returnEvent.handoff.id = :handoffId "
            + "and rl.returnEvent.confirmed = :confirmed "
            + "group by rl.item.id")
    List<ItemReturnTotal> sumByHandoffAndConfirmed(@Param("handoffId") Long handoffId,
                                                   @Param("confirmed") boolean confirmed);

    /**
     * What a customer got back within a period: the quantity on confirmed returns (any condition except MISSING)
     * made against their handoffs, counted on the day the return was made.
     */
    @Query("select coalesce(sum(rl.quantity), 0) from ReturnLine rl "
            + "where rl.returnEvent.handoff.owner.id = :ownerId and rl.returnEvent.confirmed = true "
            + "and rl.condition <> :missing "
            + "and rl.returnEvent.occurredAt >= :from and rl.returnEvent.occurredAt < :to")
    BigDecimal sumReturnedForOwnerBetween(@Param("ownerId") Long ownerId, @Param("missing") ItemCondition missing,
                                          @Param("from") Instant from, @Param("to") Instant to);

    /** Projection: total returned quantity for a single handoff item. */
    interface ItemReturnTotal {
        Long getItemId();
        BigDecimal getTotal();
    }
}

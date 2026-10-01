package com.handoffly.returns;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
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

    /** Projection: total returned quantity for a single handoff item. */
    interface ItemReturnTotal {
        Long getItemId();
        BigDecimal getTotal();
    }
}

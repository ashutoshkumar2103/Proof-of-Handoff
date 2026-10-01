package com.handoffly.handoff;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

public interface HandoffRepository
        extends JpaRepository<Handoff, Long>, JpaSpecificationExecutor<Handoff> {

    Optional<Handoff> findByPublicCode(String publicCode);

    @EntityGraph(attributePaths = "items")
    Optional<Handoff> findWithItemsById(Long id);

    @EntityGraph(attributePaths = "items")
    Optional<Handoff> findWithItemsByPublicCode(String publicCode);

    /** Dashboard status tallies for a given owner. */
    @Query("select h.status as status, count(h) as count from Handoff h "
            + "where h.owner.id = :ownerId group by h.status")
    List<StatusCount> countByStatusForOwner(@Param("ownerId") Long ownerId);

    /** Count of overdue handoffs: past due date while still active with the recipient. */
    long countByOwnerIdAndStatusInAndDueAtBefore(
            Long ownerId, Collection<HandoffStatus> statuses, Instant before);

    /** Projection for status tallies. */
    interface StatusCount {
        HandoffStatus getStatus();
        long getCount();
    }
}

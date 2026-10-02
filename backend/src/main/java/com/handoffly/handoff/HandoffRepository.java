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

    @EntityGraph(attributePaths = "items")
    Optional<Handoff> findWithItemsById(Long id);

    /** A customer's handoffs in any of these statuses, with their items (for reminders and summaries). */
    @EntityGraph(attributePaths = "items")
    List<Handoff> findByOwnerIdAndStatusIn(Long ownerId, Collection<HandoffStatus> statuses);

    /** A customer's handoffs created in a period. */
    List<Handoff> findByOwnerIdAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(Long ownerId, Instant from, Instant to);

    /** A customer's handoffs that went out (were submitted) in a period, with their items. */
    @EntityGraph(attributePaths = "items")
    List<Handoff> findByOwnerIdAndOutgoingAtGreaterThanEqualAndOutgoingAtLessThan(Long ownerId, Instant from, Instant to);

    /** A customer's own handoff by its reference (references are unique per account, not globally). */
    Optional<Handoff> findByOwnerIdAndPublicCodeIgnoreCase(Long ownerId, String publicCode);

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

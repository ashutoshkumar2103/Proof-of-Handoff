package com.handoffly.support;

import com.handoffly.user.SubscriptionPlan;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Collection;
import java.util.Optional;

public interface SupportTicketRepository
        extends JpaRepository<SupportTicket, Long>, JpaSpecificationExecutor<SupportTicket> {

    /** Lists load the customer with the ticket, so a page of tickets costs one query rather than one per row. */
    @Override
    @EntityGraph(attributePaths = "account")
    Page<SupportTicket> findAll(Specification<SupportTicket> spec, Pageable pageable);

    @EntityGraph(attributePaths = "account")
    Optional<SupportTicket> findByTicketCode(String ticketCode);

    /** A customer's own ticket; someone else's ticket is simply not found. */
    @EntityGraph(attributePaths = "account")
    Optional<SupportTicket> findByTicketCodeAndAccountId(String ticketCode, Long accountId);

    long countByStatus(TicketStatus status);

    long countByAccountIdAndStatusIn(Long accountId, Collection<TicketStatus> statuses);

    /**
     * How many different customers on any of the given plans, with a subscription that is still active at {@code now},
     * have a ticket in one of the given statuses.
     */
    @Query("select count(distinct t.account.id) from SupportTicket t "
            + "where t.status in :statuses and t.account.subscriptionPlan in :plans "
            + "and (t.account.planValidUntil is null or t.account.planValidUntil > :now)")
    long countCustomersWithTickets(@Param("statuses") Collection<TicketStatus> statuses,
                                   @Param("plans") Collection<SubscriptionPlan> plans,
                                   @Param("now") Instant now);
}

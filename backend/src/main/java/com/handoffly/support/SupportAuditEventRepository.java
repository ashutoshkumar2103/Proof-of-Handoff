package com.handoffly.support;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Deliberately NOT a {@code JpaRepository}: the audit trail can be added to and read, never updated or
 * deleted through the application.
 */
public interface SupportAuditEventRepository extends Repository<SupportAuditEvent, Long> {

    SupportAuditEvent save(SupportAuditEvent event);

    /** A customer's most recent changes, newest first. */
    @EntityGraph(attributePaths = {"staff", "customer", "targetStaff"})
    List<SupportAuditEvent> findTop10ByCustomerIdOrderByIdDesc(Long customerId);

    /** The whole trail, newest first (the page request must not carry a sort of its own). */
    @EntityGraph(attributePaths = {"staff", "customer", "targetStaff"})
    Page<SupportAuditEvent> findAllByOrderByIdDesc(Pageable pageable);
}

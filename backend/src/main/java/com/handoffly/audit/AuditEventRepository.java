package com.handoffly.audit;

import org.springframework.data.jpa.repository.JpaRepository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

public interface AuditEventRepository extends JpaRepository<AuditEvent, Long> {

    List<AuditEvent> findByHandoffIdOrderByCreatedAtAscIdAsc(Long handoffId);

    /** Events of one kind, on any of these handoffs, within a period. */
    List<AuditEvent> findByHandoffIdInAndTypeAndCreatedAtGreaterThanEqualAndCreatedAtLessThan(
            Collection<Long> handoffIds, AuditEventType type, Instant from, Instant to);
}

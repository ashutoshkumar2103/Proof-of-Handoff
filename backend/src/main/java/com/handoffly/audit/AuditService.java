package com.handoffly.audit;

import com.handoffly.audit.dto.AuditEventResponse;
import com.handoffly.common.domain.ActorType;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Records and reads lifecycle events. Writes participate in the caller's transaction
 * so an event is never persisted for an operation that ultimately rolls back.
 */
@Service
public class AuditService {

    private final AuditEventRepository repository;

    public AuditService(AuditEventRepository repository) {
        this.repository = repository;
    }

    @Transactional
    public void record(Long handoffId, AuditEventType type, ActorType actorType,
                       String actorRef, String message) {
        repository.save(new AuditEvent(handoffId, type, actorType, actorRef, message));
    }

    @Transactional(readOnly = true)
    public List<AuditEventResponse> listForHandoff(Long handoffId) {
        return repository.findByHandoffIdOrderByCreatedAtAscIdAsc(handoffId).stream()
                .map(AuditEventResponse::from)
                .toList();
    }
}

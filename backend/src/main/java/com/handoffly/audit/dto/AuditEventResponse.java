package com.handoffly.audit.dto;

import com.handoffly.audit.AuditEvent;
import com.handoffly.audit.AuditEventType;
import com.handoffly.common.domain.ActorType;

import java.time.Instant;

public record AuditEventResponse(
        Long id,
        AuditEventType type,
        ActorType actorType,
        String actorRef,
        String message,
        Instant at
) {
    public static AuditEventResponse from(AuditEvent e) {
        return new AuditEventResponse(
                e.getId(), e.getType(), e.getActorType(), e.getActorRef(), e.getMessage(), e.getCreatedAt());
    }
}

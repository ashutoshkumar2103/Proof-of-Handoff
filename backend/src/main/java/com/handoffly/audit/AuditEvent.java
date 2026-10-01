package com.handoffly.audit;

import com.handoffly.common.domain.ActorType;
import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

/**
 * An append-only record of something that happened to a handoff. Never mutated or
 * deleted. Referenced by {@code handoff_id} rather than a JPA relation to keep audit
 * a low-coupling, cross-cutting concern.
 */
@Entity
@Table(name = "audit_event")
public class AuditEvent extends BaseEntity {

    @Column(name = "handoff_id", nullable = false)
    private Long handoffId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type", nullable = false, length = 40)
    private AuditEventType type;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, length = 20)
    private ActorType actorType;

    @Column(name = "actor_ref", length = 200)
    private String actorRef;

    @Column(nullable = false, length = 1000)
    private String message;

    protected AuditEvent() {
        // JPA
    }

    public AuditEvent(Long handoffId, AuditEventType type, ActorType actorType, String actorRef, String message) {
        this.handoffId = handoffId;
        this.type = type;
        this.actorType = actorType;
        this.actorRef = actorRef;
        this.message = message;
    }

    public Long getHandoffId() { return handoffId; }
    public AuditEventType getType() { return type; }
    public ActorType getActorType() { return actorType; }
    public String getActorRef() { return actorRef; }
    public String getMessage() { return message; }
}

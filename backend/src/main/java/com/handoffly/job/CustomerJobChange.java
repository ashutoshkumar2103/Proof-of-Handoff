package com.handoffly.job;

import com.handoffly.common.domain.ActorType;
import com.handoffly.common.domain.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

import java.time.Instant;

/**
 * One successful change to a customer's job settings, and who made it. Append-only: it is immutable, has no setters, and the repository
 * offers no update or delete, so the record of who changed what is exactly what happened. It is written in the same transaction as the
 * change itself, so a change that is rolled back leaves no record and a recorded change always happened.
 * The account (whose job it is) and the actor (who changed it) are separate ids, so a different kind of actor can be added later.
 */
@Entity
@Immutable
@Table(name = "customer_job_change")
public class CustomerJobChange extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private Long accountId;

    @Enumerated(EnumType.STRING)
    @Column(name = "job_type", nullable = false, updatable = false, length = 30)
    private JobType type;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, updatable = false, length = 30)
    private JobChangeAction action;

    @Column(name = "previous_value", updatable = false, length = 200)
    private String previousValue;

    @Column(name = "new_value", nullable = false, updatable = false, length = 200)
    private String newValue;

    @Column(name = "actor_user_id", nullable = false, updatable = false)
    private Long actorUserId;

    /** The actor's name when they made the change; a later rename does not rewrite it. */
    @Column(name = "actor_name", nullable = false, updatable = false, length = 150)
    private String actorName;

    @Enumerated(EnumType.STRING)
    @Column(name = "actor_type", nullable = false, updatable = false, length = 20)
    private ActorType actorType;

    @Column(name = "changed_at", nullable = false, updatable = false)
    private Instant changedAt;

    protected CustomerJobChange() {
        // JPA
    }

    public CustomerJobChange(Long accountId, JobType type, JobChangeAction action, String previousValue, String newValue,
                             JobActor actor, Instant changedAt) {
        this.accountId = accountId;
        this.type = type;
        this.action = action;
        this.previousValue = previousValue;
        this.newValue = newValue;
        this.actorUserId = actor.id();
        this.actorName = actor.name();
        this.actorType = actor.type();
        this.changedAt = changedAt;
    }

    public Long getAccountId() { return accountId; }
    public JobType getType() { return type; }
    public JobChangeAction getAction() { return action; }
    public String getPreviousValue() { return previousValue; }
    public String getNewValue() { return newValue; }
    public Long getActorUserId() { return actorUserId; }
    public String getActorName() { return actorName; }
    public ActorType getActorType() { return actorType; }
    public Instant getChangedAt() { return changedAt; }
}

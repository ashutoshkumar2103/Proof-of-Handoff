package com.handoffly.returns;

import com.handoffly.common.domain.ActorType;
import com.handoffly.common.domain.BaseEntity;
import com.handoffly.handoff.Handoff;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * A single return event recorded against a handoff. Multiple return events accumulate
 * over time on the SAME handoff — outgoing quantities are never overwritten. A return
 * only counts toward remaining once {@code confirmed}: owner-entered returns are
 * auto-confirmed; recipient-entered returns await owner confirmation.
 */
@Entity
@Table(name = "return_event")
public class ReturnEvent extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handoff_id", nullable = false)
    private Handoff handoff;

    /** When the physical/actual return happened (may predate the record). */
    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "entered_by_type", nullable = false, length = 20)
    private ActorType enteredByType;

    /** Free-text reference to who entered it (user email or recipient name). */
    @Column(name = "entered_by_ref", length = 200)
    private String enteredByRef;

    @Column(length = 1000)
    private String note;

    @Column(nullable = false)
    private boolean confirmed;

    @Column(name = "confirmed_at")
    private Instant confirmedAt;

    @Column(name = "confirmed_by_ref", length = 200)
    private String confirmedByRef;

    @OneToMany(mappedBy = "returnEvent", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("id ASC")
    private List<ReturnLine> lines = new ArrayList<>();

    protected ReturnEvent() {
        // JPA
    }

    public ReturnEvent(Handoff handoff, Instant occurredAt, ActorType enteredByType, String enteredByRef) {
        this.handoff = handoff;
        this.occurredAt = occurredAt;
        this.enteredByType = enteredByType;
        this.enteredByRef = enteredByRef;
    }

    public void addLine(ReturnLine line) {
        line.setReturnEvent(this);
        lines.add(line);
    }

    public Handoff getHandoff() { return handoff; }

    public Instant getOccurredAt() { return occurredAt; }
    public void setOccurredAt(Instant occurredAt) { this.occurredAt = occurredAt; }

    public ActorType getEnteredByType() { return enteredByType; }
    public String getEnteredByRef() { return enteredByRef; }

    public String getNote() { return note; }
    public void setNote(String note) { this.note = note; }

    public boolean isConfirmed() { return confirmed; }
    public void setConfirmed(boolean confirmed) { this.confirmed = confirmed; }

    public Instant getConfirmedAt() { return confirmedAt; }
    public void setConfirmedAt(Instant confirmedAt) { this.confirmedAt = confirmedAt; }

    public String getConfirmedByRef() { return confirmedByRef; }
    public void setConfirmedByRef(String confirmedByRef) { this.confirmedByRef = confirmedByRef; }

    public List<ReturnLine> getLines() { return lines; }
}

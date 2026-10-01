package com.handoffly.returns;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.handoff.HandoffItem;
import com.handoffly.handoff.ItemCondition;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import java.math.BigDecimal;

/**
 * A returned quantity of one specific handoff item within a return event, with its
 * structured condition and optional free-form note.
 */
@Entity
@Table(name = "return_line")
public class ReturnLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_event_id", nullable = false)
    private ReturnEvent returnEvent;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handoff_item_id", nullable = false)
    private HandoffItem item;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition condition = ItemCondition.GOOD;

    @Column(length = 1000)
    private String note;

    protected ReturnLine() {
        // JPA
    }

    public ReturnLine(HandoffItem item, BigDecimal quantity, ItemCondition condition, String note) {
        this.item = item;
        this.quantity = quantity;
        this.condition = condition == null ? ItemCondition.GOOD : condition;
        this.note = note;
    }

    void setReturnEvent(ReturnEvent returnEvent) { this.returnEvent = returnEvent; }
    public ReturnEvent getReturnEvent() { return returnEvent; }

    public HandoffItem getItem() { return item; }

    public BigDecimal getQuantity() { return quantity; }

    public ItemCondition getCondition() { return condition; }

    public String getNote() { return note; }
}

package com.handoffly.handoff;

import com.handoffly.common.domain.BaseEntity;
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
 * A single outgoing line on a handoff. Supports both simple quantities (200 bedsheets)
 * and individually identifiable items (a drill with a serial number). The outgoing
 * {@code quantity} is never mutated after submission — returns are tracked separately.
 */
@Entity
@Table(name = "handoff_item")
public class HandoffItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "handoff_id", nullable = false)
    private Handoff handoff;

    @Column(nullable = false, length = 300)
    private String name;

    @Column(length = 1000)
    private String description;

    @Column(length = 80)
    private String sku;

    @Column(name = "serial_number", length = 120)
    private String serialNumber;

    @Column(name = "asset_number", length = 120)
    private String assetNumber;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(length = 30)
    private String unit;

    @Column(length = 1000)
    private String notes;

    /** Condition of the item at handover (returns capture their own condition separately). */
    @Enumerated(EnumType.STRING)
    @Column(name = "item_condition", nullable = false, length = 20)
    private ItemCondition condition = ItemCondition.GOOD;

    @Column(name = "sort_order", nullable = false)
    private int position;

    protected HandoffItem() {
        // JPA
    }

    public HandoffItem(String name, BigDecimal quantity) {
        this.name = name;
        this.quantity = quantity;
    }

    void setHandoff(Handoff handoff) { this.handoff = handoff; }
    public Handoff getHandoff() { return handoff; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getDescription() { return description; }
    public void setDescription(String description) { this.description = description; }

    public String getSku() { return sku; }
    public void setSku(String sku) { this.sku = sku; }

    public String getSerialNumber() { return serialNumber; }
    public void setSerialNumber(String serialNumber) { this.serialNumber = serialNumber; }

    public String getAssetNumber() { return assetNumber; }
    public void setAssetNumber(String assetNumber) { this.assetNumber = assetNumber; }

    public BigDecimal getQuantity() { return quantity; }
    public void setQuantity(BigDecimal quantity) { this.quantity = quantity; }

    public String getUnit() { return unit; }
    public void setUnit(String unit) { this.unit = unit; }

    public String getNotes() { return notes; }
    public void setNotes(String notes) { this.notes = notes; }

    public ItemCondition getCondition() { return condition; }
    public void setCondition(ItemCondition condition) { this.condition = condition; }

    public int getPosition() { return position; }
    public void setPosition(int position) { this.position = position; }
}

package com.handoffly.handoff;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.user.User;
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
 * A lifecycle record of responsibility moving from a sender to a recipient, kept open
 * until the items are returned. Outgoing items are immutable after submission; returns
 * are recorded as separate events against this same handoff.
 */
@Entity
@Table(name = "handoff")
public class Handoff extends BaseEntity {

    @Column(name = "public_code", nullable = false, unique = true, length = 20)
    private String publicCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "owner_user_id", nullable = false)
    private User owner;

    @Column(nullable = false, length = 200)
    private String title;

    @Column(length = 2000)
    private String purpose;

    /** Domain/template category, e.g. "EVENT", "IT_EQUIPMENT" — free text, no per-domain code. */
    @Column(length = 60)
    private String category;

    @Column(name = "sender_name", nullable = false, length = 200)
    private String senderName;

    @Column(name = "sender_organization", length = 200)
    private String senderOrganization;

    @Column(name = "recipient_name", nullable = false, length = 200)
    private String recipientName;

    @Column(name = "recipient_email", nullable = false, length = 255)
    private String recipientEmail;

    @Column(name = "recipient_phone", length = 40)
    private String recipientPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private HandoffStatus status = HandoffStatus.DRAFT;

    @Column(name = "outgoing_at")
    private Instant outgoingAt;

    @Column(name = "acceptance_at")
    private Instant acceptanceAt;

    /** Optional expected-return deadline, used to flag OVERDUE. */
    @Column(name = "due_at")
    private Instant dueAt;

    /** Typed acknowledgement name (NOT a legally binding e-signature). */
    @Column(name = "acknowledgement_name", length = 200)
    private String acknowledgementName;

    @Column(name = "acknowledged_at")
    private Instant acknowledgedAt;

    @Column(name = "rejection_reason", length = 1000)
    private String rejectionReason;

    /** Set when the owner asks the recipient to confirm items reported missing. */
    @Column(name = "missing_confirmation_requested_at")
    private Instant missingConfirmationRequestedAt;

    /** Set when the recipient confirms the missing items — required before closing. */
    @Column(name = "missing_confirmed_at")
    private Instant missingConfirmedAt;

    /** Typed acknowledgement name captured when the recipient confirms the missing items. */
    @Column(name = "missing_confirmed_by_name", length = 200)
    private String missingConfirmedByName;

    /** Set when the recipient requests to wait for return instead of confirming missing. */
    @Column(name = "return_wait_requested_at")
    private Instant returnWaitRequestedAt;

    @Column(name = "return_wait_reason", length = 1000)
    private String returnWaitReason;

    @Column(name = "return_wait_requested_by_name", length = 200)
    private String returnWaitRequestedByName;

    @OneToMany(mappedBy = "handoff", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("position ASC, id ASC")
    private List<HandoffItem> items = new ArrayList<>();

    protected Handoff() {
        // JPA
    }

    public Handoff(String publicCode, User owner, String title) {
        this.publicCode = publicCode;
        this.owner = owner;
        this.title = title;
    }

    public void addItem(HandoffItem item) {
        item.setHandoff(this);
        item.setPosition(items.size());
        items.add(item);
    }

    public void clearItems() {
        items.clear();
    }

    public boolean isOwnedBy(Long userId) {
        return owner != null && owner.getId().equals(userId);
    }

    public String getPublicCode() { return publicCode; }
    public void setPublicCode(String publicCode) { this.publicCode = publicCode; }

    public User getOwner() { return owner; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getPurpose() { return purpose; }
    public void setPurpose(String purpose) { this.purpose = purpose; }

    public String getCategory() { return category; }
    public void setCategory(String category) { this.category = category; }

    public String getSenderName() { return senderName; }
    public void setSenderName(String senderName) { this.senderName = senderName; }

    public String getSenderOrganization() { return senderOrganization; }
    public void setSenderOrganization(String senderOrganization) { this.senderOrganization = senderOrganization; }

    public String getRecipientName() { return recipientName; }
    public void setRecipientName(String recipientName) { this.recipientName = recipientName; }

    public String getRecipientEmail() { return recipientEmail; }
    public void setRecipientEmail(String recipientEmail) { this.recipientEmail = recipientEmail; }

    public String getRecipientPhone() { return recipientPhone; }
    public void setRecipientPhone(String recipientPhone) { this.recipientPhone = recipientPhone; }

    public HandoffStatus getStatus() { return status; }
    public void setStatus(HandoffStatus status) { this.status = status; }

    public Instant getOutgoingAt() { return outgoingAt; }
    public void setOutgoingAt(Instant outgoingAt) { this.outgoingAt = outgoingAt; }

    public Instant getAcceptanceAt() { return acceptanceAt; }
    public void setAcceptanceAt(Instant acceptanceAt) { this.acceptanceAt = acceptanceAt; }

    public Instant getDueAt() { return dueAt; }
    public void setDueAt(Instant dueAt) { this.dueAt = dueAt; }

    public String getAcknowledgementName() { return acknowledgementName; }
    public void setAcknowledgementName(String acknowledgementName) { this.acknowledgementName = acknowledgementName; }

    public Instant getAcknowledgedAt() { return acknowledgedAt; }
    public void setAcknowledgedAt(Instant acknowledgedAt) { this.acknowledgedAt = acknowledgedAt; }

    public String getRejectionReason() { return rejectionReason; }
    public void setRejectionReason(String rejectionReason) { this.rejectionReason = rejectionReason; }

    public Instant getMissingConfirmationRequestedAt() { return missingConfirmationRequestedAt; }
    public void setMissingConfirmationRequestedAt(Instant at) { this.missingConfirmationRequestedAt = at; }

    public Instant getMissingConfirmedAt() { return missingConfirmedAt; }
    public void setMissingConfirmedAt(Instant at) { this.missingConfirmedAt = at; }

    public String getMissingConfirmedByName() { return missingConfirmedByName; }
    public void setMissingConfirmedByName(String name) { this.missingConfirmedByName = name; }

    public Instant getReturnWaitRequestedAt() { return returnWaitRequestedAt; }
    public void setReturnWaitRequestedAt(Instant at) { this.returnWaitRequestedAt = at; }

    public String getReturnWaitReason() { return returnWaitReason; }
    public void setReturnWaitReason(String reason) { this.returnWaitReason = reason; }

    public String getReturnWaitRequestedByName() { return returnWaitRequestedByName; }
    public void setReturnWaitRequestedByName(String name) { this.returnWaitRequestedByName = name; }

    public List<HandoffItem> getItems() { return items; }
}

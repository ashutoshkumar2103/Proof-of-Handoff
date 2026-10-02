package com.handoffly.support;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.user.User;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

/**
 * A customer's request for help. It belongs to one customer account and carries only what support
 * needs to answer it — never the customer's handoff data. Every reply bumps {@code messageCount},
 * which also refreshes {@code updatedAt}, so "last updated" always reflects real activity.
 */
@Entity
@Table(name = "support_ticket")
public class SupportTicket extends BaseEntity {

    @Column(name = "ticket_code", nullable = false, unique = true, updatable = false, length = 20)
    private String ticketCode;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "account_user_id", nullable = false, updatable = false)
    private User account;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketCategory category;

    @Enumerated(EnumType.STRING)
    @Column(name = "contact_method", nullable = false, length = 20)
    private ContactMethod contactMethod;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(nullable = false, length = 5000)
    private String description;

    /** The customer's own handoff reference (e.g. AV-3), kept as text: support sees it, never the handoff. */
    @Column(name = "handoff_reference", length = 20)
    private String handoffReference;

    @Column(name = "contact_phone", length = 40)
    private String contactPhone;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private TicketStatus status = TicketStatus.OPEN;

    @Column(name = "message_count", nullable = false)
    private int messageCount;

    protected SupportTicket() {
        // JPA
    }

    public SupportTicket(String ticketCode, User account, TicketCategory category, ContactMethod contactMethod,
                         String subject, String description, String handoffReference, String contactPhone) {
        this.ticketCode = ticketCode;
        this.account = account;
        this.category = category;
        this.contactMethod = contactMethod;
        this.subject = subject;
        this.description = description;
        this.handoffReference = handoffReference;
        this.contactPhone = contactPhone;
    }

    public void recordMessage() {
        messageCount++;
    }

    public String getTicketCode() { return ticketCode; }
    public User getAccount() { return account; }
    public TicketCategory getCategory() { return category; }
    public ContactMethod getContactMethod() { return contactMethod; }
    public String getSubject() { return subject; }
    public String getDescription() { return description; }
    public String getHandoffReference() { return handoffReference; }
    public String getContactPhone() { return contactPhone; }
    public TicketStatus getStatus() { return status; }
    public void setStatus(TicketStatus status) { this.status = status; }
    public int getMessageCount() { return messageCount; }
}

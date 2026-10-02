package com.handoffly.support;

import com.handoffly.common.domain.BaseEntity;
import com.handoffly.support.staff.SupportStaff;
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
 * One reply on a ticket, from the customer or from a support staff member (two different identities, so
 * the author is one of two references). Append-only.
 */
@Entity
@Table(name = "support_ticket_message")
public class SupportTicketMessage extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "ticket_id", nullable = false, updatable = false)
    private SupportTicket ticket;

    /** Set when a customer wrote the message. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_user_id", updatable = false)
    private User customerAuthor;

    /** Set when a support staff member wrote the message. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_staff_id", updatable = false)
    private SupportStaff staffAuthor;

    @Enumerated(EnumType.STRING)
    @Column(name = "author_role", nullable = false, length = 20)
    private MessageAuthor authorRole;

    @Column(nullable = false, length = 5000)
    private String body;

    protected SupportTicketMessage() {
        // JPA
    }

    private SupportTicketMessage(SupportTicket ticket, User customerAuthor, SupportStaff staffAuthor,
                                 MessageAuthor authorRole, String body) {
        this.ticket = ticket;
        this.customerAuthor = customerAuthor;
        this.staffAuthor = staffAuthor;
        this.authorRole = authorRole;
        this.body = body;
    }

    public static SupportTicketMessage byCustomer(SupportTicket ticket, User customer, String body) {
        return new SupportTicketMessage(ticket, customer, null, MessageAuthor.CUSTOMER, body);
    }

    public static SupportTicketMessage byStaff(SupportTicket ticket, SupportStaff staff, String body) {
        return new SupportTicketMessage(ticket, null, staff, MessageAuthor.SUPPORT, body);
    }

    /** The author's display name, whichever identity wrote it. */
    public String authorName() {
        return staffAuthor != null ? staffAuthor.getName() : customerAuthor.getDisplayName();
    }

    public SupportTicket getTicket() { return ticket; }
    public MessageAuthor getAuthorRole() { return authorRole; }
    public String getBody() { return body; }
}

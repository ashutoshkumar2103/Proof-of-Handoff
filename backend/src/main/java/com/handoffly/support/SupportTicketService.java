package com.handoffly.support;

import com.handoffly.attachment.UploadPolicy;
import com.handoffly.attachment.storage.StorageService;
import com.handoffly.common.config.HandOfflyProperties;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.ForbiddenException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.sequence.SequenceService;
import com.handoffly.common.util.PublicCode;
import com.handoffly.common.web.PageResponse;
import com.handoffly.notification.NotificationService;
import com.handoffly.notification.TicketNotice;
import com.handoffly.support.dto.CreateTicketRequest;
import com.handoffly.support.dto.SupportDashboardResponse;
import com.handoffly.support.dto.SupportMessageRequest;
import com.handoffly.support.dto.TicketAttachmentResponse;
import com.handoffly.support.dto.TicketDetailResponse;
import com.handoffly.support.dto.TicketMessageResponse;
import com.handoffly.support.dto.TicketSummaryResponse;
import com.handoffly.support.staff.SupportStaff;
import com.handoffly.support.staff.SupportStaffService;
import com.handoffly.user.SubscriptionPlan;
import com.handoffly.user.SupportEntitlements;
import com.handoffly.user.User;
import com.handoffly.user.UserService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.Resource;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.web.multipart.MultipartFile;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * Support tickets for both sides of the desk. The customer methods take the signed-in customer's id
 * and only ever reach that customer's own tickets, and only while the plan includes tickets (a plain
 * support message needs only a plan with Contact Support — the plan decides, see {@link SubscriptionPlan}); an
 * account that has no plan yet may open tickets to ask for its activation. The
 * support methods work on any ticket and are reachable only through the staff-only support API.
 * Notification emails go out after the change is committed and never undo it if they fail.
 */
@Service
public class SupportTicketService {

    private static final Logger log = LoggerFactory.getLogger(SupportTicketService.class);

    /** Most recent activity first. */
    private static final Sort NEWEST_ACTIVITY = Sort.by(Sort.Order.desc("updatedAt"), Sort.Order.desc("id"));
    private static final int RECENT_TICKETS = 5;

    private final SupportTicketRepository tickets;
    private final SupportTicketMessageRepository messages;
    private final SupportTicketAttachmentRepository attachments;
    private final UserService userService;
    private final SupportStaffService staffService;
    private final SequenceService sequences;
    private final StorageService storage;
    private final UploadPolicy uploadPolicy;
    private final NotificationService notifications;
    private final String supportMailbox;

    public SupportTicketService(SupportTicketRepository tickets,
                                SupportTicketMessageRepository messages,
                                SupportTicketAttachmentRepository attachments,
                                UserService userService,
                                SupportStaffService staffService,
                                SequenceService sequences,
                                StorageService storage,
                                UploadPolicy uploadPolicy,
                                NotificationService notifications,
                                HandOfflyProperties properties) {
        this.tickets = tickets;
        this.messages = messages;
        this.attachments = attachments;
        this.userService = userService;
        this.staffService = staffService;
        this.sequences = sequences;
        this.storage = storage;
        this.uploadPolicy = uploadPolicy;
        this.notifications = notifications;
        this.supportMailbox = properties.getSupport().getMailbox();
    }

    /** A stored ticket file together with its content, ready to send back. */
    public record LoadedTicketAttachment(SupportTicketAttachment attachment, Resource resource) {}

    // ------------------------------------------------------------ Customer side

    @Transactional
    public TicketDetailResponse create(Long userId, CreateTicketRequest request) {
        User customer = requireTicketAccess(userId);
        String phone = hasText(request.phone()) ? request.phone().trim() : customer.getPhone();
        return detail(open(customer, ContactMethod.TICKET, request.category(), request.subject(),
                request.description(), request.handoffReference(), phone));
    }

    /**
     * A plain support message from a customer whose plan includes Contact Support. Support receives and
     * handles it as a ticket, but the customer gets no ticket workflow back (no list, no replies) unless
     * their plan includes tickets. An optional file is checked before anything is saved, so a refused file
     * means no message is sent. Returns the reference to quote.
     */
    @Transactional
    public String sendMessage(Long userId, SupportMessageRequest request, MultipartFile file) {
        User customer = requireMessageAccess(userId);
        UploadPolicy.CheckedUpload upload = file == null || file.isEmpty() ? null : uploadPolicy.check(file);

        SupportTicket ticket = open(customer, ContactMethod.MESSAGE, TicketCategory.GENERAL, request.subject(),
                request.message(), request.handoffReference(), customer.getPhone());
        if (upload != null) {
            attach(ticket, upload);
        }
        return ticket.getTicketCode();
    }

    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> listMine(Long userId, Pageable pageable) {
        requireTicketAccess(userId);
        return page(TicketSpecifications.ofAccount(userId), pageable);
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse getMine(Long userId, String ticketCode) {
        requireTicketAccess(userId);
        return detail(ownTicket(userId, ticketCode));
    }

    @Transactional
    public TicketDetailResponse replyAsCustomer(Long userId, String ticketCode, String body) {
        User customer = requireTicketAccess(userId);
        SupportTicket ticket = ownTicket(userId, ticketCode);
        String reply = addMessage(ticket, body, text -> SupportTicketMessage.byCustomer(ticket, customer, text));
        ticket.setStatus(ticket.getStatus().afterCustomerReply());
        tickets.saveAndFlush(ticket);

        TicketNotice notice = notice(ticket);
        afterCommit(() -> notifications.sendTicketCustomerReply(supportMailbox, notice, reply));
        return detail(ticket);
    }

    @Transactional
    public TicketAttachmentResponse addAttachment(Long userId, String ticketCode, MultipartFile file) {
        requireTicketAccess(userId);
        SupportTicket ticket = ownTicket(userId, ticketCode);
        requireOpenForChanges(ticket);

        return attach(ticket, uploadPolicy.check(file));
    }

    @Transactional(readOnly = true)
    public LoadedTicketAttachment downloadMine(Long userId, String ticketCode, Long attachmentId) {
        requireTicketAccess(userId);
        return load(ownTicket(userId, ticketCode), attachmentId);
    }

    // ------------------------------------------------------------ Support side

    @Transactional(readOnly = true)
    public SupportDashboardResponse dashboard(boolean includeCustomerMetrics) {
        return new SupportDashboardResponse(
                tickets.countByStatus(TicketStatus.OPEN),
                tickets.countByStatus(TicketStatus.IN_PROGRESS),
                tickets.countByStatus(TicketStatus.WAITING_FOR_CUSTOMER),
                includeCustomerMetrics
                        ? tickets.countCustomersWithTickets(TicketStatus.active(), SubscriptionPlan.withElevatedPriority(),
                                Instant.now())
                        : null);
    }

    /** All tickets, newest activity first; each filter is optional (no statuses = every status). */
    @Transactional(readOnly = true)
    public PageResponse<TicketSummaryResponse> search(Collection<TicketStatus> statuses, boolean priorityOnly,
                                                      String accountCode, Pageable pageable) {
        return page(Specification.allOf(
                TicketSpecifications.withStatusIn(statuses),
                TicketSpecifications.onActivePlanIn(priorityOnly ? SubscriptionPlan.withElevatedPriority() : null, Instant.now()),
                TicketSpecifications.ofAccountCode(accountCode)), pageable);
    }

    @Transactional(readOnly = true)
    public TicketDetailResponse getForSupport(String ticketCode) {
        return detail(anyTicket(ticketCode));
    }

    @Transactional
    public TicketDetailResponse replyAsSupport(Long staffId, String ticketCode, String body) {
        SupportStaff staff = staffService.getById(staffId);
        SupportTicket ticket = anyTicket(ticketCode);
        String reply = addMessage(ticket, body, text -> SupportTicketMessage.byStaff(ticket, staff, text));
        tickets.saveAndFlush(ticket);

        TicketNotice notice = notice(ticket);
        afterCommit(() -> notifications.sendTicketSupportReply(notice, reply));
        return detail(ticket);
    }

    @Transactional
    public TicketDetailResponse changeStatus(String ticketCode, TicketStatus status) {
        SupportTicket ticket = anyTicket(ticketCode);
        if (ticket.getStatus() != status) {
            requireOpenForChanges(ticket);
            ticket.setStatus(status);
            tickets.saveAndFlush(ticket);
        }
        return detail(ticket);
    }

    @Transactional(readOnly = true)
    public LoadedTicketAttachment downloadForSupport(String ticketCode, Long attachmentId) {
        return load(anyTicket(ticketCode), attachmentId);
    }

    /** A customer's most recent tickets, for their profile. */
    @Transactional(readOnly = true)
    public List<TicketSummaryResponse> recentForAccount(Long accountId) {
        return tickets.findAll(TicketSpecifications.ofAccount(accountId), PageRequest.of(0, RECENT_TICKETS, NEWEST_ACTIVITY))
                .map(TicketSummaryResponse::from).getContent();
    }

    /** How many of a customer's tickets still need attention. */
    @Transactional(readOnly = true)
    public long countActiveForAccount(Long accountId) {
        return tickets.countByAccountIdAndStatusIn(accountId, TicketStatus.active());
    }

    // ------------------------------------------------------------ Shared

    /**
     * Plans without tickets get no ticket access at all, whatever the client shows. An account with no plan can ask
     * support to activate it (see {@link SupportEntitlements#of(User, String)}), and only that.
     */
    private User requireTicketAccess(Long userId) {
        User user = userService.getById(userId);
        if (!SupportEntitlements.of(user, null).ticket()) {
            throw new ForbiddenException("Your plan does not include support tickets.");
        }
        return user;
    }

    /** Plans without Contact Support cannot message support from the app, whatever the client shows. */
    private User requireMessageAccess(Long userId) {
        User user = userService.getById(userId);
        if (!SupportEntitlements.of(user, null).message()) {
            throw new ForbiddenException("Your plan does not include contacting support from the app.");
        }
        return user;
    }

    /** Saves a new ticket and queues the notice to the support mailbox for after the commit. */
    private SupportTicket open(User customer, ContactMethod method, TicketCategory category, String subject,
                               String description, String handoffReference, String phone) {
        SupportTicket ticket = tickets.save(new SupportTicket(
                PublicCode.ticket(sequences.next(SequenceService.TICKET)),
                customer, category, method, oneLine(subject), description.trim(),
                hasText(handoffReference) ? handoffReference.trim().toUpperCase(Locale.ROOT) : null, phone));

        TicketNotice notice = notice(ticket);
        afterCommit(() -> notifications.sendTicketCreated(supportMailbox, notice, ticket.getDescription()));
        return ticket;
    }

    private TicketAttachmentResponse attach(SupportTicket ticket, UploadPolicy.CheckedUpload upload) {
        String storageKey = storage.store(upload.data(), upload.contentType());
        return TicketAttachmentResponse.from(attachments.save(new SupportTicketAttachment(
                ticket, storageKey, upload.filename(), upload.contentType(), upload.size())));
    }

    /** A ticket of this customer; anyone else's is reported as not found, never as forbidden. */
    private SupportTicket ownTicket(Long userId, String ticketCode) {
        return tickets.findByTicketCodeAndAccountId(ticketCode, userId)
                .orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    private SupportTicket anyTicket(String ticketCode) {
        return tickets.findByTicketCode(ticketCode)
                .orElseThrow(() -> new NotFoundException("Ticket not found."));
    }

    private static void requireOpenForChanges(SupportTicket ticket) {
        if (ticket.getStatus().isClosed()) {
            throw new ConflictException("This ticket is closed.");
        }
    }

    /** Appends a reply (built from its cleaned-up text) and returns that text. The caller saves the ticket. */
    private String addMessage(SupportTicket ticket, String body, Function<String, SupportTicketMessage> message) {
        requireOpenForChanges(ticket);
        String text = body.trim();
        messages.save(message.apply(text));
        ticket.recordMessage();
        return text;
    }

    private LoadedTicketAttachment load(SupportTicket ticket, Long attachmentId) {
        SupportTicketAttachment attachment = attachments.findByIdAndTicketId(attachmentId, ticket.getId())
                .orElseThrow(() -> new NotFoundException("Attachment not found."));
        return new LoadedTicketAttachment(attachment, storage.load(attachment.getStorageKey()));
    }

    private PageResponse<TicketSummaryResponse> page(Specification<SupportTicket> spec, Pageable requested) {
        Page<SupportTicket> page = tickets.findAll(spec, Paging.of(requested, NEWEST_ACTIVITY));
        return PageResponse.of(page, TicketSummaryResponse::from);
    }

    private TicketDetailResponse detail(SupportTicket ticket) {
        return new TicketDetailResponse(
                TicketSummaryResponse.from(ticket),
                ticket.getDescription(),
                messages.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()).stream()
                        .map(TicketMessageResponse::from).toList(),
                attachments.findByTicketIdOrderByCreatedAtAscIdAsc(ticket.getId()).stream()
                        .map(TicketAttachmentResponse::from).toList());
    }

    private static TicketNotice notice(SupportTicket ticket) {
        User account = ticket.getAccount();
        return new TicketNotice(
                ticket.getTicketCode(), ticket.getSubject(), ticket.getCategory().name(),
                account.getAccountCode(), account.getDisplayName(), account.getEmail(),
                ticket.getContactPhone(), account.hasPlan() ? account.getSubscriptionPlan().name() : "NONE",
                account.entitledPlan().supportPriority().name(), ticket.getContactMethod().name(),
                ticket.getHandoffReference(), SupportEntitlements.of(account, null).ticket());
    }

    /**
     * Runs a notification once the surrounding transaction has committed, so an email is never sent
     * for a change that was rolled back, and a mail failure never undoes a saved ticket or reply.
     */
    private void afterCommit(Runnable notification) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                try {
                    notification.run();
                } catch (RuntimeException e) {
                    log.warn("A support notification email could not be sent: {}", e.getMessage());
                }
            }
        });
    }

    /** A subject goes into email headers and lists, so it is always one line. */
    private static String oneLine(String text) {
        return text.trim().replaceAll("\\s+", " ");
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}

package com.handoffly.support;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface SupportTicketAttachmentRepository extends JpaRepository<SupportTicketAttachment, Long> {

    List<SupportTicketAttachment> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);

    Optional<SupportTicketAttachment> findByIdAndTicketId(Long id, Long ticketId);
}

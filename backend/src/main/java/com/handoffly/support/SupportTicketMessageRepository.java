package com.handoffly.support;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface SupportTicketMessageRepository extends JpaRepository<SupportTicketMessage, Long> {

    @EntityGraph(attributePaths = {"customerAuthor", "staffAuthor"})
    List<SupportTicketMessage> findByTicketIdOrderByCreatedAtAscIdAsc(Long ticketId);
}

package com.handoffly.attachment;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface AttachmentRepository extends JpaRepository<Attachment, Long> {

    List<Attachment> findByHandoffIdOrderByCreatedAtAscIdAsc(Long handoffId);

    long countByHandoffId(Long handoffId);
}

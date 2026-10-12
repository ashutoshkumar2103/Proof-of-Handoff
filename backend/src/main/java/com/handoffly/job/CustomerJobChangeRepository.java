package com.handoffly.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

/** Every lookup is by the customer's own account id, so there is no way to read another customer's changes. */
public interface CustomerJobChangeRepository extends JpaRepository<CustomerJobChange, Long> {

    /** The changes to the customer's jobs; the caller orders them (newest first). */
    Page<CustomerJobChange> findByAccountId(Long accountId, Pageable pageable);
}

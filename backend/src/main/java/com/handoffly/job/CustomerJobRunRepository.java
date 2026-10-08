package com.handoffly.job;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

/** Every lookup is by the customer's own id, so there is no way to read another customer's runs. */
public interface CustomerJobRunRepository extends JpaRepository<CustomerJobRun, Long> {

    /** The customer's runs of every job; the caller orders them (newest first). */
    Page<CustomerJobRun> findByUserId(Long userId, Pageable pageable);

    /** The most recent run of one of the customer's jobs, if it has run. */
    Optional<CustomerJobRun> findFirstByUserIdAndTypeOrderByIdDesc(Long userId, JobType type);
}

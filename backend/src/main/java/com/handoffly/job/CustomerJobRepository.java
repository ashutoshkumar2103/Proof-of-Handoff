package com.handoffly.job;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface CustomerJobRepository extends JpaRepository<CustomerJob, Long> {

    /** Every lookup a customer can cause is by their own id: there is no way to name another customer's job. */
    @EntityGraph(attributePaths = "user")
    List<CustomerJob> findByUserIdOrderByIdAsc(Long userId);

    @EntityGraph(attributePaths = "user")
    Optional<CustomerJob> findByUserIdAndType(Long userId, JobType type);

    /** Enabled jobs whose time has come, oldest first. */
    @EntityGraph(attributePaths = "user")
    @Query("select j from CustomerJob j where j.enabled = true and j.nextRunAt <= :now order by j.nextRunAt asc, j.id asc")
    List<CustomerJob> findDue(@Param("now") Instant now, Pageable limit);

    /**
     * Takes a due job for running by moving its next run on — but only if it is still enabled and still due at the
     * time it was read. Of two scheduler passes (or two instances) racing for the same job exactly one gets 1 back.
     */
    @Modifying
    @Query("update CustomerJob j set j.nextRunAt = :next, j.version = j.version + 1 "
            + "where j.id = :id and j.enabled = true and j.nextRunAt = :expected")
    int claim(@Param("id") Long id, @Param("expected") Instant expected, @Param("next") Instant next);
}

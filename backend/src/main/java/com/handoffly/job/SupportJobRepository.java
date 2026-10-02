package com.handoffly.job;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface SupportJobRepository extends JpaRepository<SupportJob, Long> {

    Optional<SupportJob> findByType(SupportJobType type);

    /** Enabled jobs whose time has come. */
    @Query("select j from SupportJob j where j.enabled = true and j.nextRunAt <= :now order by j.nextRunAt asc, j.id asc")
    List<SupportJob> findDue(@Param("now") Instant now);

    /** Takes a due job by moving its next run on, but only if it is still enabled and still due: one racer wins. */
    @Modifying
    @Query("update SupportJob j set j.nextRunAt = :next, j.version = j.version + 1 "
            + "where j.id = :id and j.enabled = true and j.nextRunAt = :expected")
    int claim(@Param("id") Long id, @Param("expected") Instant expected, @Param("next") Instant next);
}

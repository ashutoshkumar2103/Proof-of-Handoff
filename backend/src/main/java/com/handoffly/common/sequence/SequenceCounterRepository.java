package com.handoffly.common.sequence;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface SequenceCounterRepository extends JpaRepository<SequenceCounter, String> {

    /** Row-locks the counter until the caller's transaction ends, so numbers are never duplicated. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select c from SequenceCounter c where c.name = :name")
    Optional<SequenceCounter> findForUpdateByName(@Param("name") String name);
}

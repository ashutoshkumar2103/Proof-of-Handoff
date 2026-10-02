package com.handoffly.payment;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** Row-locks the payment, so two requests with the same token cannot both apply it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select p from Payment p where p.tokenHash = :tokenHash")
    Optional<Payment> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);
}

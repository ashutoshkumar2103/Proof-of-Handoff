package com.handoffly.auth;

import jakarta.persistence.LockModeType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, Long> {

    /** Row-locks the token, so two requests with the same link cannot both use it. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select t from PasswordResetToken t where t.tokenHash = :tokenHash")
    Optional<PasswordResetToken> findByTokenHashForUpdate(@Param("tokenHash") String tokenHash);

    /** A customer's links that could still be used. */
    @Query("select t from PasswordResetToken t where t.user.id = :userId and t.usedAt is null and t.expiresAt > :now")
    List<PasswordResetToken> findUsableByUserId(@Param("userId") Long userId, @Param("now") Instant now);
}

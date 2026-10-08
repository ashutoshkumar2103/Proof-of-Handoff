package com.handoffly.user;

import jakarta.persistence.LockModeType;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;

public interface UserRepository extends JpaRepository<User, Long> {

    Optional<User> findByEmailIgnoreCase(String email);

    boolean existsByEmailIgnoreCase(String email);

    Optional<User> findByAccountCode(String accountCode);

    /** Row-locks the user for the rest of the transaction, e.g. to issue handoff numbers one at a time. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.id = :id")
    Optional<User> findByIdForUpdate(@Param("id") Long id);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select u from User u where u.accountCode = :accountCode")
    Optional<User> findByAccountCodeForUpdate(@Param("accountCode") String accountCode);

    /** Every handoff prefix some customer has now, in upper case (existing accounts may share one: HO). */
    @Query("select distinct upper(u.handoffPrefix) from User u")
    List<String> findCurrentHandoffPrefixes();

    /** Whether a customer other than {@code id} has this prefix now, whatever its case. */
    boolean existsByHandoffPrefixIgnoreCaseAndIdNot(String handoffPrefix, Long id);

    /** Customer search for support: account ID, name or email, case-insensitive ({@code like} is lower-case, wrapped in %). */
    @Query("select u from User u where lower(u.accountCode) like :like "
            + "or lower(u.displayName) like :like or lower(u.email) like :like")
    Page<User> search(@Param("like") String like, Pageable pageable);
}

package com.handoffly.job;

import com.handoffly.user.User;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;

public interface SubscriptionExpiryReminderRepository extends JpaRepository<SubscriptionExpiryReminder, Long> {

    /**
     * Customers whose paid period ends within the window and who have not yet been reminded about that end date.
     * A subscription with no end date, or one that has already ended, is not "about to expire" and is left out, and
     * so is a customer whose account is switched off.
     */
    @Query("select u from User u where u.enabled = true and u.planValidUntil > :now and u.planValidUntil <= :until "
            + "and not exists (select 1 from SubscriptionExpiryReminder r "
            + "where r.userId = u.id and r.validUntil = u.planValidUntil) "
            + "order by u.planValidUntil asc, u.id asc")
    List<User> findDueForReminder(@Param("now") Instant now, @Param("until") Instant until, Pageable limit);

    /** Takes back one reservation — only for a reminder whose email could not be sent, so the next run tries again. */
    @Modifying
    @Query("delete from SubscriptionExpiryReminder r where r.userId = :userId and r.validUntil = :validUntil")
    int deleteByUserIdAndValidUntil(@Param("userId") Long userId, @Param("validUntil") Instant validUntil);
}

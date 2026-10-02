package com.handoffly.user;

import org.springframework.data.repository.Repository;

import java.util.List;

/**
 * Deliberately NOT a {@code JpaRepository}: the subscription history can be added to and read, never updated or
 * deleted through the application.
 */
public interface SubscriptionHistoryRepository extends Repository<SubscriptionHistory, Long> {

    SubscriptionHistory save(SubscriptionHistory entry);

    /** A customer's plans, newest first. */
    List<SubscriptionHistory> findTop50ByUserIdOrderByIdDesc(Long userId);
}

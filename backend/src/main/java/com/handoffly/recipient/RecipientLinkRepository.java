package com.handoffly.recipient;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;

public interface RecipientLinkRepository extends JpaRepository<RecipientLink, Long> {

    @EntityGraph(attributePaths = {"handoff", "handoff.items", "handoff.owner"})
    Optional<RecipientLink> findByTokenHash(String tokenHash);

    @Modifying
    @Query("update RecipientLink rl set rl.revoked = true "
            + "where rl.handoff.id = :handoffId and rl.revoked = false")
    void revokeAllForHandoff(@Param("handoffId") Long handoffId);
}

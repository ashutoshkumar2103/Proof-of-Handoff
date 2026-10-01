package com.handoffly.returns;

import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;

public interface ReturnEventRepository extends JpaRepository<ReturnEvent, Long> {

    @EntityGraph(attributePaths = {"lines", "lines.item"})
    List<ReturnEvent> findByHandoffIdOrderByOccurredAtAscIdAsc(Long handoffId);

    @EntityGraph(attributePaths = {"lines", "lines.item"})
    Optional<ReturnEvent> findWithLinesById(Long id);
}

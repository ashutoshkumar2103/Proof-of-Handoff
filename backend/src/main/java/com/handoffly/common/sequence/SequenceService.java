package com.handoffly.common.sequence;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Hands out gap-free, never-repeated numbers for global sequences. The counter row is locked for
 * the rest of the CALLER's transaction ({@link Propagation#MANDATORY}), so two concurrent creators
 * queue up instead of colliding, and a rolled-back creation gives its number back.
 */
@Service
public class SequenceService {

    public static final String ACCOUNT = "ACCOUNT";
    public static final String TICKET = "TICKET";
    public static final String STAFF = "STAFF";

    private final SequenceCounterRepository counters;

    public SequenceService(SequenceCounterRepository counters) {
        this.counters = counters;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long next(String name) {
        return counters.findForUpdateByName(name)
                .orElseThrow(() -> new IllegalStateException("Sequence '" + name + "' is not configured."))
                .take();
    }
}

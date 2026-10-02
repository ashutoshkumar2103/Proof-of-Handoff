package com.handoffly.common.sequence;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** The next number to hand out for one named global sequence (account IDs, ticket IDs). */
@Entity
@Table(name = "sequence_counter")
public class SequenceCounter {

    @Id
    @Column(length = 40)
    private String name;

    @Column(name = "next_value", nullable = false)
    private long nextValue;

    protected SequenceCounter() {
        // JPA
    }

    /** Returns the current number and moves the counter on. */
    long take() {
        return nextValue++;
    }
}

package com.handoffly.handoff;

import com.handoffly.common.error.ConflictException;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HandoffStateMachineTest {

    private final HandoffStateMachine sm = new HandoffStateMachine();

    @Test
    void allowsTheHappyPath() {
        assertThat(sm.canTransition(HandoffStatus.DRAFT, HandoffStatus.OUTGOING_SENT)).isTrue();
        assertThat(sm.canTransition(HandoffStatus.OUTGOING_SENT, HandoffStatus.AWAITING_RECIPIENT)).isTrue();
        assertThat(sm.canTransition(HandoffStatus.AWAITING_RECIPIENT, HandoffStatus.ACTIVE_WITH_RECIPIENT)).isTrue();
        assertThat(sm.canTransition(HandoffStatus.ACTIVE_WITH_RECIPIENT, HandoffStatus.PARTIALLY_RETURNED)).isTrue();
        assertThat(sm.canTransition(HandoffStatus.PARTIALLY_RETURNED, HandoffStatus.FULLY_RETURNED)).isTrue();
        assertThat(sm.canTransition(HandoffStatus.FULLY_RETURNED, HandoffStatus.CLOSED)).isTrue();
    }

    @Test
    void rejectsIllegalTransitions() {
        assertThat(sm.canTransition(HandoffStatus.DRAFT, HandoffStatus.CLOSED)).isFalse();
        assertThat(sm.canTransition(HandoffStatus.DRAFT, HandoffStatus.FULLY_RETURNED)).isFalse();
        assertThat(sm.canTransition(HandoffStatus.CLOSED, HandoffStatus.ACTIVE_WITH_RECIPIENT)).isFalse();
        assertThat(sm.canTransition(HandoffStatus.AWAITING_RECIPIENT, HandoffStatus.FULLY_RETURNED)).isFalse();
    }

    @Test
    void terminalStatesHaveNoTransitions() {
        for (HandoffStatus target : HandoffStatus.values()) {
            assertThat(sm.canTransition(HandoffStatus.CLOSED, target)).isFalse();
            assertThat(sm.canTransition(HandoffStatus.CANCELLED, target)).isFalse();
            assertThat(sm.canTransition(HandoffStatus.REJECTED, target)).isFalse();
        }
        assertThat(HandoffStatus.CLOSED.isTerminal()).isTrue();
        assertThat(HandoffStatus.CANCELLED.isTerminal()).isTrue();
        assertThat(HandoffStatus.REJECTED.isTerminal()).isTrue();
    }

    @Test
    void sameStateIsNotATransition() {
        assertThat(sm.canTransition(HandoffStatus.DRAFT, HandoffStatus.DRAFT)).isFalse();
    }

    @Test
    void assertCanTransitionThrowsOnIllegal() {
        assertThatThrownBy(() -> sm.assertCanTransition(HandoffStatus.DRAFT, HandoffStatus.CLOSED))
                .isInstanceOf(ConflictException.class)
                .hasMessageContaining("Illegal status transition");
    }
}

package com.handoffly.common.error;

import org.junit.jupiter.api.Test;
import org.springframework.http.ProblemDetail;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import static org.assertj.core.api.Assertions.assertThat;

class ConcurrentUpdateHandlingTest {

    @Test
    void aLostRaceBetweenTwoEditorsIsAConflictNotAServerError() {
        ProblemDetail problem = new GlobalExceptionHandler().handleConcurrentUpdate(
                new ObjectOptimisticLockingFailureException(Object.class, 1L),
                new MockHttpServletRequest("PUT", "/api/v1/support/tickets/TKT-000001/status"));

        assertThat(problem.getStatus()).isEqualTo(409);
        assertThat(problem.getProperties()).containsEntry("code", "concurrent_update");
        assertThat(problem.getDetail()).doesNotContain("Object");   // nothing internal leaks
    }
}

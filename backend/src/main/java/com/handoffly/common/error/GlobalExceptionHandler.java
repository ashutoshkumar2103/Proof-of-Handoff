package com.handoffly.common.error;

import jakarta.servlet.http.HttpServletRequest;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.validation.FieldError;
import org.springframework.web.HttpRequestMethodNotSupportedException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.multipart.MaxUploadSizeExceededException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import java.net.URI;
import java.time.Instant;
import java.util.List;

/**
 * Single, centralized translation of exceptions into RFC 7807 {@link ProblemDetail}
 * responses. Stack traces are never exposed to clients; technical detail is logged
 * server-side. This is the one place API error shape is defined.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final URI ERROR_TYPE = URI.create("https://handoffly.app/errors");

    @ExceptionHandler(ApiException.class)
    public ProblemDetail handleApi(ApiException ex, HttpServletRequest req) {
        return problem(ex.getStatus(), ex.getCode(), ex.getMessage(), req, null);
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ProblemDetail handleValidation(MethodArgumentNotValidException ex, HttpServletRequest req) {
        List<FieldViolation> violations = ex.getBindingResult().getFieldErrors().stream()
                .map(this::toViolation)
                .toList();
        ProblemDetail pd = problem(HttpStatus.BAD_REQUEST, "validation_failed",
                "One or more fields are invalid.", req, null);
        pd.setProperty("errors", violations);
        return pd;
    }

    @ExceptionHandler(MaxUploadSizeExceededException.class)
    public ProblemDetail handleMaxSize(MaxUploadSizeExceededException ex, HttpServletRequest req) {
        return problem(HttpStatus.PAYLOAD_TOO_LARGE, "file_too_large",
                "Uploaded file exceeds the maximum allowed size.", req, null);
    }

    @ExceptionHandler(NoResourceFoundException.class)
    public ProblemDetail handleNoResource(NoResourceFoundException ex, HttpServletRequest req) {
        return problem(HttpStatus.NOT_FOUND, "not_found", "Resource not found.", req, null);
    }

    @ExceptionHandler(HttpRequestMethodNotSupportedException.class)
    public ProblemDetail handleMethodNotAllowed(HttpRequestMethodNotSupportedException ex, HttpServletRequest req) {
        return problem(HttpStatus.METHOD_NOT_ALLOWED, "method_not_allowed",
                "This HTTP method is not supported for this endpoint.", req, null);
    }

    @ExceptionHandler(AccessDeniedException.class)
    public ProblemDetail handleAccessDenied(AccessDeniedException ex, HttpServletRequest req) {
        return problem(HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this resource.", req, null);
    }

    @ExceptionHandler(AuthenticationException.class)
    public ProblemDetail handleAuth(AuthenticationException ex, HttpServletRequest req) {
        return problem(HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required.", req, null);
    }

    @ExceptionHandler(Exception.class)
    public ProblemDetail handleUnexpected(Exception ex, HttpServletRequest req) {
        // Log full detail server-side; return a safe, generic message.
        log.error("Unhandled exception on {} {}", req.getMethod(), req.getRequestURI(), ex);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal_error",
                "An unexpected error occurred.", req, null);
    }

    private ProblemDetail problem(HttpStatus status, String code, String detail,
                                  HttpServletRequest req, List<FieldViolation> violations) {
        ProblemDetail pd = ProblemDetail.forStatusAndDetail(status, detail);
        pd.setType(ERROR_TYPE);
        pd.setTitle(status.getReasonPhrase());
        pd.setInstance(URI.create(req.getRequestURI()));
        pd.setProperty("code", code);
        pd.setProperty("timestamp", Instant.now().toString());
        if (violations != null) {
            pd.setProperty("errors", violations);
        }
        return pd;
    }

    private FieldViolation toViolation(FieldError fe) {
        return new FieldViolation(fe.getField(), fe.getDefaultMessage());
    }

    /** Field-level validation error entry. */
    public record FieldViolation(String field, String message) {}
}

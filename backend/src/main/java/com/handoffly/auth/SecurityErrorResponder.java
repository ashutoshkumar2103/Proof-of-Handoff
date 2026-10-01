package com.handoffly.auth;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.AuthenticationException;
import org.springframework.security.web.AuthenticationEntryPoint;
import org.springframework.security.web.access.AccessDeniedHandler;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Instant;

/**
 * Writes RFC 7807 error bodies for authentication/authorization failures that occur
 * inside the security filter chain (before controller advice can run), keeping error
 * shape consistent with {@code GlobalExceptionHandler}. JSON is written directly to stay
 * independent of the Jackson version on the classpath.
 */
@Component
public class SecurityErrorResponder implements AuthenticationEntryPoint, AccessDeniedHandler {

    @Override
    public void commence(HttpServletRequest request, HttpServletResponse response,
                         AuthenticationException authException) throws IOException {
        write(request, response, HttpStatus.UNAUTHORIZED, "unauthorized", "Authentication required.");
    }

    @Override
    public void handle(HttpServletRequest request, HttpServletResponse response,
                       AccessDeniedException accessDeniedException) throws IOException {
        write(request, response, HttpStatus.FORBIDDEN, "forbidden", "You do not have access to this resource.");
    }

    private void write(HttpServletRequest request, HttpServletResponse response,
                       HttpStatus status, String code, String detail) throws IOException {
        response.setStatus(status.value());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        String body = "{"
                + "\"type\":\"https://handoffly.app/errors\","
                + "\"title\":\"" + esc(status.getReasonPhrase()) + "\","
                + "\"status\":" + status.value() + ","
                + "\"detail\":\"" + esc(detail) + "\","
                + "\"instance\":\"" + esc(request.getRequestURI()) + "\","
                + "\"code\":\"" + esc(code) + "\","
                + "\"timestamp\":\"" + esc(Instant.now().toString()) + "\""
                + "}";
        response.getWriter().write(body);
    }

    /** Minimal JSON string escaping for the small, controlled set of values written here. */
    private static String esc(String s) {
        if (s == null) return "";
        StringBuilder sb = new StringBuilder(s.length() + 8);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.toString();
    }
}

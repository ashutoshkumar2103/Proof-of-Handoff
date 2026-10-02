package com.handoffly.auth.jwt;

import com.handoffly.auth.UserPrincipal;
import com.handoffly.user.UserRepository;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpHeaders;
import org.springframework.lang.NonNull;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;

/**
 * Extracts and validates the Bearer JWT on each customer-API request, populating the security
 * context with a {@link UserPrincipal}. Only CUSTOMER tokens are accepted: invalid, absent or
 * support-staff tokens leave the request anonymous — access rules then decide whether that is allowed.
 * A valid signature is not enough: the account must still exist, be enabled, and the token must carry the
 * account's current token version, so a password change or reset signs out every older session at once.
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";
    private static final String CUSTOMER_AUTHORITY = "ROLE_CUSTOMER";

    private final JwtService jwtService;
    private final UserRepository userRepository;

    public JwtAuthenticationFilter(JwtService jwtService, UserRepository userRepository) {
        this.jwtService = jwtService;
        this.userRepository = userRepository;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain)
            throws ServletException, IOException {

        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            String token = header.substring(BEARER_PREFIX.length());
            JwtService.ParsedToken parsed = jwtService.parseCustomer(token);
            if (parsed != null && isCurrent(parsed)) {
                UserPrincipal principal = new UserPrincipal(parsed.id(), parsed.email());
                var authorities = List.of(new SimpleGrantedAuthority(CUSTOMER_AUTHORITY));
                var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);
            }
        }

        filterChain.doFilter(request, response);
    }

    /** The account still exists, is enabled, and the token belongs to its current generation of tokens. */
    private boolean isCurrent(JwtService.ParsedToken token) {
        return userRepository.findById(token.id())
                .map(user -> user.isEnabled() && user.getTokenVersion() == token.tokenVersion())
                .orElse(false);
    }
}

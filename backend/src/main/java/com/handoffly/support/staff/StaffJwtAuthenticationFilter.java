package com.handoffly.support.staff;

import com.handoffly.auth.jwt.JwtService;
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
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.List;
import java.util.stream.Stream;

/**
 * Authenticates support-API requests from a Bearer token issued to SUPPORT STAFF. Customer tokens are
 * not accepted here (they carry a different audience), and a valid staff token is honoured only while
 * the staff member still exists and is active in the database — their role comes from there too, not
 * from the token. Anything else leaves the request anonymous. This filter belongs to the support
 * security chain only; it is deliberately not a Spring bean, so it can never run on customer endpoints.
 */
public class StaffJwtAuthenticationFilter extends OncePerRequestFilter {

    private static final String BEARER_PREFIX = "Bearer ";

    private final JwtService jwtService;
    private final SupportStaffService staffService;

    public StaffJwtAuthenticationFilter(JwtService jwtService, SupportStaffService staffService) {
        this.jwtService = jwtService;
        this.staffService = staffService;
    }

    @Override
    protected void doFilterInternal(@NonNull HttpServletRequest request,
                                    @NonNull HttpServletResponse response,
                                    @NonNull FilterChain filterChain) throws ServletException, IOException {
        String header = request.getHeader(HttpHeaders.AUTHORIZATION);
        if (header != null && header.startsWith(BEARER_PREFIX)
                && SecurityContextHolder.getContext().getAuthentication() == null) {

            JwtService.ParsedToken parsed = jwtService.parseStaff(header.substring(BEARER_PREFIX.length()));
            if (parsed != null) {
                staffService.findActive(parsed.id()).ifPresent(staff -> {
                    StaffPrincipal principal = new StaffPrincipal(
                            staff.getId(), staff.getStaffCode(), staff.getEmail(), staff.getRole());
                    // The role AND the permissions it holds, both read from the database just now (not from the token),
                    // so a role change or deactivation applies on the very next request.
                    List<SimpleGrantedAuthority> authorities = Stream.concat(
                                    Stream.of("ROLE_" + staff.getRole().name()),
                                    staff.getRole().permissions().stream().map(Enum::name))
                            .map(SimpleGrantedAuthority::new).toList();
                    var authentication = new UsernamePasswordAuthenticationToken(principal, null, authorities);
                    authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                    SecurityContextHolder.getContext().setAuthentication(authentication);
                });
            }
        }
        filterChain.doFilter(request, response);
    }
}

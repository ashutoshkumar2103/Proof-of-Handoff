package com.handoffly.support.staff;

import com.handoffly.auth.SecurityErrorResponder;
import com.handoffly.auth.jwt.JwtService;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfigurationSource;

import java.util.Arrays;

/**
 * The security of the support API: a chain of its own for {@code /api/v1/support/**}, ahead of the
 * customer chain. Only a staff login is public; everything else needs a valid, active SUPPORT or ADMIN
 * staff token. A customer token never authenticates here, and (in the customer chain) a staff token never
 * authenticates there — the two identities share a backend, not credentials.
 */
@Configuration
public class SupportSecurityConfig {

    private static final String LOGIN_PATH = "/api/v1/support/auth/login";

    @Bean
    @Order(1)
    public SecurityFilterChain supportFilterChain(HttpSecurity http,
                                                  CorsConfigurationSource corsConfigurationSource,
                                                  SecurityErrorResponder securityErrorResponder,
                                                  JwtService jwtService,
                                                  SupportStaffService staffService) throws Exception {
        String[] staffRoles = Arrays.stream(SupportRole.values()).map(Enum::name).toArray(String[]::new);
        http
                .securityMatcher("/api/v1/support/**")
                .cors(cors -> cors.configurationSource(corsConfigurationSource))
                .csrf(csrf -> csrf.disable())
                .sessionManagement(sm -> sm.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers(HttpMethod.OPTIONS, "/**").permitAll()
                        .requestMatchers(HttpMethod.POST, LOGIN_PATH).permitAll()
                        .anyRequest().hasAnyRole(staffRoles))
                .exceptionHandling(ex -> ex
                        .authenticationEntryPoint(securityErrorResponder)
                        .accessDeniedHandler(securityErrorResponder))
                .addFilterBefore(new StaffJwtAuthenticationFilter(jwtService, staffService),
                        UsernamePasswordAuthenticationFilter.class);
        return http.build();
    }
}

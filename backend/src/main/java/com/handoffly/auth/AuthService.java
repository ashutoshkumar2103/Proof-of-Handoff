package com.handoffly.auth;

import com.handoffly.auth.dto.AuthResponse;
import com.handoffly.auth.dto.LoginRequest;
import com.handoffly.auth.dto.RegisterRequest;
import com.handoffly.auth.dto.UserResponse;
import com.handoffly.auth.jwt.JwtService;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.UnauthorizedException;
import com.handoffly.user.Role;
import com.handoffly.user.User;
import com.handoffly.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Account registration and authentication. Passwords are BCrypt-hashed; login
 * returns a stateless JWT. Login failures are deliberately generic to avoid
 * revealing whether an email exists.
 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder, JwtService jwtService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
    }

    @Transactional
    public AuthResponse register(RegisterRequest request) {
        String email = request.email().trim();
        if (userRepository.existsByEmailIgnoreCase(email)) {
            throw new ConflictException("An account with this email already exists.");
        }
        User user = new User(
                email,
                passwordEncoder.encode(request.password()),
                request.displayName().trim(),
                request.organization() == null ? null : request.organization().trim(),
                Role.USER);
        user = userRepository.save(user);
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public AuthResponse login(LoginRequest request) {
        User user = userRepository.findByEmailIgnoreCase(request.email().trim())
                .orElse(null);
        if (user == null || !user.isEnabled()
                || !passwordEncoder.matches(request.password(), user.getPasswordHash())) {
            throw new UnauthorizedException("Invalid email or password.");
        }
        return toAuthResponse(user);
    }

    @Transactional(readOnly = true)
    public UserResponse me(Long userId) {
        return userRepository.findById(userId)
                .map(UserResponse::from)
                .orElseThrow(() -> new UnauthorizedException("Account no longer exists."));
    }

    private AuthResponse toAuthResponse(User user) {
        JwtService.IssuedToken issued = jwtService.issue(user);
        return new AuthResponse(issued.token(), issued.expiresAt(), UserResponse.from(user));
    }
}

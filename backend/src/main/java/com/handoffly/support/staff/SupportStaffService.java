package com.handoffly.support.staff;

import com.handoffly.common.error.BadRequestException;
import com.handoffly.common.error.ConflictException;
import com.handoffly.common.error.NotFoundException;
import com.handoffly.common.sequence.SequenceService;
import com.handoffly.common.util.PublicCode;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Pattern;

/** Lookup and controlled creation of support staff. There is no public way to create one. */
@Service
public class SupportStaffService {

    static final int MIN_PASSWORD_LENGTH = 12;
    /** BCrypt only uses the first 72 bytes, so a longer password would silently be weaker than it looks. */
    static final int MAX_PASSWORD_BYTES = 72;
    private static final Pattern EMAIL = Pattern.compile("^[^@\\s]+@[^@\\s]+$");

    private final SupportStaffRepository repository;
    private final SequenceService sequences;
    private final PasswordEncoder passwordEncoder;

    public SupportStaffService(SupportStaffRepository repository, SequenceService sequences,
                               PasswordEncoder passwordEncoder) {
        this.repository = repository;
        this.sequences = sequences;
        this.passwordEncoder = passwordEncoder;
    }

    /** The staff member, but only while they are active — what every support request is checked against. */
    @Transactional(readOnly = true)
    public Optional<SupportStaff> findActive(Long id) {
        return repository.findByIdAndActiveTrue(id);
    }

    @Transactional(readOnly = true)
    public SupportStaff getById(Long id) {
        return repository.findById(id).orElseThrow(() -> new NotFoundException("Staff member not found."));
    }

    @Transactional(readOnly = true)
    public boolean exists(String email) {
        return email != null && repository.existsByEmailIgnoreCase(email.trim());
    }

    /**
     * Creates an active staff member with a freshly issued Staff ID and a BCrypt-hashed password.
     * @throws BadRequestException if the name, email or password is unacceptable
     * @throws ConflictException   if a staff member with this email already exists
     */
    @Transactional
    public SupportStaff provision(String name, String email, String rawPassword, SupportRole role) {
        if (name == null || name.isBlank() || name.trim().length() > 150) {
            throw new BadRequestException("A staff name is required (up to 150 characters).");
        }
        if (email == null || !EMAIL.matcher(email.trim()).matches() || email.trim().length() > 255) {
            throw new BadRequestException("A valid staff email is required.");
        }
        if (rawPassword == null || rawPassword.length() < MIN_PASSWORD_LENGTH
                || rawPassword.getBytes(StandardCharsets.UTF_8).length > MAX_PASSWORD_BYTES) {
            throw new BadRequestException("A staff password must be at least " + MIN_PASSWORD_LENGTH
                    + " characters (and at most " + MAX_PASSWORD_BYTES + " bytes).");
        }
        if (repository.existsByEmailIgnoreCase(email.trim())) {
            throw new ConflictException("A support staff member with this email already exists.");
        }
        return repository.save(new SupportStaff(
                PublicCode.staff(sequences.next(SequenceService.STAFF)),
                name.trim(), email.trim(), passwordEncoder.encode(rawPassword), role));
    }
}

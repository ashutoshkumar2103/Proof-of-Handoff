# Architecture Decision Record (defaults chosen)

These are sensible engineering defaults made without blocking on questions, per the
project brief. Each can be revisited.

1. **No Lombok.** Java 25 is new; Lombok historically lags new JDKs and can break
   builds. DTOs use Java `record`s; entities use plain getters/setters. Low risk,
   no annotation-processor surprises.

2. **Spring Boot 4.1.1** (latest *stable*; 4.2.0-M2 is a milestone and excluded).

3. **Quantities are `BigDecimal(19,3)`.** Supports both counts (200 chairs) and
   fractional units (2.5 kg). Units stored as free-text `unit` per item.

4. **Two ID schemes.** Internal `BIGINT` auto-increment PKs for joins/performance;
   a public, unguessable `public_code` (ULID-like) on `handoff` for external URLs
   so internal IDs are not exposed.

5. **Recipient access = opaque token, stored hashed** (SHA-256) with expiry. One
   token ↔ one handoff. Never a JWT (recipients are unauthenticated guests).
   Raw token is only ever placed in the emailed link; DB stores only the hash.

6. **Auth = stateless JWT** (jjwt), BCrypt password hashing. Roles: `USER`, `ADMIN`.

7. **Storage abstraction.** `StorageService` interface; `LocalFileStorage` for dev
   (files under a configured dir, metadata in DB). Production can add an S3 impl
   without touching business logic. Blobs never stored in MySQL.

8. **Email abstraction.** `EmailSender` interface; `LoggingEmailSender` (default in
   dev — logs the link, sends nothing) and `SmtpEmailSender` (prod, Spring Mail).
   Selected by property `handoffly.mail.provider`.

9. **Test DB = H2 in MySQL-compat mode**, because Docker/MySQL are not guaranteed in
   the build/dev environment. Flyway migrations are written portably (no MySQL-only
   syntax) so the same migrations run on H2 and MySQL 8.4.

10. **Errors = RFC 7807 ProblemDetail** via a single `GlobalExceptionHandler`, with a
    `errors` array for field-level Bean Validation failures. No stack traces leak.

11. **Timestamps = UTC `Instant`**, serialized ISO-8601. DB columns `TIMESTAMP` /
    Hibernate maps `Instant`.

12. **Remaining quantities are always computed** (outgoing − Σ returned), never
    stored denormalized, to avoid drift. Status is derived/validated by the state
    machine on each mutating operation.

13. **Legal wording:** typed name = "Typed acknowledgement". Never "legally binding
    e-signature".

# Architecture Decision Record (defaults chosen)

These are sensible engineering defaults made without blocking on questions, per the
project brief. Each can be revisited.

1. **No Lombok.** Java 25 is new; Lombok historically lags new JDKs and can break
   builds. DTOs use Java `record`s; entities use plain getters/setters. Low risk,
   no annotation-processor surprises.

2. **Spring Boot 4.1.1** (latest *stable*; 4.2.0-M2 is a milestone and excluded).

3. **Quantities are `BigDecimal(19,3)`.** Supports both counts (200 chairs) and
   fractional units (2.5 kg). Units stored as free-text `unit` per item.

4. **Two ID schemes.** Internal `BIGINT` auto-increment PKs for joins and for addressing
   records in the API (they are globally unique); a human-readable `public_code` on `handoff`
   (e.g. `HO-3`) for people. The code is a display reference only — it never grants access, every
   API checks ownership. See decision 15 for how it is numbered.

5. **Recipient access = opaque token, stored hashed** (SHA-256) with expiry. One
   token ↔ one handoff. Never a JWT (recipients are unauthenticated guests).
   Raw token is only ever placed in the emailed link; DB stores only the hash.

6. **Auth = stateless JWT** (jjwt), BCrypt password hashing. Customers have no role; support staff
   are a separate identity with roles `ADMIN`/`MANAGER`/`TICKET_AGENT` (see decisions 16 and 22).

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

## Accounts, plans and the support portal

14. **A customer account is the existing `User` — no separate `Account` entity.**
    *Trade-off:* a separate account table would allow several users per account (teams) later, but
    nothing today needs it, it would add a second identity model next to `User`, and every
    ownership check (`handoff.owner`) would need rewiring. So the `app_user` row carries the
    customer-facing **Account ID** (`CUS-42`, unique, never changes, issued from a global
    counter table so it is gap-free and never reused), the plan, the handoff prefix and the
    handoff counter. If teams are ever needed, an account table can be introduced then, keyed by
    these same Account IDs.

15. **Handoff references are per customer; `public_code` keeps every value ever issued.**
    The reference a customer sees is `<prefix>-<n>` (`AV-1`, `AV-2`, …) where `n` comes from that
    customer's own counter (`app_user.handoff_sequence`, the last number issued), never from the
    global id. The counter is advanced under a pessimistic row lock on the customer inside the
    handoff-creating transaction, so concurrent creation cannot issue a number twice; a unique
    `(owner_user_id, public_code)` constraint is the backstop. The counter is **never reset** and is
    independent of the prefix, so changing the prefix (support only: 2–5 capital letters, not unique
    across customers) affects new handoffs only and a returning prefix cannot recreate an old
    reference. Uniqueness is therefore per customer, not global: two customers may both hold `AV-1`.
    *Migration V10* leaves every existing value untouched (they were globally unique, so they are
    trivially unique per customer), gives each existing account `CUS-<id>` as its Account ID, the
    `HO` prefix, and a counter starting at the highest handoff id it owns (V6 made every code
    exactly `HO-<id>`), so its next reference is one past anything it has already seen. Recipient
    tokens, PDFs, audit events, attachments and API ids are unaffected.

16. **Customers and support staff are separate identities that share a backend, not credentials.**
    An earlier design let a customer account be promoted to a staff role and reused the customer
    login — rejected: it mixed two kinds of people in one table, made "customer password promoted to
    support" possible, and tied staff access to customer registration. Now `support_staff` is its own
    table (Staff ID `STAFF-01`, name, email, BCrypt hash, role, active), with its own login
    (`/api/v1/support/auth/login`) and its own security chain for `/api/v1/support/**`. Customers have no
    role at all. Tokens are bound to an audience (`handoffly-customers` / `handoffly-support`): a customer
    token is not a staff token and vice versa, so each API simply does not recognise the other's credentials.
    The support chain re-checks `support_staff` on every request (member exists and is active; the role comes
    from the database, not the token), so deactivation and role changes apply at once. Migration V12 keeps the
    legacy `app_user.role` column (nothing destroyed) but gives it a default, and returns any customer an earlier
    version had promoted to being an ordinary customer.

17. **Entitlements are derived from the plan; support staff change the plan, explicitly and on the record.**
    `SubscriptionPlan` (`MONTHLY` default, `QUARTERLY`, `HALF_YEARLY`, `YEARLY`) carries what it includes (Contact
    Support area, tickets, direct call); nothing stores entitlements separately and there is no way to
    switch a feature on or off individually, so they cannot drift from the plan. The customer API
    reports them (`/auth/me`) so the UI can *hide* what is not included, and the backend enforces them
    (all customer ticket endpoints, reads included, are refused for plans without tickets). The call
    number comes from configuration and is only sent to plans that include calls. Because there are no
    payments yet, support staff change a customer's plan from the portal: the request names the plan the
    staff member was looking at (a change made on a stale view is refused), the portal asks for explicit
    confirmation first, a no-op is refused, and the change is audited. It does not claim that a payment
    happened. Customers have no way to change their plan. Each plan also carries its list
    price and billing period (`SubscriptionPlan`, INR) — the one place prices live; the public pricing page and the
    support portal (so staff know what to charge) read them from `GET /api/v1/public/plans`. The pricing page's
    four tiers map one-to-one onto these plans. The plan alone also decides *which* support entitlements
    exist, so no code checks for a particular plan: `SubscriptionPlan` carries them (Contact Support, message,
    tickets, direct call, `SupportPriority`), `SupportEntitlements` is the single projection that the customer API,
    the support portal and the public `GET /api/v1/public/plans` all serve, and "the plans the desk treats as
    priority" is `SubscriptionPlan.withElevatedPriority()` (a query, not a hard-coded plan). The pricing page builds
    its support lines from those entitlements. Final matrix: `MONTHLY` none; `QUARTERLY` Contact Support + message;
    `HALF_YEARLY` adds tickets (priority `PRIORITY`); `YEARLY` adds the direct call (priority `HIGHEST`); the core
    product is the same on every plan. Priority orders the desk's attention, it is not an SLA, and customers are not
    shown the desk's priority handling.

18. **Tickets are deliberately lightweight.**
    A ticket belongs to one customer account and stores only a subject, category, the request, an
    optional handoff reference **as plain text** (support never gets access to the handoff), a contact
    phone and a status (`OPEN → IN_PROGRESS → WAITING_FOR_CUSTOMER → RESOLVED → CLOSED`), with
    append-only replies. Rules live in one place (`TicketStatus`): a customer reply moves a waiting or
    resolved ticket back to `OPEN`; a support reply changes nothing by itself; `CLOSED` is final.
    Ticket IDs (`TKT-01`) come from a global counter like Account IDs. A customer asking for
    someone else's ticket gets "not found" (not "forbidden"), so ticket IDs cannot be probed.
    A `QUARTERLY` customer's support *message* is stored as the same kind of ticket with contact method `MESSAGE`
    (one model, one desk, one set of rules), but it goes through its own endpoint (`POST /api/v1/support-messages`,
    needs only Contact Support) and gives the customer no ticket list or reply ability — those endpoints still
    require a plan with tickets, so the backend, not the UI, keeps the two experiences apart. Support's reply email
    adapts: ticket plans are told to answer in the ticket, message-only plans to send another message. If the customer
    is later upgraded to a ticket plan, their earlier messages simply appear among their tickets.
    Attachments reuse the blob storage and the same upload rules as handoff attachments
    (`UploadPolicy`). A reply is written by a customer OR by a staff member (two identities, so two
    author references, exactly one set). Emails (new ticket → configured mailbox; customer reply → mailbox; support
    reply → customer) are sent after the change is committed and a mail failure never loses a ticket
    or reply. The model has no Jira fields; a future integration can map a `ticket_code`.

19. **The support portal is a separate app, not a section of the customer app.**
    Different audience, different release cadence, different origin (so a bug or XSS in one cannot
    reach the other's session — they even use different token storage keys), and the customer bundle
    never ships staff screens. It shares no code with `frontend/`; the duplication of a few labels
    and types is the price of that independence. Both talk to the one backend and the one database.

20. **API error mapping additions.** A lost race between two editors (optimistic lock) is `409
    concurrent_update`; malformed JSON, unknown enum values and missing/invalid request parameters or
    multipart parts are `400` — previously these surfaced as `500`.

21. **Support actions on a customer account are audited, append-only.**
    Every plan change and handoff-prefix change writes a `support_audit_event` row in the same
    transaction as the change: the customer, the previous and new value, the Staff ID, the time and an
    optional reason. The entity is immutable (Hibernate `@Immutable`, no setters) and its repository is
    deliberately not a `JpaRepository`, so the application can add and read records but never edit or
    delete them; the portal only displays them. A prefix "changed" to the value it already has is not a
    change and leaves no record.

22. **Three staff roles, granular permissions, admin-only staff management.**
    The generic `SUPPORT` role (identical to `ADMIN`) is replaced by `ADMIN`, `MANAGER` and `TICKET_AGENT`.
    A role is a fixed set of `SupportPermission`s (`SupportRole` is the only place that mapping exists); the staff
    filter grants the role's permissions as authorities, read from the database on each request, and every
    support endpoint asks for exactly one (`@PreAuthorize("hasAuthority(...)")`) — the controllers do not list roles,
    the portal receives the permission list from `/support/auth/me` instead of mirroring the role table, and
    the backend refuses whatever the UI hides. ADMIN holds everything (including `MANAGE_STAFF` and the full
    audit trail); MANAGER adds customer search/profile and plan/prefix changes to ticket work; TICKET_AGENT has
    tickets and ticket metrics only (no customer records, no priority-customer metric). Staff management
    (list/search, create, change role, deactivate/reactivate) is ADMIN-only, uses the existing `support_staff`
    table, and is audited in the existing `support_audit_event` table (extended: an event concerns a customer OR
    a staff member). Admin accounts are deliberately outside it: they are never created, re-roled or
    deactivated through the API (so an admin cannot lock the team out); they exist by bootstrap
    (`SupportStaffProvisioner`, create-only, from the environment) or direct database promotion. Migration V13
    maps existing `SUPPORT` staff to `MANAGER`: they could already change plans/prefixes and work tickets, which
    is exactly a manager's job, so nobody loses a capability and nobody gains staff management.

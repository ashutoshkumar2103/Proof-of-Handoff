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

23. **A plan is bought, not claimed: payment first, then a one-time token applies it.**
    Choosing a plan on the pricing page leads to checkout; the customer then signs up or signs in and the plan
    is applied automatically. Registration deliberately cannot set a plan (anyone could pick the top one for free),
    and neither can the client at any other point. Instead the backend records a `payment` (its own module, table
    `payment`) and hands back a high-entropy one-time token; only the token's SHA-256 hash is stored, like recipient
    links. A signed-in customer presents it to `POST /api/v1/payments/redeem`; under a row lock the backend checks it is
    known, unexpired and unused, sets the plan the payment bought (price from `SubscriptionPlan`, never from the request),
    and records who redeemed it and the plan it replaced. Unknown, expired and used tokens are refused identically.
    Paying needs no account so the pricing page can lead straight to it; applying needs one. The only provider is
    `DEMO` (no money, off unless `PAYMENT_DEMO_ENABLED=true`, then 404; a card form that accepts only the public test cards
    in `DemoCard` — one always approved, one always declined (402) — and refuses any other number without echoing or keeping it,
    so no real card data is ever handled): a real provider will confirm payments its own
    way and then feed the same token and redeem step. Support staff can still change plans by hand (decision 17).
    Plans now have an optional end date (decision 26), but still nothing changes a plan when it ends.

## Account security, subscriptions, jobs and file tools

24. **Password reset and "sign out everywhere" without server-side session storage.**
    A reset link carries a high-entropy one-time token; only its SHA-256 hash is stored (like recipient links),
    it expires (`PASSWORD_RESET_TTL_MINUTES`), works once, and a newer request makes older links useless. The
    request endpoint answers the same `204` for a known and an unknown email, so it cannot be used to find out who has
    an account; the email is sent asynchronously so response time does not give it away either. JWTs stay stateless
    but carry the account's `token_version` (`tv`); the customer filter now reads the account on every request (it
    must exist, be enabled, and match the version) and a password change or reset bumps the version, ending every
    older session while the session that made the change gets a fresh token. Staff sessions are a separate chain and
    are unaffected. Passwords follow one policy (`PasswordPolicy`) for register, change and reset.

25. **Abuse limits are in-memory sliding windows, applied where the abuse happens.**
    `RateLimiter` (a plain sliding-window counter) is used by an MVC interceptor for public endpoints (login,
    register, forgot/reset password, recipient links, payments, staff login) and directly by `AuthService` for
    failure-based limits (failed sign-ins per account and address, failed password changes) and by the job service
    for manual job runs. A limited call is `429` with `Retry-After` through the one error envelope. Deliberate
    limits: the counters live in one instance's memory (a restart clears them and several instances each count their
    own — acceptable for a first line of defence, and a shared store can replace the class without touching callers),
    and addresses are only trustworthy behind a proxy once `FORWARD_HEADERS_STRATEGY=native` is set. Uploads are
    checked by their content (`ContentSniffer` compares magic bytes with the declared type) on top of type and size,
    and every response carries the security headers (CSP `default-src 'none'`, no-referrer, nosniff, frame deny, HSTS
    over HTTPS).

26. **A subscription has a start, an optional end and a status that is derived, not stored; its history is append-only.**
    `app_user.plan_started_at` / `plan_valid_until` (V17) are NULL for every existing customer on purpose: the real dates
    were never recorded and are not invented, and NULL means "no end date", exactly how existing plans behaved, so
    nobody's access changes. `ACTIVE`/`INACTIVE` follows from `plan_valid_until`, so it cannot go stale. When a plan has
    lapsed the customer keeps their plan on record (a renewal picks it up again) but the support extras fall back to the
    `MONTHLY` entitlements. A lapsed subscription also stops the customer STARTING a new handoff, SENDING a draft and using
    HANDOFFCHECK (the product's core gate, not a support extra). The one rule is `UserService.requireActiveSubscription`, using
    the same `User.subscriptionStatus`. `HandoffService` asks it at the top of `create` — the one place any handoff is persisted,
    so New handoff, Duplicate (its copy is saved through `create`) and import-assisted drafts all share it — at the top of
    `submit` (the only route that turns a draft into an outgoing handoff), and when a duplicate's template is requested, so the
    customer hears it before filling in a form. HandoffCheck asks it once for the whole feature: a `@ModelAttribute` method on
    `DocumentCheckController` runs before every endpoint in that controller (compare, read a file, export, import items, compare
    files, import a returns file) and before any request body or file is read, so an endpoint added there later is covered
    without anyone remembering. That includes the *Import from File* route of recording a return, which is a HandoffCheck
    feature; recording a return by hand is not HandoffCheck and stays open. Each check asks the clock afresh, inside the request
    and before anything is saved, sent or read, and nothing the client sends or remembers is consulted: that is the race
    protection (a plan can run out a second after a page last checked). It answers `403` with code `subscription_expired`
    through the ordinary error envelope. Nothing that already exists is gated (viewing handoffs and drafts, manual returns,
    closing, resending a link, recipient links, PDFs). The New handoff and HandoffCheck pages share one hook,
    `useSubscriptionGate`, which polls the account (`/auth/me`, no new endpoint) on open, every 30 seconds while visible and on
    tab-visible/window-focus — for responsiveness only, never for security — and shows the one shared dialog (a single OK, then
    the Dashboard). Every plan change — by staff or by a payment — appends
    a `subscription_history` row (the entity is `@Immutable`, the repository cannot update or delete); the migration
    backfilled it only from real audit and payment rows. Staff change plans and end dates through the same audited,
    confirmed endpoint as before (`MANAGE_CUSTOMERS`: administrators and managers; ticket agents cannot). The plans stay
    exactly `MONTHLY`, `QUARTERLY`, `HALF_YEARLY`, `YEARLY`. Staff ids in history are plain ids so the user module does not
    depend on the support module.

27. **Duplicate a handoff = a template the backend builds, and nothing is created until the customer saves.**
    `GET /handoffs/{id}/template` returns the reusable parts (title, parties, items, notes) and none of the lifecycle
    (no status, reference, dates, acknowledgement, returns, attachments or links). The create page is prefilled with it and
    saving goes through the ordinary create endpoint, so the copy is always a fresh draft with its own reference, the
    validation is the existing validation, and the owner check is the existing owner check.

28. **Item import and comparison export reuse HandoffCheck; neither stores or creates anything.**
    Importing items reads a CSV or Excel file with the existing `DocumentLineExtractor` (header detection, number
    parsing) and returns rows for the customer to review inside the New Handoff table; duplicates are flagged rather
    than merged, skipped rows are counted, and nothing is submitted. Exporting a comparison (PDF or CSV) re-runs the
    same `compare` from the same request, so the file is exactly what was shown, with CSV formula-injection protection;
    nothing is stored and the standalone HandoffCheck mode stays independent of handoffs.

29. **Customer jobs: four per customer, off by default, one email per run, scoped by construction.** (A fifth job was added later: decision 35.)
    Return Reminder (due tomorrow or the day after — not today, not overdue), Overdue Reminder, Missing Item Reminder
    (open handoffs only, so a force-closed handoff is never reminded about) and Weekly Summary. Each customer has one row
    per job (`customer_job`, unique `(user_id, job_type)`), created the first time they open it and switched off, with its
    own cron expression, timezone, next and last run; only the latest result is kept. Every service method takes the
    customer's id and finds jobs and handoffs only by it, and no URL carries a job or handoff id that could name
    someone else's. `JobSchedule` is the only place a cron expression is interpreted: Spring's six-field `CronExpression`,
    restricted characters, a real timezone, a schedule that actually runs, and a minimum gap (default 60 minutes) so
    nobody can ask for a job that fires every few seconds. A run builds ONE email from the reusable template (heading in
    bold in the HTML part), or none if there is nothing to say. Run Now never changes the schedule or whether the job is
    on; Run All Now runs the four jobs of the current customer only. One ticker (`JobScheduling`) runs whatever is due; a
    due job is claimed by moving its next run on with a compare-and-set update, so two passes or two instances cannot
    both run it, and a job missed while the application was down runs once.

30. **The support expiry reminder is a separate, platform-wide job that can only send email.**
    Administrators and managers (the existing `MANAGE_CUSTOMERS` permission) can see, schedule, pause, resume and run it from
    the portal's Jobs page. It emails customers whose subscription ends within a configurable window (default 7 days,
    1–90), once per end date: `subscription_expiry_reminder` has a unique `(user_id, valid_until)` and a reminder is
    reserved before it is sent, so even overlapping runs cannot double-send, and a failed email releases its reservation so
    the next run retries. Renewing moves the end date, so the next period is reminded about again. It never touches a plan
    or an end date, is switched off until staff turn it on, and shares the schedule validation, the claim mechanism and the
    email template with the customer jobs.

31. **HandoffCheck (the comparison tool) is a plan feature; the file imports are not.**
    `SubscriptionPlan.includesHandoffCheck()` is true for `HALF_YEARLY` and `YEARLY` and false for `MONTHLY` and `QUARTERLY`. It
    sits in the same enum as the support columns (that enum is where "what a plan includes" lives) but as its own flag,
    deliberately apart from `SupportEntitlements`: the two concepts share the plan as their source and nothing else. Nothing is
    stored per customer, so a plan change by support changes it at once, and — like every plan-dependent feature — it asks
    `User.entitledPlan()`, so a lapsed subscription is treated as the fallback plan. The account response carries it as
    `handoffCheck` (the backend decides; the app never encodes which plans). The one backend rule is
    `UserService.requireHandoffCheckPlan`, refusing with `403` and the code `plan_required` (distinct from
    `subscription_expired`, so a client can tell "not in your plan" from "ended"); `DocumentCheckController`'s single
    `@ModelAttribute` runs it after the active-subscription check, before any body or file is read, for every endpoint
    **except** two named ones: `import-items` (New handoff's item import) and `return-import` (Returns' *Import from File*).
    Those two reuse HandoffCheck's file reader but belong to New handoff and Returns, which work on every plan, so they stay
    open; every other endpoint, including any added later, is the tool and is locked by default. An ended subscription is
    answered as ended first, for all six endpoints, exactly as before. In the app the menu entry stays visible but dimmed with a
    lock, and the page says "HandoffCheck is available on Half-Yearly and Yearly plans." with the existing link to the plans;
    return-import mode of the page is never locked. The menu and the page lock only when the account says `handoffCheck` is
    `false` — never because it said nothing — so a backend that is older than the app cannot make them disagree. The page
    learns of a plan change the way it learns of an ended
    subscription (decision 26): its own check of the account on open, every 30 seconds and on focus, and a `plan_required`
    refusal from the backend makes it look again at once. No migration, no new table.

32. **A new account has no plan; a plan's duration is worked out in exactly one place.**
    Registration used to leave an account `MONTHLY` and active: the entity defaulted its plan to `MONTHLY`, its constructor stamped
    a start, and an absent end date means "no end date" (decision 26), which is `ACTIVE` — so an unpaid sign-up looked like a paying
    customer, and the column itself (`NOT NULL DEFAULT 'MONTHLY'`) made "no plan" impossible to store. Now `app_user.subscription_plan`
    may be empty (V20) and a new account has no plan, no start and no end; registration still can never set one (decision 23). That
    is a state in its own right, `User.hasPlan()`, not a flavour of "ended": the status stays binary — `INACTIVE` — so every screen that
    already treats `INACTIVE` as "not usable" stays fail-safe, and `hasPlan()` (`plan: null` in the API) is the one extra fact that picks
    the message. `UserService.requireActiveSubscription`, the single rule behind every paid action (decision 26), refuses with code
    `no_active_subscription` and "No active plan is associated with this account…" for an account that never had a plan, and with the
    existing `subscription_expired` for one whose plan ran out; callers are unchanged. The code is lower-case snake like the others.
    Support gets one stated exception rather than a plan: `SupportEntitlements.of(User, phone)` gives an account with no plan the
    "activation help" — Contact Support and tickets at normal priority, no message form, no phone — from the same projection that
    produces every plan's entitlements, so the customer app, the portal and the ticket checks agree; it stops existing the moment
    a plan does. Nothing that is not a no-plan account changes: an expired plan keeps its message and its (lapsed) entitlements.
    **Duration.** `SubscriptionPlan.validUntil(start)` is the only place an end is calculated — `MONTHLY` 30 days, `QUARTERLY` 90, `HALF_YEARLY`
    6 calendar months, `YEARLY` 365, in UTC like every other date — used by a payment, a renewal and a support change.
    `months()` stays only as the billing cycle the pricing pages divide the price by. A payment or an activation without a typed date
    gets that end; renewing a plan still active carries on from its current end; a support member can still name a last day
    (overriding it), and naming the plan the customer already has still needs one (that action only changes validity). Because
    "empty" now means "the plan's own duration", a plan can no longer be given *no* end date from the portal; accounts that predate
    end dates keep theirs (none). A staff request names what the customer has now (`fromPlan`), and none means "no plan": the stale-view
    check applies unchanged, so omitting it can never change a customer who has a plan. The first plan an account ever gets is recorded
    in `subscription_history` with no previous plan (that column was already nullable) and as `plan_before = NULL` on the payment,
    whose check constraint V20 relaxes for exactly that. No existing row is rewritten.

33. **Moving up a plan costs the difference of list prices, worked out by the backend and paid in one step.**
    A customer on an active plan that lacks HandoffCheck is shown, on the HandoffCheck page itself, the plans above theirs that include
    it, priced as what is left to pay. The rule is one line in the enum that owns the prices — `SubscriptionPlan.upgradeAmountFrom`
    (new list price minus the current plan's, which counts in full as already paid) — so Quarterly 549 → Half-yearly 999 is 450.
    The user chose that over pro-rating by unused time: it is simple and explainable, at the price that the used part of the old
    plan is credited too. `PaymentService.upgradeOptions` lists every dearer plan (the response says which include HandoffCheck, so the
    screen filters by a flag, never by plan name, as in decision 31); `payUpgradeDemo` locks the customer's row, recomputes the
    amount from the plan they are on at that moment, checks it against the amount they were shown (`expectedAmount`: a guard, never
    a price — a mismatch is a `409` that charges nothing), takes the demo card through the same checks as any demo payment, records the
    payment for the difference and applies it through `redeem`, so there is still exactly one place that applies a paid plan. It is
    deliberately one step with no token: a token bought at a customer's discount could be redeemed on another account, and nothing
    else needs one because the customer is already signed in. The row lock also makes a double click safe — the second request finds the
    customer already on that plan and is refused. The new plan starts now and lasts its own duration (decision 32); the old plan is
    replaced, not extended. Only an *active* plan is upgraded: no plan or an ended plan is bought outright at the list price, as before.
    Entry point only the HandoffCheck page for now; the Account page and the pricing page are unchanged and still charge the list price.
    The card form moved out of the checkout page into one shared component (`DemoCardFields`, with its helpers in `lib/checkout`)
    rather than being copied. No migration: `payment.amount` already holds what was charged and `plan_before` what it replaced.

## Reports

34. **Reports show the same per-handoff figures as the Dashboard and the handoff page, for the handoffs created in a period.**
    A read-only page (`GET /api/v1/reports/handoffs` and `/export`) in the `handoff` module, next to `HandoffActivityService` — the
    other period view of one customer's handoffs — rather than a new module. *Which date:* the period is matched against the date each
    handoff was **created**: the one date every handoff has (a draft has no sent date), so each handoff falls in exactly one period and
    the table rows add up to the summary cards. The alternative — counting each thing on its own date (closed in the period, returned in
    the period) — answers "what happened this week", gives rows that do not add up to the cards, and is what the Weekly Summary job
    already does; Reports answers "how did the handoffs I created in this period do" and says so on the page. *Which zone:* whole
    calendar days, both ends included, in the zone the caller names (the browser's; UTC if none), echoed back and written into the CSV,
    so the table's dates (shown in the browser's zone) and the filter always agree. *Which numbers:* no new calculation. Every handoff's
    figures are `HandoffMapper.toSummary`, fed with the return totals of the whole period fetched at once (`ReturnQueryService`
    overloads, same confirmed-only rule, same `netMissing` formula) instead of one query per handoff; the summary, the status/overdue
    filter, the sorting, the page and the CSV are then plain operations on those rows, so they cannot disagree, and a test compares
    every row and total with the handoff page. "Open" is `HandoffActivityService.OPEN_STATUSES`, "overdue" is `HandoffMapper.isOverdue`
    (both as of now, not as of the end of the period), and the item totals count handoffs that were sent — a draft has given nothing.
    Every item given is back, missing or neither (the handoff's `remaining`, outgoing = returned + missing + remaining), so that third part is
    shown too, split by where it stayed — on a rejected handoff, on a cancelled one, or still out on any other — and the row always adds up:
    given = returned + missing + still out + rejected + cancelled. Showing only returned and missing left a difference (for example a
    rejected handoff's whole quantity) that the customer had to go and find.
    The summary ignores the status filter (it describes the period); the table and the CSV rows follow it. *Who:* the customer is always
    the signed-in one (no parameter names another); every lookup is by that id. *Access:* `UserService.requireActiveSubscription` first
    thing in both endpoints, on any plan — no new entitlement, flag or column — and the page uses the same `useSubscriptionGate` and
    dialog as New handoff and HandoffCheck. *Limits:* a period may hold at most 10,000 handoffs (otherwise 400, "Choose a shorter
    period") and dates must lie between 1970 and 9998; reading a period is done in memory in three queries — chosen over SQL
    aggregates so there is still exactly one quantity calculation — and is the thing to revisit if accounts outgrow that limit.
    *CSV:* `common.util.Csv` (quoting, spreadsheet-formula protection, plain quantities). The HandoffCheck export has its own private
    copies of the same few lines and was deliberately left untouched; moving it onto `Csv` is a follow-up. *PDF:* deferred — each of the
    two PDF generators keeps its own private OpenPDF layout helpers (fonts, tables, page numbers), so a third report would copy them or
    force edits to the Proof-of-Handoff PDF and the HandoffCheck export. No migration, no stored report, nothing changes in any handoff or return.

35. **A fifth customer job, Recipient Response Reminder, is one more value of the existing job type — nothing else is new.**
    It tells the customer about handoffs that went out and whose recipient has neither accepted nor declined after a day, so one
    that was never opened does not sit unnoticed. It is the same machinery as the other four: `JobType.RECIPIENT_RESPONSE_REMINDER`
    (stored in the existing `customer_job.job_type` column, which has room and no value list, so **no migration**; every
    customer's job list and Run All Now already walk `JobType.values()`, and a customer who has the four gets the fifth, switched
    off, the next time their jobs are looked at), the same cron and timezone checks, the same claim by the ticker, the same
    one-email-per-run and none-when-empty rules, the same monitoring row. *Which handoffs:* those in `AWAITING_RECIPIENT` — the one
    state in which the recipient is offered accept or decline (`RecipientService`), so there is no second definition of "has not
    responded" — that went out at least 24 hours ago (`RESPONSE_WAIT`, a constant beside `DUE_SOON_DAYS`, in one place). The wait is
    elapsed time from the handoff's own sent time, not a calendar day, so the customer's timezone only decides how the sent date is
    written in the email (as for the other jobs). When the recipient answers the status moves to active or rejected (or the owner
    cancels), and the handoff drops out with nothing to switch off. The query is `HandoffActivityService.awaitingRecipient`, read-only
    and by owner id like its neighbours. *Email:* one per run, oldest first, each handoff once: reference, title, recipient, the date
    it was sent and how many days it has waited. It carries no recipient link: a link is only ever stored hashed, and issuing a new
    one would be a change to the handoff, which a job never makes — the note says to use Resend link. *Consequence to know:* Run All
    Now now costs five of the hourly run allowance instead of four.

# CLAUDE.md — HandOffly Engineering Rules (MANDATORY)

HandOffly is a **universal Proof-of-Handoff and Return Tracking** application.
It is **NOT** an inventory-management system. The core concept is a lifecycle record:

```
Give → Acknowledge → Active With Recipient → Return Pending → Partial Return(s) → Full Return → Closed
```

One generic, reusable **handoff engine** serves every domain (construction, events,
IT, education, rentals, office assets, documents, keys, repair shops, ...). Domain
differences are handled with **categories/templates**, never with per-industry code.

---

## 1. Architecture

- **Modular monolith.** One Spring Boot app, package-by-module. Never microservices.
- Backend modules (Java packages under `com.handoffly`):
  `common`, `auth`, `user`, `handoff`, `returns`, `recipient`, `attachment`,
  `audit`, `notification`, `documentcheck`, `support`, `payment`, `job`.
- Keep module boundaries clean: a module exposes services; other modules depend on
  those services, not on each other's internals.
- **Backend is the single authoritative source of business rules.** The frontend may
  mirror rules for UX only; it never becomes the source of truth.

## 2. Technology (baseline — prefer latest *stable*, never alpha/beta/RC/milestone)

- Java 25 LTS, Spring Boot 4.1.x, Spring Web / Security / Data JPA, Hibernate,
  Bean Validation, Maven, Flyway, MySQL 8.4 LTS.
- Frontend: React 19, TypeScript, Vite, React Router, TanStack Query. Two separate apps on the
  one backend: `frontend/` (customers) and `support-portal/` (support staff). They share no code.
- No Lombok (JDK-lag risk on Java 25) — use Java records for DTOs and plain
  entities. This is a deliberate, documented decision (see `docs/DECISIONS.md`).

## 3. DO NOT DUPLICATE CODE (hard rule)

Before creating **any** file / class / interface / method / DTO / entity / enum /
service / repository / controller / component / hook / utility / validator / mapper:

1. **Search the codebase first** (Grep/Glob).
2. If something can be reused or extended, **reuse or extend it**.
3. Only create new code when nothing fits.

UI follows `docs/UI_GUIDELINES.md`: reuse the existing shared component/style (`ActionMenu`, the `.modal` markup, the tokens in `index.css`) rather than a visually different
implementation of a pattern that already exists.

Never create `FooService2`, `FooServiceNew`, `FooServiceFinal`, duplicate DTOs,
duplicate utilities, duplicate components, or duplicate validation. Never solve the
same problem twice. **Modify before creating. Search before implementing.**

## 4. One source of truth

Status-transition rules live in **one** place: `handoff` module's state machine
(`HandoffStatus` + `HandoffStateMachine`). Do not re-encode transition rules in
controllers, repositories, or the frontend as authority.

## 5. No magic values

Use enums / constants / configuration. No scattered magic strings or numbers.

## 6. History is sacred

- Outgoing quantities are **never** overwritten by returns.
- Returns are **separate rows** (`return_event`, `return_line`) inside the **same**
  handoff. Remaining = outgoing − Σ returned, computed by the backend.
- Lifecycle events are appended to `audit_event`; never mutated or deleted.
- After `CLOSED`, records are read-only. Corrections preserve history, never
  silently overwrite.

## 7. Database

- MySQL via **Flyway migrations only**. Never modify schema outside migrations.
- Migrations are **immutable once merged**. New change = new `V__` file.
- Proper FKs, indexes, constraints, and transaction boundaries.
- Files/blobs live in file/object storage, **not** in MySQL — only metadata in DB.
- Tests run on H2 in MySQL-compat mode; keep migration SQL portable.

## 8. Security (from day one)

- BCrypt password hashing. JWT stateless auth for app users.
- Recipient links use opaque, high-entropy tokens, **stored hashed**, with expiry.
  A recipient token grants access to **exactly one** handoff — never others.
- Validate all input. Validate uploaded file type & size. Prevent path traversal.
- Centralized error handling; never leak stack traces. Never log secrets/tokens.
- CORS configured explicitly. Rate-limit sensitive/public endpoints.
- **No secrets in source.** Config via environment variables only.

## 9. Legal wording

A typed name is a **"Typed acknowledgement" / "Recipient acknowledgement"** — never
call it a legally binding e-signature. No unsupported legal claims in code or UI.

## 10. API consistency

- Base path `/api/v1`. Consistent naming, HTTP methods, status codes.
- Errors use one envelope (RFC 7807 ProblemDetail + `errors` for field violations).
- ISO-8601 UTC instants for all timestamps. Consistent pagination.

## 11. Quality workflow

- Small, logical, coherent changes. After each substantial change: **compile, run
  tests, fix failures, verify.** Don't accumulate unverified changes.
- **Test what you build:** success paths, validation failures, authz failures,
  invalid transitions, partial/multiple returns, closing, duplicate requests,
  expired links, missing/malformed data.
- No useless files, no dead code, no speculative "just-in-case" abstractions.
- **Messages disappear after 5 seconds.** Any message that reports the result of something the user just did (done, refused,
  failed) must clear itself after 5 s via the app's `useTransient` hook (`ui.tsx` in `support-portal/`; `frontend/` has the same hook
  and keeps its form/validation errors on screen) — never a bare `useState` plus a notice. Things that are the page's own state
  (a failed load, a closed ticket, a locked feature) stay for as long as they are true.
- Preserve working functionality; don't rewrite without a strong, evidenced reason.
- Update existing docs instead of creating duplicates.

## 12. AI features are optional & modular

The core workflow is deterministic and requires **no AI**. Any future AI (OCR,
extraction, damage detection) must be an isolated, optional module.

---

**When in doubt: search first, reuse, keep it simple, preserve history, secure by
default, and keep the backend authoritative.**

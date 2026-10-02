# HandOffly — Implementation Plan

A universal **Proof-of-Handoff & Return Tracking** system. Generic handoff engine,
domain-agnostic, driven by categories/templates. Modular monolith.

## Stack
- Backend: Java 25, Spring Boot 4.1.1, Spring Web/Security/Data JPA, Hibernate,
  Bean Validation, Flyway, MySQL 8.4 (H2 MySQL-mode for tests). JWT auth (jjwt).
- Frontend: React 19 + TypeScript + Vite + React Router + TanStack Query.
- Docker + docker-compose for MySQL + app.

## Domain model (core)
```
User ──creates──> Handoff ──has──> HandoffItem (outgoing line, qty never mutated)
                     │
                     ├── RecipientLink (hashed token, expiry, one handoff)
                     ├── ReturnEvent ──has──> ReturnLine (qty returned against an item)
                     ├── Attachment (metadata; blob in storage)
                     ├── AuditEvent (append-only lifecycle log)
                     └── status: HandoffStatus (state machine controlled)
```
Remaining(item) = item.quantity − Σ ReturnLine.quantity for that item.
Handoff is FULLY_RETURNED when every item's remaining == 0.

## Status state machine
`DRAFT → OUTGOING_SENT → AWAITING_RECIPIENT → ACTIVE_WITH_RECIPIENT →
 RETURN_PENDING → PARTIALLY_RETURNED → FULLY_RETURNED → CLOSED`
Exceptional: `REJECTED, CANCELLED, DISPUTED, OVERDUE`.
Transitions are enforced by `HandoffStateMachine` (single source of truth).

## Phases (build order)
- **Phase 1** ✅ setup, auth, users, DB+Flyway, layout, handoff create/items/draft,
  outgoing submission.
- **Phase 2** recipient secure link, review page, accept/reject, typed
  acknowledgement, status transitions, email notification.
- **Phase 3** returns on same handoff, partial + multiple returns, remaining calc,
  notes/condition, return confirmation, close/lock.
- **Phase 4** attachments, reference documents, audit history, dashboard, filter/search.
- **Phase 5** HandoffCheck document comparison + result UI.
- **Phase 6** hardening, security, tests, Docker, CI/CD readiness, prod config.

## API surface (v1)
- `POST /api/v1/auth/register`, `POST /api/v1/auth/login`, `GET /api/v1/auth/me`
- `GET/POST /api/v1/handoffs`, `GET/PATCH/DELETE /api/v1/handoffs/{id}`
- `POST /api/v1/handoffs/{id}/items`, item update/delete (draft only)
- `POST /api/v1/handoffs/{id}/submit` (send outgoing → email link)
- `POST /api/v1/handoffs/{id}/cancel`, `/dispute`
- `POST /api/v1/handoffs/{id}/returns`, `POST .../returns/{rid}/confirm`
- `POST /api/v1/handoffs/{id}/close`
- `GET/POST /api/v1/handoffs/{id}/attachments`, `GET .../attachments/{aid}/content`
- `GET /api/v1/handoffs/{id}/events`
- `GET /api/v1/reports/handoffs` (read-only period report: totals + one page), `GET /api/v1/reports/handoffs/export` (CSV)
- Recipient (public, token-scoped): `GET /api/v1/r/{token}`,
  `POST /api/v1/r/{token}/accept`, `/reject`, `POST /api/v1/r/{token}/returns`
- `POST /api/v1/handoff-check` (compare reference doc vs handoff / doc vs doc)

See `docs/DECISIONS.md` for defaults chosen and rationale.

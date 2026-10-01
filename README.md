# HandOffly

**Universal Proof-of-Handoff & Return Tracking.**

HandOffly records exactly what was handed over, who received it, when, and the
recipient's acknowledgement — then keeps the **same** record open until the items are
returned. Returns are entered against that same handoff, so the system always knows
what is still outstanding.

```
Give → Acknowledge → Active With Recipient → Return Pending → Partial Return(s) → Full Return → Closed
```

It is **not** an inventory system. One generic handoff engine serves every domain —
construction tools, event/tent-house materials, IT equipment, school assets, rentals,
office assets, documents, keys, repair jobs — via free-form categories, not per-industry
code.

## Tech stack

- **Backend:** Java 25, Spring Boot 4.1, Spring Web/Security/Data JPA, Hibernate,
  Bean Validation, Flyway, MySQL 8.4 (H2 in MySQL-mode for tests). Stateless JWT auth.
- **Frontend:** React 19, TypeScript, Vite, React Router, TanStack Query.
- **Packaging:** Docker + docker-compose. Modular monolith (no microservices).

See [`CLAUDE.md`](CLAUDE.md) for engineering rules, [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)
for the plan, and [`docs/DECISIONS.md`](docs/DECISIONS.md) for design decisions.

## Modules

`auth · user · handoff · returns · recipient · attachment · audit · notification · documentcheck`

## Running

### Option A — Docker (MySQL + backend + frontend)

```bash
cp .env.example .env    # then edit secrets (JWT_SECRET, DB passwords)
docker compose up --build
```

- Frontend: http://localhost:5173
- Backend API: http://localhost:8080/api/v1
- Health: http://localhost:8080/actuator/health

The Maven project lives in `backend/` (a root aggregator `pom.xml` also exists). The
helper scripts pick JDK 25 automatically (your system `JAVA_HOME` may point at an older
JDK that can't build Java 25). **The default database is MySQL** — H2 is only used when
you explicitly ask for it, so a plain run never silently writes to the wrong store.

### Option B — Local dev with MySQL (default; see tables in MySQL Workbench)

1. In MySQL Workbench, open [`db/setup-mysql.sql`](db/setup-mysql.sql) and run the **whole
   script** ("Execute all" / Ctrl+Shift+Enter). It creates the `handoffly` database and a
   `handoffly`/`handoffly` user matching the app's defaults. Flyway creates the **tables**
   on startup — don't create tables by hand.
2. Start the backend (double-click **`run-backend-mysql.bat`**, or `.\run-backend.ps1`).
   Confirm the console shows `Database JDBC URL [jdbc:mysql://...]` and
   `Successfully applied 1 migration`.
3. Start the frontend (double-click **`run-frontend.bat`**, or `.\run-frontend.ps1`).
4. In Workbench, refresh SCHEMAS → **handoffly** → **Tables**. Register an account in the
   UI, then `SELECT * FROM handoffly.handoff;`, `SELECT * FROM handoffly.handoff_item;`,
   `SELECT * FROM handoffly.return_event;` to watch the data change.

To use different credentials, set `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` env vars
(see `.env.example`) instead of the defaults.

### Option C — Local dev without MySQL (H2)

When you don't want to run MySQL at all, use H2 (a file-backed DB in MySQL-compat mode,
same Flyway migrations). This is a **separate store** from MySQL — data does not cross over.

```powershell
.\run-backend.ps1 -Profile h2   # or double-click run-backend-h2.bat
```

Then start the frontend as above. Because dev email uses the **logging** sender, the
recipient review link is printed in the backend console instead of being emailed.

## Testing

```bash
cd backend
mvn test
```

Covers the state machine, the full HTTP lifecycle (create → submit → accept →
partial returns → full return → close), authorization, over-return guards, expired/
invalid links, and document comparison — all on H2, no external services required.

## Key API endpoints (v1)

| Area | Endpoint |
|------|----------|
| Auth | `POST /api/v1/auth/register`, `/login`, `GET /auth/me` |
| Handoffs | `GET/POST /api/v1/handoffs`, `GET/PATCH/DELETE /handoffs/{id}`, `GET /handoffs/dashboard` |
| Lifecycle | `POST /handoffs/{id}/submit` · `/resend-link` · `/cancel` · `/dispute` · `/close` |
| Items | `PUT /handoffs/{id}/items` |
| Returns | `POST /handoffs/{id}/returns`, `POST /handoffs/{id}/returns/{rid}/confirm` |
| Attachments | `GET/POST /handoffs/{id}/attachments`, `GET .../{aid}/content`, `DELETE` |
| Events | `GET /handoffs/{id}/events` |
| Recipient (public) | `GET /api/v1/r/{token}`, `POST /r/{token}/accept` · `/reject` · `/returns` |
| HandoffCheck | `POST /api/v1/handoff-check` |

## Security notes

- Passwords are BCrypt-hashed; API auth is stateless JWT.
- Recipient links are opaque 256-bit tokens, **stored hashed**, expiring, and scoped to
  a single handoff.
- Uploads are type- and size-validated; blobs live in file/object storage, never in the DB.
- No secrets in source — all configuration is environment-driven.
- A typed name is a **"typed acknowledgement"**, never described as a legally binding
  e-signature.

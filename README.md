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

### Real email delivery (SMTP)

By default nothing is emailed: the **logging** sender prints each message (including the
name and size of any PDF attachment) in the backend console, marked
`[DEV EMAIL — not actually sent]`, and the app tells you nothing was sent. To send real
email — the recipient review link and **Email PDF** — give the backend SMTP settings.

**Easiest (VS Code, Maven, any local run):** create a file named **`.env.local`** in the repo
root — it is git-ignored and the backend reads it on startup — then restart the backend. Example
for Gmail with an [app password](https://myaccount.google.com/apppasswords) (2-step
verification required; paste the password exactly as shown, spaces included, no quotes):

```properties
MAIL_PROVIDER=smtp
SMTP_HOST=smtp.gmail.com
SMTP_PORT=587
SMTP_AUTH=true
SMTP_STARTTLS=true
SMTP_USERNAME=you@gmail.com
SMTP_PASSWORD=your app password here
MAIL_FROM=you@gmail.com
```

**Or** set the same names as environment variables before starting the backend (these win over
`.env.local`). Never commit credentials.

```powershell
$env:MAIL_PROVIDER  = "smtp"
$env:SMTP_HOST      = "smtp.gmail.com"
$env:SMTP_PORT      = "587"
$env:SMTP_AUTH      = "true"
$env:SMTP_STARTTLS  = "true"
$env:SMTP_USERNAME  = "you@gmail.com"
$env:SMTP_PASSWORD  = "<your app password>"
$env:MAIL_FROM      = "you@gmail.com"
.\run-backend.ps1
```

If sending fails, **Email PDF** reports "The email could not be sent" and the cause is in
the backend log.

### Email wording (template)

The text of the **Email PDF** message comes from a reusable template:
`backend/src/main/resources/mail/proof-of-handoff-email.txt`. Edit it and restart the backend.
An optional first line `Subject: …` sets the subject; everything after it is the body.

```text
Subject: Proof of Handoff — {handoffCode}

Hello {recipientName},

Attached is the Proof-of-Handoff record for {handoffCode}.
Handoff: {handoffTitle}
Attachment: {attachmentName}

Thank You,
{senderName}
```

| Placeholder | Filled with |
|---|---|
| `{recipientName}` | the recipient's name ("there" if blank) |
| `{senderName}` | the sender (the party who gave the items) |
| `{handoffCode}` (or `{quotationCode}`) | the handoff reference, e.g. `HO-3` |
| `{handoffTitle}` (or `{quotationTitle}`) | the handoff title |
| `{attachmentName}` | the PDF's filename, e.g. `HandOffly-HO-3-Proof-of-Handoff.pdf` |

An unknown placeholder is left as typed, so a typo is visible in the email. To keep your own copy
outside the project and change it **without restarting** (it is re-read on every send), set
`MAIL_PDF_TEMPLATE_FILE` — for example in `.env.local`, using forward slashes:
`MAIL_PDF_TEMPLATE_FILE=C:/Users/you/handoffly-email.txt`. If that file can't be read, the built-in
template is used and a warning is logged.

## Proof-of-Handoff PDF

On a handoff's detail page (any status except draft) **Download PDF**, **Share** and
**Email PDF** produce the record on demand from live server data (nothing is stored). It
lists the handoff, parties, acknowledgement, items, full return history, final summary,
lifecycle events and attachments (names only). A closed handoff is labelled **FINAL RECORD**;
any other status is labelled **INTERIM RECORD — NOT FINAL** with its current status. All times
in the PDF are UTC. **Share** uses the device's native share sheet where the browser supports
sharing files, and otherwise downloads the PDF.

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
| PDF | `GET /handoffs/{id}/pdf` (`application/pdf`), `POST /handoffs/{id}/email-pdf` (`{ "to"? }`) |
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

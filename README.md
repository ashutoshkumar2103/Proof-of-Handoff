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
- **Support portal:** a second, separate React 19 + TypeScript + Vite app for support staff,
  on the same backend and database.
- **Packaging:** Docker + docker-compose. Modular monolith (no microservices).

See [`CLAUDE.md`](CLAUDE.md) for engineering rules, [`docs/IMPLEMENTATION_PLAN.md`](docs/IMPLEMENTATION_PLAN.md)
for the plan, and [`docs/DECISIONS.md`](docs/DECISIONS.md) for design decisions.

## Modules

`auth · user · handoff · returns · recipient · attachment · audit · notification · documentcheck · support`

## Repository layout

| Folder | What it is |
|---|---|
| `backend/` | The one Spring Boot application: every API, one MySQL database |
| `frontend/` | The customer web app |
| `support-portal/` | The support staff web app — its own UI and build, the same backend. Shares no code with `frontend/` |
| `db/` · `docs/` | MySQL setup script · design notes |

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

## Accounts, plans and support

**Account ID.** Every customer has a permanent Account ID such as `CUS-42`, shown on their
dashboard; support finds customers by it. The internal numeric id is never shown.

**Handoff references are numbered per customer.** Each customer's handoffs count up from 1 under
their own prefix — `HO-1, HO-2, …` by default, `AV-1, AV-2, …` once support sets the prefix `AV`.
Two customers can both have an `AV-1`; the internal database ids stay globally unique and are what
the API addresses a handoff by. Numbers never restart: changing a prefix only affects *new* handoffs and
carries on from the customer's current number, and numbers are issued under a row lock so concurrent
creation can never repeat one. A prefix is 2–5 capital letters.

**Plans.** Every customer is on a plan; new accounts start on `MONTHLY`. There is no public way to
choose or change one. What a plan includes is derived from the plan alone — nobody edits
entitlements separately, so changing the plan changes them automatically:

| Plan | Contact Support in the app | Send a message | Support tickets (create, view, reply) | Phone support | Support priority |
|---|---|---|---|---|---|
| `MONTHLY` (default) | – | – | – | – | Normal |
| `QUARTERLY` | ✓ | ✓ | – | – | Normal |
| `HALF_YEARLY` | ✓ | ✓ | ✓ | – | Priority |
| `YEARLY` | ✓ | ✓ | ✓ | ✓ | Highest |

The core product — handoffs, returns, HandoffCheck, PDFs — is identical on every plan; plans differ only in support.
A `QUARTERLY` customer's **Contact Support** page is a simple message form (subject, message, optional handoff
reference and attachment): support receives it as a ticket and replies by email, but the customer has no ticket list
and cannot reply in the app (the backend refuses the ticket endpoints for them). Priority is a ranking for the support
desk, not a promised response time — HandOffly makes no SLA claim. The support phone number is configuration
(`SUPPORT_PHONE`), is sent only to plans with phone support, and opens as a `tel:` link.

**Prices.** The list prices (₹ per billing period) live in one place, the `SubscriptionPlan` enum, and are served
by `GET /api/v1/public/plans`. The public pricing page and the support portal both read them from there — the
portal shows the amount beside each plan, and the charge for the new plan when staff change a customer's plan —
so they cannot disagree. To change a price, edit it in `SubscriptionPlan`.

The customer app *hides* what a plan doesn't include and the backend refuses it regardless.
Monthly customers still see the general contact address in the public site's footer. There are no
payments yet, so **support staff change a plan by hand** from the support portal (an explicit,
confirmed action, recorded in an audit trail). That a plan was changed does not prove a payment; a
future payment integration can change the plan automatically instead.

### Customers and support staff are separate identities

| | Customer | Support staff |
|---|---|---|
| Stored in | `app_user` | `support_staff` |
| ID | Account ID `CUS-01` | Staff ID `STAFF-01` |
| Created by | public registration | controlled provisioning only (below) |
| Signs in at | `POST /api/v1/auth/login` | `POST /api/v1/support/auth/login` |
| Uses | the customer app, `/api/v1/**` | the support portal, `/api/v1/support/**` |
| Roles | none | `ADMIN`, `MANAGER`, `TICKET_AGENT` |

They share the backend and the MySQL database, but not credentials: a customer's token is refused by
the support API, a staff token is refused by the customer API, and a customer's email and password
do not sign in to the support portal (or the other way round). A customer can never become staff —
there is no role on a customer account to turn on.

### Support portal

```bash
cd support-portal
npm install
npm run dev        # http://localhost:5175 — proxies /api to http://localhost:8080
```

**The first administrator (bootstrap).** There is no sign-up and no built-in password. When the backend starts
with an email and password in its configuration, it creates that staff member if none with that email exists
yet — it never overwrites an existing account's password. Use this once, for the first **ADMIN**; the admin then
creates everyone else in the portal (below). Add to your `.env.local` (or the environment), start the backend
once, then **remove the password line**:

```properties
SUPPORT_STAFF_NAME=Asha Admin
SUPPORT_STAFF_EMAIL=asha@yourcompany.com
SUPPORT_STAFF_PASSWORD=a-long-passphrase-of-12-or-more-characters
SUPPORT_STAFF_ROLE=ADMIN
```

`SUPPORT_STAFF_ROLE` must be `ADMIN`, `MANAGER` or `TICKET_AGENT` (the old `SUPPORT` role no longer exists). The
backend log says `Support staff STAFF-01 (ADMIN) was created for …` (never the password). Passwords are stored
only as BCrypt hashes. If you ever lose every admin, promote one account directly (there is deliberately no
screen for it):

```sql
UPDATE support_staff SET role = 'ADMIN' WHERE staff_code = 'STAFF-01';
```

### Roles and permissions

Support staff have exactly one of three roles (customers have none). Each role is a fixed set of permissions,
defined in one place (`SupportRole`); every support endpoint asks for the permission it needs, so the backend
enforces this on every request and the portal merely shows what the signed-in member may use.

| | ADMIN | MANAGER | TICKET_AGENT |
|---|:-:|:-:|:-:|
| Dashboard | ✓ | ✓ | ✓ (ticket metrics only) |
| Tickets: list, search, open, reply, change status | ✓ | ✓ | ✓ |
| Customers: search, profile | ✓ | ✓ | – |
| Change a customer's plan or handoff prefix | ✓ | ✓ | – |
| Staff: list, create, deactivate, reactivate, change role | ✓ | – | – |
| Full audit trail | ✓ | – | – |

A ticket agent sees only what a ticket needs: the customer's Account ID, name, email, phone, plan and the
handoff reference typed on the ticket — no customer records, no handoffs, no account administration, and no
customer metrics on the dashboard. A manager also sees a customer's own change history on that customer's
profile. Staff can never touch a customer's password or login, impersonate a customer, switch individual
features on or off, or see or edit any handoff, return or attachment of a customer.

### Staff management (admins only)

Administrators get a **Staff** page in the portal (other roles never see it, and the API refuses them):

- **List and search** the team by Staff ID, name, email, role and active/inactive — filtered in the database; no
  password hash or credential is ever shown.
- **Create staff** as `MANAGER` or `TICKET_AGENT` with a name, email, initial password (12+ characters, hashed
  at once, never returned or logged) — admins are never created from here.
- **Change role** (e.g. Manager → Ticket agent): you choose the new role and confirm; it applies immediately,
  even to someone already signed in.
- **Deactivate / reactivate**: a deactivated member is signed out at once and cannot sign in; records are never
  deleted. Administrator accounts cannot be changed from this page (so an admin cannot lock the team out).

Endpoints (all `ADMIN` only): `GET /api/v1/support/staff?q=&role=&active=`, `POST /support/staff`,
`PUT /support/staff/{staffCode}/role`, `PUT /support/staff/{staffCode}/active`, `GET /support/audit`.

Support sessions last 8 hours by default (`STAFF_JWT_EXPIRATION_MINUTES`). Every support request is checked
against `support_staff` in the database — active, and the role read from there, not from the token.

Every change support staff make is written to `support_audit_event`: **plan** and **handoff-prefix** changes
(customer's Account ID) and **staff created / deactivated / reactivated / role changed** (Staff ID of the member
concerned) — with the previous and new value, the Staff ID of the actor, the time and an optional reason. The
records are append-only: the application offers no way to edit or delete them, and the portal only displays them.

| Setting | Meaning |
|---|---|
| `SUPPORT_STAFF_NAME` / `_EMAIL` / `_PASSWORD` / `_ROLE` | Bootstraps a staff member (the first admin) at startup; blank = nothing is created |
| `STAFF_JWT_EXPIRATION_MINUTES` | Length of a support session (default 480) |
| `SUPPORT_MAILBOX` | Where new-ticket and customer-reply notices are emailed; also the public contact address |
| `SUPPORT_PHONE` | The number shown to `YEARLY` customers (blank = no call option) |
| `CORS_ALLOWED_ORIGINS` | Must include the portal's origin (`http://localhost:5175` in dev) when the portal calls the API directly |
| `VITE_API_BASE_URL` (portal, `support-portal/.env.example`) | Backend address: the dev proxy target, or the API URL baked into a production build |

For production: set `VITE_API_BASE_URL`, run `npm run build`, and serve the static `support-portal/dist/`
from any web host.

### Upgrading an existing database

Migrations `V10` (account IDs, plans, per-customer numbering), `V11` (tickets), `V12` (support
staff as their own identity, the audit trail) and `V13` (staff roles) are non-destructive for customers: existing handoffs
keep their references (`HO-14` stays `HO-14`), recipient links, PDFs, audit events and attachments
keep working, and every existing account gets an Account ID, the `MONTHLY` plan and the `HO` prefix.
`V12` also returns any customer that an earlier version had given a staff role to being an ordinary
customer (staff are never customers), and keeps existing ticket replies as they were.

`V13` replaces the old generic `SUPPORT` staff role: **existing `SUPPORT` staff become `MANAGER`** (they could already
change plans and prefixes and work tickets — exactly what a manager may do, so nobody loses a capability and nobody
gains staff management); `ADMIN` is kept; Staff IDs, emails, passwords, active/inactive status and audit links are
untouched. Because staff management is admin-only, **make sure at least one `ADMIN` exists** after upgrading — if the
only staff account was a `SUPPORT` one it is now a manager, so promote it with the SQL above or bootstrap a new admin.

`V14` shortens the public IDs by dropping their padding zeros: `CUS-000009` becomes `CUS-09`, `TKT-000001` becomes
`TKT-01`, `STAFF-000001` becomes `STAFF-01`. Nothing is renumbered — each ID keeps its number, the counters carry on —
so IDs stay unique and still point at the same account, ticket or staff member. Anything that saved an old-style ID
(a note, a bookmark, an email already sent) shows the old form; search with the new one.

Two things to expect on the first start of this version: **everyone signs in again once** (tokens are
now bound to customers or staff, so sessions from before the upgrade stop working), and **a support
admin must exist** — bootstrap one as described above if none does. As with any schema change, take a backup first:

```bash
mysqldump -u handoffly -p handoffly > handoffly-backup.sql
```

## Testing

```bash
cd backend
mvn test
```

Covers the state machine, the full HTTP lifecycle (create → submit → accept →
partial returns → full return → close), authorization, over-return guards, expired/
invalid links, and document comparison — plus account IDs, per-customer numbering
(including concurrent creation), plan entitlements, the staff-only support API, the ticket
system, customer isolation, and an upgrade of a legacy-shaped database — all on H2, no
external services required.

```bash
cd frontend && npm run build          # customer app: type-check + build
cd support-portal && npm run build    # support portal: type-check + build
```

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
| Tickets (customer; plans with tickets only) | `GET/POST /api/v1/tickets`, `GET /tickets/{ticketId}`, `POST /tickets/{ticketId}/messages` · `/attachments`, `GET .../attachments/{aid}/content` |
| Support sign-in | `POST /api/v1/support/auth/login` (staff only — not the customer login), `GET /support/auth/me` |
| Support (staff token only) | `GET /api/v1/support/dashboard` · `/customers` · `/customers/{accountId}` · `/tickets` · `/tickets/{ticketId}`, `PUT /customers/{accountId}/plan` · `/prefix`, `PUT /tickets/{ticketId}/status`, `POST /tickets/{ticketId}/messages`, `GET /tickets/{ticketId}/attachments/{aid}/content` |
| Public | `GET /api/v1/public/contact` (the general contact address), `GET /api/v1/public/plans` (plans and their list prices) |

## Security notes

- Passwords are BCrypt-hashed; API auth is stateless JWT.
- Customers and support staff are separate identities with separate logins and tokens (each token is
  bound to its own audience). The support API has a security chain of its own: it accepts only a staff
  token, and every call re-checks in `support_staff` that the member still exists and is active, taking
  the role from there. Customer APIs accept only customer tokens. Customers can only reach their own
  tickets (someone else's is simply "not found").
- Staff passwords are BCrypt hashes (12+ characters at provisioning); staff accounts are created only by
  controlled provisioning, never by registration; plan and prefix changes are audited, append-only.
- Recipient links are opaque 256-bit tokens, **stored hashed**, expiring, and scoped to
  a single handoff.
- Uploads are type- and size-validated; blobs live in file/object storage, never in the DB.
- No secrets in source — all configuration is environment-driven.
- A typed name is a **"typed acknowledgement"**, never described as a legally binding
  e-signature.

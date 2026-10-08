# HandOffly

**Universal Proof-of-Handoff & Return Tracking.**

HandOffly records exactly what was handed over, who received it, when, and the
recipient's acknowledgement — then keeps the **same** record open until the items are
returned. Returns are entered against that same handoff, so the system always knows
what is still outstanding.

```
Give → Acknowledge → Active With Recipient → Partial Return(s) → Full Return → Closed
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

`auth · user · handoff · returns · recipient · attachment · audit · notification · documentcheck · support · payment · job`

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

**SMTP credentials are backend-only.** `.env.local` is read by the Spring Boot backend alone (through
`spring.config.import` in `application.yml`, relative to where the backend starts — the repo root or `backend/`). The
React/Vite apps never read it: Vite loads `.env*` files only from `frontend/` and `support-portal/`, and exposes only names
starting with `VITE_` (the sole one in use is `VITE_API_BASE_URL`, an address, not a secret). Never put SMTP, staff or any
other secret in a `VITE_`-prefixed variable or in a file under `frontend/` or `support-portal/`, and no API endpoint returns
mail settings. Everything in `.env.local` (SMTP, `SUPPORT_STAFF_*`, `SUPPORT_PHONE`, `PAYMENT_DEMO_ENABLED`) is backend
configuration. Docker Compose does **not** read `.env.local`; it reads `.env` (copied from `.env.example`). The backend
tests always use their own capturing sender, so running them never sends real email, whatever `.env.local` says.
`MAIL_PROVIDER=logging` writes each email (including reset and review links) to the backend log and sends nothing;
`MAIL_PROVIDER=smtp` sends through the configured server.

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

On a handoff's detail page **Download PDF** and **Download Excel** are available in every status, a draft included; **Share** and
**Email PDF** are for a handoff that has been sent. All produce the record on demand from live server data (nothing is stored). The Excel
file is the same single page as the PDF — heading and reference, parties, items, summary, return summary, attachments — built from the same
values; it is one handoff's record, not a report, and its text is never evaluated as a formula. It
lists the handoff, parties, acknowledgement, items, full return history, final summary,
lifecycle events and attachments (names only). A closed handoff is labelled **FINAL RECORD**;
any other status is labelled **INTERIM RECORD — NOT FINAL** with its current status. All times
in the PDF are UTC. **Share** uses the device's native share sheet where the browser supports
sharing files, and otherwise downloads the PDF.

## Accounts, plans and support

**Account ID.** Every customer has a permanent Account ID such as `CUS-42`, shown on their
dashboard; support finds customers by it. The internal numeric id is never shown.

**Handoff references are numbered per customer, under a prefix that is the customer's own.** A new customer is given a prefix when they
register, made from their organization (or, without one, their name): Siam Traders gets `ST`, Rahul Kumar `RK`, ABC Solutions `AS`, a single name such
as Flipkart `FL`. No two customers have the same prefix (compared without regard to case): when the first choice is taken the next one of a fixed order is
used — Siam Technologies, after Siam Traders, gets `SI` — and only when every two-letter prefix is in use does a new customer get three letters. The
customer is never asked for one, and it is shown on their Account page. Support can still set another with *Change prefix*, and it is refused if another
customer has it. Each customer's handoffs count up from 1 under that prefix — `ST-1, ST-2, …`, `RK-1, RK-2, …`. Accounts that existed before this rule keep
the prefix they had (all of them `HO`) and the references they were given; nothing was rewritten. The internal database ids stay globally unique and are what
the API addresses a handoff by. Numbers never restart: changing a prefix only affects *new* handoffs and
carries on from the customer's current number, and numbers are issued under a row lock so concurrent
creation can never repeat one. A prefix is 2–5 capital letters. The prefix is chosen (and a support change checked) under the lock of the account counter, so two customers registering at the same moment can never be given the same one.

**Plans.** A new account has **no plan** until one is paid for (below) or support staff activate it; it is never
silently put on `MONTHLY`. What a plan includes is derived from the plan alone — nobody edits
entitlements separately, so changing the plan changes them automatically:

| Plan | Contact Support in the app | Send a message | Support tickets (create, view, reply) | Phone support | Support priority |
|---|---|---|---|---|---|
| `MONTHLY` | – | – | – | – | Normal |
| `QUARTERLY` | ✓ | ✓ | – | – | Normal |
| `HALF_YEARLY` | ✓ | ✓ | ✓ | – | Priority |
| `YEARLY` | ✓ | ✓ | ✓ | ✓ | Highest |

The core product — handoffs, returns, PDFs — is identical on every plan; plans differ in support, and in **HandoffCheck**
(comparing two files, with its PDF/CSV export), which is included on `HALF_YEARLY` and `YEARLY` only. It is a product feature
read from the plan alone — nothing is stored per customer — and is separate from the support entitlements above. On `MONTHLY`
and `QUARTERLY` the menu still shows **HandoffCheck 🔒**, dimmed; opening it says "HandoffCheck is available on Half-Yearly and
Yearly plans." with **See the plans**, which opens the upgrade options right there (below) rather than the public pricing page, and
the API refuses its endpoints (compare, read a file, export, compare two files) with `403` and code `plan_required`. When support changes a plan, access follows at once, with no sign-in again: the menu
updates on the next check of the account, and the HandoffCheck page checks it when it opens and every 30 seconds after.
Importing items into a new handoff and importing a returns file also read files with HandoffCheck's reader, but they belong to
*New handoff* and *Returns* and work on every plan.

**AI Assist (optional, HandoffCheck only).** On the review step of a CSV or Excel file, **AI Assist** asks Google Gemini which columns hold
the item and the quantity — useful when the headings are unusual and the ordinary reading picks the wrong column. Only when the customer
clicks it; only a few sanitized sample rows are sent (first 8 rows, 12 columns, cells cut to 40 characters, emails and phone numbers hidden,
no file name or account details). It returns a *suggestion* the customer accepts or rejects; accepting re-reads the file with the ordinary
reader using those columns, so every rule about the rows and the comparison is the existing code. If AI is off, unreachable, over quota or
answers badly, a short note says so and HandoffCheck works exactly as before. It follows the HandoffCheck plans (Half-Yearly and Yearly)
and is limited per customer (`RATE_LIMIT_AI_ASSIST_PER_USER`, default 30 an hour, shared by every AI feature). Set `GEMINI_API_KEY` in `.env.local` (never in the
browser app or Git; blank = off); `GEMINI_MODEL` defaults to `gemini-3.1-flash-lite`, asked without "thinking" so it answers in a few seconds.
An attempt is given up after `GEMINI_TIMEOUT_SECONDS` (30; Google's free endpoint answers in 2 to 25 seconds, depending on its load) and a timeout or an overload is tried once more; after a slow or unusable answer the panel
offers **Try again**.

**AI Assist for item names (optional, HandoffCheck only).** Two files rarely spell every item the same way: "Exam Pad" and "Exam Ped", "10th Science
Book" and "10th Senence Book", "History Book" and "History Books" are one item, but the comparison (which only ignores case and punctuation) would show
each as a Missing and an Extra. On the review step of two files, **AI Assist: match item names** (the first card on that step; the result screen offers it too when it shows Missing and Extra rows) finds the
names of File A and File B that are spelled almost alike — by a fixed rule, not by the AI — and asks Gemini, in one request with the names only (never
quantities, files or account details), whether each such pair is the same item or two different words. Each suggestion shows the name in one file, the
name in the other, how sure the AI is and why; the likely ones are ticked, a less sure one is labelled *Possible match* and left unticked. Nothing changes
until **Accept**: the rows stay exactly as they were read (and stay editable); the accepted names are only *compared as* one item, shown under the row
("Compared as “Exam Pad”"), and **Undo** or **Reject** leaves everything as it was. The result then has one row per item with both quantities side by
side — "Exam Pad · 6 · 2 · -4 · Mismatch", with how File B spelled it under the name, in the PDF/CSV too — and Missing / Extra stay for items that are
really in one file only. The AI never decides Match, Mismatch, Missing or Extra: the existing comparison does, with the accepted names. The AI cannot
over-match: only pairs that pass a fixed check (`ItemNameVariation`) are ever put to it — a typing mistake in one word (the same first letter, at most
one or two letters), a plural, spacing or capitals; never a different word ("Science Book"/"History Book", "Chair"/"Table", "Laptop"/"Laptop Bag"),
never a different number or size ("Chair 1"/"Chair 2", "10th"/"9th") — and it can only confirm or refuse them, never bring a pair of its own. Same plans, same limit, same key
and same "AI unavailable" note as the column suggestion; works the same for CSV, Excel and PDF, since it works on the reviewed rows.

**AI Report Assistant (Quotation List and Summary Report; Half-Yearly and Yearly).** The **Ask AI Reports** button, on both views of Reports, opens a box to ask anything about
your handoffs in your own words ("How many total overdue items are there?", "Which handoff has the most missing items?", "Which recipient has the most
active handoffs?"); the ready-made questions below the box are only examples. The AI turns the question into a small structured request — what to
measure (handoffs, items given / returned / missing / still out), what to do with it (a total, a list, the one with the most), over which handoffs
(all, overdue, open, closed, active, with missing items, ...) and grouped how (per handoff or per recipient) — choosing each part from a fixed
list; it is shown the question text and nothing else, no report data. The backend checks the request against those lists and works the answer out
from the report's own rows — **all of your handoffs**, whatever the page behind the box is showing, unless the question names a period
("missing items between 1 Oct and 10 Oct", "this month", "since March"), which is matched against when handoffs were created, like the report's own period — then puts the figures into a
fixed sentence and shows the handoffs it came from, so the AI can neither invent nor change a number. "Overdue items" are the items not yet returned
on overdue handoffs (the answer also says how many were handed over on them). A question about data the report does not have ("What was my
profit?") gets "I can't answer that from the available report data." It is read-only. The ready-made questions use no AI, so they work even when
it is unavailable; if the AI is unavailable the report itself is unaffected.

**PDFs in HandoffCheck.** HandOffly's own Proof-of-Handoff PDF is read by its item table only — each item and the quantity under *Given*; the
header, parties, dates, summary, footer and page numbers are never items. Any other PDF is read line by line, and a line that is a date, a page
marker, a footer or a labelled field ("Reference: AK-1") is never an item. If a PDF's items cannot be read for sure there are no rows, and
the rows are entered by hand in the review step. AI Assist is for CSV and Excel only (a PDF has no columns to choose).
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
Monthly customers still see the general contact address in the public site's footer. Support staff can also change a
plan by hand from the support portal (an explicit, confirmed action, recorded in an audit trail).

**Choosing a plan = paying for it.** On the pricing page, *Choose <plan>* leads to a checkout page; after paying the
customer signs up (or signs in) and the plan is applied to that account automatically, so they can start straight
away. Today the only payment provider is a **demo** one for testing: the checkout page asks for a card, but only the listed
**test cards** work (`4242 4242 4242 4242` pays, `4000 0000 0000 0002` is declined; any future expiry, any 3–4 digit code),
no money moves, and any other number — a real card — is refused (by the page before it sends anything, and again by the
backend, which never stores, returns or logs card details). It is **off by
default** and enabled with `PAYMENT_DEMO_ENABLED=true` (development only — with it on, anyone can get any plan for free).
The backend decides everything: paying records a payment and returns a one-time token (only its hash is stored, it
expires after `PAYMENT_REDEEM_TTL_HOURS`, default 24); the signed-in customer presents the token to
`POST /api/v1/payments/redeem`, which applies the plan the payment bought, once — registering or updating a profile
never sets a plan. A real provider replaces the demo card form; everything after payment stays the same.

**Upgrading by paying the difference.** A customer on an *active* plan can move up to a dearer one by paying only what is left:
the new plan's list price minus the list price of the plan they are on, which counts in full as already paid (Quarterly ₹549 →
Half-yearly ₹999 costs **₹450**; Monthly ₹199 → Yearly ₹1,999 costs ₹1,800). The HandoffCheck page offers it: *See the plans*
opens, without leaving the page, the plans that cost more than theirs **and include HandoffCheck** (so Half-yearly and Yearly, from
Monthly or Quarterly), each with the amount to pay and the demo card form; paying switches the plan at once and unlocks the tool.
The new plan starts today and runs its own duration; the old plan is replaced, not added to, and the unused part of it is not
refunded or carried over. The backend alone works the amount out (`SubscriptionPlan.upgradeAmountFrom`), under a lock on the
customer's row — the client only says which plan and what it was shown, and a different price is refused with `409` and charges
nothing, as is a plan that is not dearer, a plan that has ended, an account with no plan, or a second click. It is one step with no
token (`GET /api/v1/payments/upgrades`, `POST /api/v1/payments/upgrade`), applied through the same code as any paid plan, so the
payment records the difference actually charged and the plan it replaced and the subscription history gets a payment row.
Plans bought outright (the pricing page, an account with no plan or an ended plan) are still charged the full list price.

**An account with no plan.** Creating an account from the Login page buys nothing, so that account has **no plan, no start and no end
date** and no active subscription. The customer can sign in and see their Dashboard and account, and is told "No active plan is
associated with this account. Please contact our support team to activate your account." — a dialog with **Contact Support** and
**Create Ticket** on the Dashboard and wherever a paid action is tried. It cannot start a handoff or draft, duplicate, import into a
new handoff, send a draft, use HandoffCheck or open Reports: the API refuses all of them with `403` and code `no_active_subscription` (a plan
that *ran out* is still `subscription_expired` with its own message). Only in this state are Contact Support and tickets available, to
ask for the activation (no message form, no phone number, normal priority); they end the moment a plan exists, when that plan's
ordinary rules apply (`MONTHLY` none, `QUARTERLY` message only, and so on). A plan arrives by paying for it from the pricing page —
that order is unchanged — or when support activates it in the portal (*Activate plan*).

**How long a plan lasts.** A plan put on an account by a payment or by support starts when it is put there and ends by itself:
`MONTHLY` **30 days**, `QUARTERLY` **90 days**, `HALF_YEARLY` **6 calendar months**, `YEARLY` **365 days** (UTC).
`SubscriptionPlan.validUntil` is the one place that is worked out; nobody types an end date for a normal activation, and renewing a
plan that is still active carries on from where it runs out. Support can still name a last day instead (the portal's optional *Last
day of the plan*). Accounts that existed before have no end date and are unchanged.

**Subscription start, end and status.** A plan can have a start date and a last paid day. The customer's **Account →
Subscription** shows the plan, *Active* or *Expired*, and the dates; the status is worked out from the end date, never
stored. Accounts that existed before this feature have **no end date** (the real dates were never recorded and are not
invented), so they stay active exactly as before. **An active subscription is required to start a new handoff, to send a draft,
to use HandoffCheck and to open Reports.** When a plan has ended the customer cannot create a handoff or draft (*New handoff*, *Duplicate*,
import into a new handoff), cannot turn an existing draft into an outgoing handoff (*Submit & send link*), and cannot use
HandoffCheck at all — comparing files, exporting a comparison, and the *Import from File* options for items and returns,
whatever the plan (an ended subscription is answered as ended, before the plan is considered) — and cannot open Reports. Each
of those shows "Your subscription has ended. Please subscribe to any of our plans to continue without any interruption." with
a single **OK** that returns to the Dashboard, and the API refuses with `403` and code `subscription_expired` before anything
is saved, sent or read. The plan's support extras pause too. Nothing else is locked: handoffs and drafts they already have stay
viewable, and returns (recorded by hand), closing, recipient links, PDFs and attachments all keep working; the plan stays on
record. Renewing (by support or by payment) brings it all back with no manual step, no sign-out and no refresh. The **New
handoff** and **HandoffCheck** pages keep themselves honest while they are open: each asks the account for its current
subscription when it opens, every 30 seconds while the tab is visible, and again at once when the tab becomes visible or the
window regains focus; if the subscription has ended the page is disabled (what was typed or uploaded is kept) and the dialog
appears, and if it has been renewed the dialog goes away and the page is usable again. That is only so the screen reacts
promptly — the backend independently judges every create, every send and every HandoffCheck request from the clock at that
moment, so a stale or altered page cannot get one through. Every plan
change, by staff or by payment, is appended to a read-only history that support staff see on the customer's profile.
Administrators and managers change plans and end dates (an explicit, confirmed, audited action); ticket agents cannot.
Nothing changes a plan automatically when it ends.

### Account, password and sign-in

**Account** (top bar) shows the Account ID and handoff prefix (read-only), lets the customer edit their name, organization
and phone (not the email, which is how they sign in), shows the subscription, and has **Change password** (needs the
current password) and **Jobs** (below). Changing or resetting a password signs the account out everywhere else; the device
that changed it carries on. **Forgot password?** on the sign-in page emails a one-time link that expires
(`PASSWORD_RESET_TTL_MINUTES`, default 30) and works once; the page answers the same way whether or not the email has an
account. With `MAIL_PROVIDER=logging` the link is written to the backend log instead of being sent.

### Reusing handoffs and files

- **Duplicate** (a handoff's detail page) opens *New handoff* prefilled with that handoff's title, parties, items and
  notes — never its status, reference, dates, acknowledgement, returns, attachments or links. Nothing exists until the
  customer saves, and the copy is always a new draft.
- **Import items** (New handoff) reads a CSV or Excel file into the item table for review: columns are recognised by their
  headers (Item / Item Description / Name / Product / Particulars… and Qty / Quantity / Count / Nos…, ignoring case,
  punctuation and a unit in brackets such as "Quantity (pcs)", in any column order), duplicates are flagged, skipped rows
  (no usable item, or a quantity that is not a number above zero) are counted, and nothing is submitted. There is no manual
  column-mapping step: a file whose headers are not recognised falls back to "first text cell is the item, the first number
  after it is the quantity", so a headerless file with the quantity before the item, or with another number column between
  them, needs its header row fixed (the review table shows exactly what was read). Maximum rows and size follow the
  HandoffCheck limits.
- **Export** (HandoffCheck) downloads a finished comparison as **PDF** or **CSV**. The export re-runs the same comparison, so
  the file matches what was shown; nothing is stored, and standalone comparisons stay independent of handoffs.

### Reports

**Reports** (a top-menu dropdown) is a read-only view of how the handoffs created in a period are doing — the deeper, historical companion to the
Dashboard, which stays the quick view of what needs attention now. It has two views, each with the same period controls: the **Quotation List**
(`/reports`: the handoffs themselves, with the status filter, sorting, paging and CSV) and the **Summary Report** (`/reports/summary`: the totals, and
the Ask AI Reports assistant). Pick *Today*, *This week* (Monday to Sunday), *This month*, *Last
month*, *This quarter* or a *Custom range* of two dates (both days included). The period is matched against the date each handoff was
**created** — the one date every handoff has, drafts included — as whole calendar days in the browser's time zone, which the CSV
states; nothing is mixed between zones. The **summary** covers every handoff created in the period, whatever its status:
handoffs created, closed, open (sent and not closed yet) and overdue (open and past their return date — both as of today, not as of
the end of the period), and the items given, returned and missing. The item figures are the very ones the Dashboard and the handoff page
show (a draft has given nothing, so it is in no item total). Whatever is neither back nor missing is shown too, split by where it stayed —
**still out** (not back yet), **rejected** (the recipient refused the handoff) and **cancelled** — so the row always adds up: items given =
returned + missing + still out + rejected + cancelled. Below it, the **table** lists the handoffs (reference, title, recipient,
created, expected return, status, given, returned, missing), can be narrowed to one status or to the overdue ones, is sortable by
every column and paged; a reference or title opens the ordinary handoff page. **Export CSV** downloads every handoff that matches
the current period and status (not only the page on screen) with the period, the time zone, the filter and the whole-period summary
above the rows; text that came from a customer is made harmless in a spreadsheet. Nothing can be changed from Reports and nothing is
stored. A **Report PDF** is not offered yet. Needs an active subscription, on any plan.
API: `GET /api/v1/reports/handoffs?from=&to=&timezone=&status=&overdue=&sort=&page=&size=` and `.../handoffs/export` (see decision 34).

### Jobs

**Customer jobs** (Account → Jobs). Five automatic emails about the customer's own handoffs; each customer has their own
schedule (a six-field cron expression — second minute hour day-of-month month day-of-week — and a timezone) and they all start
**switched off**:

| Job | Emails about |
|---|---|
| Return Reminder | handoffs due back tomorrow or the day after (not today, not overdue) |
| Overdue Reminder | open handoffs past their return date and not fully returned |
| Missing Item Reminder | open handoffs with items still marked missing (never one that was force-closed) |
| Weekly Summary | what happened over the last 7 days |
| Recipient Response Reminder | handoffs sent to a recipient who has neither accepted nor declined after 24 hours (it stops by itself once they respond) |

A run sends **one** email (or none if there is nothing to say), with the job's name as a bold heading. **Run now** runs one job
without touching its schedule or whether it is on; **RUN ALL NOW** runs the customer's five jobs once (each job is its own run).
Every run adds one row to **Jobs Monitoring History** (Account → Jobs Monitoring History), kept for good: when it ran, what started
it (scheduled, Run Now or Run All Now) and a one-line result — *Successfully sent for …*, *Partial success — Successfully sent for
…; Failed for …: reason*, *Nothing to report* or *Failed — reason*. The email only ever contains the handoffs that could be
prepared for it; a handoff that could not be is named in the history, never in the email, and is tried again at the next run (the
handoffs the partial run already sent are left out of that one retry). If the email itself cannot be sent, nothing counts as sent and
the run is **Failed**. A schedule is validated when saved: it must be a real cron expression in a real timezone, must run,
and may not run more often than once an hour (`JOBS_MIN_INTERVAL_MINUTES`). The background ticker is switched off with
`JOBS_SCHEDULER_ENABLED=false`.

**Subscription-expiry reminder** (support portal → Jobs; administrators and managers only). Emails each customer whose
subscription ends within a window (default 7 days, 1–90), once per end date; renewing starts a new period. It can be run now,
paused, resumed and scheduled, starts switched off, and only sends email — it never changes a plan.

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
| `PAYMENT_DEMO_ENABLED` | `true` turns on the demo payment page (no real money; development only; default `false`) |
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

`V15` only adds the `payment` table (nothing existing is touched). The demo payment page stays off until you set
`PAYMENT_DEMO_ENABLED=true`.

`V16`–`V21` are additive too. `V16` adds the password-reset table and a per-account token version (so a password change can end
older sessions); `V17` adds `plan_started_at` / `plan_valid_until` (NULL for everyone existing — no end date, nothing changes) and
the `subscription_history` table, filled only from plan changes and payments that really happened; `V18` adds `customer_job`
(nobody has a job until they open Account → Jobs, and jobs start off); `V19` adds `support_job` and
`subscription_expiry_reminder`; `V20` only relaxes two constraints — an account may have no plan, and a payment may record that it replaced
none — and rewrites nothing, so every existing account keeps its plan and dates; `V21` adds the append-only `customer_job_run` table
(the job history) and copies each job's existing latest run into it as its first row. No customer, handoff, return, attachment or audit row is changed or removed. These were applied
to a real MySQL 8.0 database loaded with legacy-shaped data (V15 schema) and checked afterwards. **Restart the backend to apply
them**; everyone signs in again once, because customer tokens now carry the account's token version.

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
system, customer isolation, and an upgrade of a legacy-shaped database; and the account/password flows, abuse limits,
upload checks, subscription lifecycle, duplicate, item import, comparison export, the customer jobs, the support expiry
job and the handoff report (totals, periods and time zones, filters, sorting, CSV, customer isolation, the subscription gate) — all on H2, no external services required.

```bash
cd frontend && npm run build          # customer app: type-check + build
cd support-portal && npm run build    # support portal: type-check + build
```

## Key API endpoints (v1)

| Area | Endpoint |
|------|----------|
| Auth | `POST /api/v1/auth/register`, `/login`, `/change-password`, `/forgot-password`, `/reset-password`, `GET/PUT /auth/me` |
| Customer jobs | `GET /api/v1/account/jobs`, `PUT /account/jobs/{type}/schedule` · `/enabled`, `POST /account/jobs/{type}/run` · `/account/jobs/run-all` |
| Handoffs | `GET/POST /api/v1/handoffs`, `GET/PATCH/DELETE /handoffs/{id}`, `GET /handoffs/dashboard` |
| Reports (read-only; active subscription) | `GET /api/v1/reports/handoffs` (summary + one page), `GET /reports/handoffs/export` (CSV) — `from`, `to`, `timezone`, `status`, `overdue`, `sort` |
| Lifecycle | `POST /handoffs/{id}/submit` · `/resend-link` · `/cancel` · `/dispute` · `/close` |
| Items | `PUT /handoffs/{id}/items`, `GET /handoffs/{id}/template` (what *Duplicate* prefills) |
| Returns | `POST /handoffs/{id}/returns`, `POST /handoffs/{id}/returns/{rid}/confirm` |
| Attachments | `GET/POST /handoffs/{id}/attachments`, `GET .../{aid}/content`, `DELETE` |
| Events | `GET /handoffs/{id}/events` |
| PDF / Excel | `GET /handoffs/{id}/pdf` (`application/pdf`), `GET /handoffs/{id}/excel` (`.xlsx`), `POST /handoffs/{id}/email-pdf` (`{ "to"? }`) |
| Recipient (public) | `GET /api/v1/r/{token}`, `POST /r/{token}/accept` · `/reject` · `/returns` |
| HandoffCheck (Half-Yearly and Yearly) | `POST /api/v1/handoff-check`, `POST /handoff-check/extract` · `/compare-files` · `/export?format=PDF\|CSV` |
| File imports (every plan) | `POST /api/v1/handoff-check/import-items` (CSV/XLSX → rows to review, for New handoff), `POST /handoff-check/return-import` (a returns file, for Returns) |
| Tickets (customer; plans with tickets only) | `GET/POST /api/v1/tickets`, `GET /tickets/{ticketId}`, `POST /tickets/{ticketId}/messages` · `/attachments`, `GET .../attachments/{aid}/content` |
| Support sign-in | `POST /api/v1/support/auth/login` (staff only — not the customer login), `GET /support/auth/me` |
| Support (staff token only) | `GET /api/v1/support/dashboard` · `/customers` · `/customers/{accountId}` · `/tickets` · `/tickets/{ticketId}`, `PUT /customers/{accountId}/plan` · `/prefix`, `PUT /tickets/{ticketId}/status`, `POST /tickets/{ticketId}/messages`, `GET /tickets/{ticketId}/attachments/{aid}/content` |
| Support jobs (administrators and managers) | `GET /api/v1/support/jobs/subscription-expiry`, `PUT .../schedule` · `.../enabled`, `POST .../run` |
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
  a single handoff. Password-reset links are the same kind of token: hashed, one-time, expiring.
- Customer sessions are checked against the account on every request, and a password change or reset ends all older
  sessions.
- Public and sensitive endpoints (sign-in, register, forgot/reset password, recipient links, payments) and manual job runs are
  rate limited (`429` with `Retry-After`). The limiter is **in memory, per backend instance**: a restart clears it and several
  instances each keep their own counts; behind a reverse proxy set `FORWARD_HEADERS_STRATEGY=native` so real client addresses
  are counted. Failed sign-ins are limited per account and address, so one caller cannot lock another out.
- Responses carry security headers (CSP, no-referrer, nosniff, frame deny, HSTS over HTTPS).
- Uploads are type-, size- and content-validated (the file's first bytes must match its declared type); blobs live in
  file/object storage, never in the DB.
- No secrets in source — all configuration is environment-driven.
- A typed name is a **"typed acknowledgement"**, never described as a legally binding
  e-signature.

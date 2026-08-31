# Spending Analyzer

Upload a bank or credit card statement and get back a categorized, analysed picture of your
spending: where the money went, what's recurring, what next month is likely to look like, and
where you could cut back.

It runs locally on your machine. Your statements go into a SQLite file in the project folder —
there's no hosted database and no account to sign up for.

---

## What it does

| | |
|---|---|
| **Import** | Reads CSV and Excel statements, working out the date, description, amount, and direction columns for you |
| **Skip duplicates** | Re-importing an overlapping statement adds only what's new |
| **Categorize** | Sorts transactions into categories using Claude, and remembers each merchant so it doesn't ask twice |
| **Multiple accounts** | Keep a current account and a credit card separate, or view everything together |
| **Recurring charges** | Finds subscriptions and regular bills, with an annual cost and next expected date |
| **Predictions** | Forecasts next month per category and suggests where to reduce spending |

---

## The flow: what happens when you upload a file

This is the whole pipeline, start to finish.

**1. Parse the file.** The uploaded CSV or Excel file is read and the columns are worked out by
name — `Date` / `Transaction Date` / `Posted Date` all count as the date column, and so on. It
handles the common awkward cases: US and ISO date formats, currency symbols and thousands
separators, accounting-style negatives like `(99.99)`, and statements that use separate *Debit*
and *Credit* columns instead of one signed *Amount*. Rows it can't read are skipped rather than
failing the whole import.

**2. Skip anything already imported.** Each row gets a fingerprint of account + date +
description + amount + direction. The importer compares *counts* rather than rejecting exact
matches — so re-uploading last month's statement adds nothing, but two identical coffees bought
on the same day are both kept, because the file genuinely contains two and the database has none.

**3. Categorize, cheaply.** Every new transaction needs a category. Rather than asking Claude
about all of them:

- Merchants already in **merchant memory** are answered from the database, with no AI call.
- Whatever is left is grouped by merchant, and Claude is asked **once per merchant** — not once
  per transaction. Fifty coffees at the same shop is one question.
- Each answer is written back to memory, so the next import is cheaper still.

**4. Learn from your corrections.** If you change a transaction's category by hand, that merchant
is saved to memory as *your* correction. Your corrections outrank Claude's guesses and are never
silently overwritten, so a fix you make once stays fixed.

**5. Crunch the numbers.** Monthly totals per category are computed in SQL, plus two statistical
baselines: a straight-line trend and a three-month moving average. Anything marked as income or
a transfer is excluded, so moving money between your own accounts doesn't look like spending.

**6. Predict and advise.** Those computed numbers are handed to Claude, which turns them into a
next-month forecast per category and specific suggestions for cutting back. The maths is done in
code and the model does the judgement — it isn't asked to do arithmetic.

**7. Spot what's recurring.** Separately, charges are grouped by merchant and checked for a steady
rhythm *and* a steady amount. Both are required, which is what keeps your weekly grocery run out
of a list that's meant to show subscriptions.

---

## Running it

There are two ways: **one container**, or **two dev servers**. Use the container to just run the
app; use the dev servers when changing it.

### Your API key (optional, either way)

```bash
cd server-springboot
cp .env.example .env
```

Open `.env` and set `ANTHROPIC_API_KEY=` to your [key](https://console.anthropic.com/).

Without a key everything still runs — import, browsing, recurring charges, and any merchant
already in memory all work offline. Only fresh AI categorization and predictions need it.

### Option A: Docker — one thing to run

**You'll need:** Docker.

```bash
APP_PASSWORD=pick-something docker compose up --build
```

Open **http://localhost:4000** and sign in with that password. That's the whole app: the React
frontend is built into the jar and served by Spring Boot, so there's one process and one port.

The password isn't optional here. The image refuses to start without one, because an image
exists to be run somewhere reachable and coming up open on a network is the mistake nobody
notices. Put `APP_PASSWORD` in `server-springboot/.env` if you'd rather not type it each time.

Your database lives in a named volume, so it survives `docker compose down` and any rebuild. The
`.env` above is picked up automatically if it exists.

To build the same single artifact without Docker:

```bash
cd server-springboot && ./mvnw -Pfrontend clean package
java -jar target/spending-analyzer.jar
```

The `frontend` profile is what compiles the client and packages it into the jar. It's off by
default so the everyday build stays fast — see `CONTEXT.md`.

### Option B: Dev servers — for working on it

**You'll need:** Java 21+ and Node 22+.

```bash
# Backend on port 4000
cd server-springboot && ./mvnw spring-boot:run

# Frontend on port 5173, in another terminal
cd client && npm install && npm run dev
```

Open **http://localhost:5173**. Vite proxies `/api` to the backend and reloads on save. On
Windows use `mvnw.cmd`; no Maven install is needed, the wrapper fetches it. The database and its
tables are created automatically on first run.

No password is needed for this — a local run is open by default, and the server says so in its
startup log. Set `APP_PASSWORD` if you want to exercise the login screen.

---

## Project layout

```
client/                     React + Vite frontend
  src/components/           Upload, Dashboard, Transactions, Recurring, Manage
  src/api.ts                Every backend call lives here
  src/types.ts              Shared TypeScript types

server-springboot/          Spring Boot backend
  src/main/java/.../
    controller/             HTTP endpoints
    service/                Parsing, categorization, stats, recurring, Claude calls
    repository/             Database access
    model/ dto/             Data shapes
  src/main/resources/
    db/migration/           Versioned schema migrations (V1–V6)
  src/test/                 128 tests
  pom.xml                   The `frontend` profile builds the client into the jar

Dockerfile                  Multi-stage build producing the single deployable image
compose.yaml                Runs that image with a volume for the database

.github/workflows/ci.yml    Client, server and Docker image, on every push and pull request
.github/workflows/codeql.yml  CodeQL security scanning
.github/dependabot.yml      Weekly dependency updates
```

---

## Tech stack

**Backend** — Java 21 (built and shipped on 25), Spring Boot 4.1, Spring Security for the
password gate, SQLite (`sqlite-jdbc`), Flyway for schema migrations,
Apache Commons CSV and Apache POI for file parsing, and the official `anthropic-java` SDK
(`claude-opus-5`, using structured JSON output so responses match a fixed schema).

**Frontend** — React, TypeScript, Vite, Tailwind CSS, and Recharts for the charts.

---

## The database

Six tables, all created automatically:

| Table | Holds |
|---|---|
| `transactions` | Every imported transaction |
| `accounts` | Your accounts; everything belongs to one, defaulting to "Default" |
| `categories` | The 16 built-in categories plus any you add |
| `merchant_categories` | Merchant memory — how each merchant was last categorized |
| `budgets` | A monthly spending target per category |
| `predictions_cache` | The most recent AI forecast |

Schema changes are **Flyway migrations** in `db/migration/`. Each file runs once, in order, and
is recorded — so upgrading never wipes your data. To change the schema, add a new `V7__*.sql`
rather than editing an existing file.

Categories carry `is_income` and `is_transfer` flags rather than the code checking for the literal
names "Income" and "Transfer". That means a category *you* create can also be kept out of spending
totals.

---

## Configuration

Everything is an environment variable, and every one has a working default — the app starts with
none of them set.

| Variable | Default | What it does |
|---|---|---|
| `APP_PASSWORD` | *(empty)* | The password guarding this instance. Empty means no authentication at all — fine on localhost, not anywhere else |
| `APP_AUTH_REQUIRED` | `false` | When true, the app refuses to start without a password. The Docker image sets this, so a container can never come up open |
| `ANTHROPIC_API_KEY` | *(empty)* | Enables AI categorization and predictions |
| `ANTHROPIC_MODEL` | `claude-opus-5` | Which model to ask |
| `SPENDING_ANALYZER_DB` | `./data.sqlite` | Path to the SQLite file. The container points this at `/data` on a volume |
| `PORT` | `4000` | Port the server listens on |
| `CORS_ALLOWED_ORIGINS` | `http://localhost:[*]`, `http://127.0.0.1:[*]` | Comma-separated origin patterns allowed to call `/api`. Loopback on any port by default, because Vite moves off 5173 when it's taken. Blank turns CORS off, which is what the container does — packaged as one artifact the frontend is same-origin and needs no exception |

`ANTHROPIC_API_KEY` and `ANTHROPIC_MODEL` can also come from `server-springboot/.env`.

---

## API

All endpoints live under `/api`.

| Method | Path | Purpose |
|---|---|---|
| `POST` | `/upload` | Import a statement (`?accountId=`, `?skipDuplicates=`) |
| `GET` | `/transactions` | List transactions (filter by category, month, account) |
| `PATCH` | `/transactions/{id}` | Change a category — also teaches merchant memory |
| `POST` | `/categorize` | Categorize anything uncategorized |
| `GET` | `/summary` | Category totals, monthly totals, per-category trends |
| `GET` | `/recurring` | Detected recurring charges |
| `GET` | `/predictions` | Last saved forecast |
| `POST` | `/predictions/refresh` | Generate a new forecast |
| `GET` `POST` `DELETE` | `/budgets` | Monthly targets per category, with spend against them |
| `GET` | `/export/transactions.csv` | Download transactions, filters and all |
| `GET` | `/export/categories.csv` | Download spend per category |
| `GET` | `/export/monthly.csv` | Download spend per month |
| `GET` `POST` `PATCH` `DELETE` | `/accounts` | Manage accounts |
| `GET` `POST` `PATCH` `DELETE` | `/categories` | Manage categories |
| `GET` `DELETE` | `/merchants` | View or forget merchant memory |
| `DELETE` | `/reset` | Delete all transactions (keeps accounts and categories) |
| `GET` | `/auth/status` | Whether this instance has a password, and whether you're past it. The only endpoint outside the gate, so it's what a health check should poll |
| `POST` | `/auth/login` `/auth/logout` | Sign in and out |
| `GET` | `/health` | Liveness, and whether an API key is configured |

---

## How the trickier bits work

**Duplicate detection compares counts, not just matches.** Rejecting every exact match would
throw away genuine repeat purchases. Counting means only the surplus over what's already stored
gets imported, which makes re-importing an overlapping statement safe.

**Recurring detection needs a steady rhythm *and* a steady amount.** Cadence alone would flag your
supermarket, since you shop there regularly — but for a different amount each time. Requiring both
is what separates "Netflix, £15.49 every month" from "groceries, roughly fortnightly, £60–£100".
Charges on the same date are treated as one billing event, so two cards billed by the same
merchant on the same day don't confuse the rhythm.

**One password, and no user accounts.** There is exactly one secret, read from the environment.
That isn't a shortcut — this app holds one person's statements in a local SQLite file, so per-user
accounts would mean an owner column on all six tables and a scoping clause in every query, to
solve a problem it doesn't have. What it does need is a lock on the door once it's reachable from
anywhere but localhost.

Which is why the default differs by how you run it. A local `mvnw spring-boot:run` is open, and
says so loudly in its startup log. The Docker image sets `APP_AUTH_REQUIRED=true` and **refuses
to start** without a password, because an image exists to be run somewhere reachable, and coming
up open on a network is the failure nobody notices. CI asserts both halves: that the container
answers 401 to an unauthenticated caller, and that it exits when given no password.

Sessions are cookie-based, so writes carry a CSRF token. One consequence worth knowing: because
a rejected token from a not-yet-signed-in caller comes back through the authentication entry
point, it surfaces as a 401 rather than a 403 — so the frontend refreshes its token immediately
before signing in, rather than showing you "incorrect password" when the password was fine.

**Budgets measure a month, and pick the month that has data in it.** A budget is monthly, so a
date filter is reduced to the month it ends in. With no filter, the default is the newest month
on record rather than the current calendar month — statements get imported weeks after the fact,
and defaulting to "now" would show every budget untouched at zero on a fresh import. The month
being measured is always named on the card, so it can't quietly disagree with your filter.

Targets are stored per category name, which means a category rename or delete has to reach them.
A rename carries the budget across; a delete drops it rather than folding it into whichever
category the transactions moved to, since that would silently change a number you set.

**CSV exports carry the filters you're looking at, and a signed amount.** Exporting a filtered
view gives you the filtered rows — but *all* of them, not the page on screen, because a silently
truncated export is worse than none. Amounts are stored unsigned with direction in a separate
`type` column, which would make a naive spreadsheet `SUM` wrong, so a `signed_amount` column sits
alongside: negative for debits. The files start with a byte-order mark, without which Excel reads
them in the OS codepage and mangles any accented merchant name.

**Merchant memory keys on a cleaned-up merchant name.** Store numbers and order references vary
per visit (`WHOLE FOODS MARKET #123`, `AMAZON.COM*AB123`), so they're stripped before matching.
That means one entry covers every branch of a chain. The same cleanup is shared with recurring
detection, so both agree on what counts as one merchant.

A trade worth knowing: memory assumes one merchant maps to one category. For somewhere like Amazon
that spans Shopping and Subscriptions, the first answer sticks until you correct it. Correcting a
transaction updates the entry, and **Manage → Merchant memory** lets you forget any entry so it's
asked about fresh.

---

## Development

```bash
# Backend: build and run all tests
cd server-springboot && ./mvnw verify

# Frontend: lint, type-check, build
cd client && npm run lint && npm run build

# The deployable artifact, as CI builds it
docker build -t spending-analyzer .
```

**Tests (128).** Most cover pure logic and run in milliseconds: the duplicate counting rules, the
file-parsing edge cases, merchant name cleanup, and recurring detection — including the negative
cases that keep groceries and coffee *out* of the recurring list. A smoke test boots the whole
application with no API key, which is how CI runs it, and catches broken wiring or a failed
migration that a compile-only check would miss.

**CI.** Every push and pull request runs three jobs in parallel on GitHub Actions: the client, the
server, and the Docker image. All three are required to merge. The image job doesn't just build —
it starts the container and checks that both the API and the packaged frontend respond, since an
image that builds and then fails to boot would otherwise pass unnoticed.

**Security scanning.** CodeQL analyses the Java and the TypeScript on every change, and again
weekly — new queries ship over time, so a scheduled run finds problems in code nobody has touched.
Results land in the repository's Security tab. It is not a required check: a scanner's opinion is
worth reading, not worth blocking a merge on.

**Dependencies.** Dependabot checks npm, Maven, the base images, and the CI actions weekly. Small
updates are grouped into one pull request; major ones arrive separately so they get a proper look.
One thing it can't see: the Node version in the `frontend` profile is a Maven property, not a
dependency, so that line is bumped by hand.

---

## How this was built

Roughly in order:

1. **The app itself** — Spring Boot backend and React frontend: import, AI categorization,
   dashboard, and predictions.
2. **Published to GitHub**, with build output, the database file, and `.env` kept out of version
   control.
3. **CI on GitHub Actions**, plus a smoke test that boots the app — because a build that only
   compiles proves very little. It earned its keep almost immediately.
4. **Dependabot**, along with vulnerability alerts and automated security fixes.
5. **Accounts, custom categories, recurring detection, and duplicate detection** — with Flyway
   introduced first, since the previous setup could create tables but not alter them, so no schema
   change would have reached an existing database.
6. **Spring Boot 4 upgrade**, which moved Jackson 2 → 3 and needed Flyway's auto-configuration
   module added. Both problems only appeared at runtime; the code compiled cleanly throughout.
7. **Merchant memory**, so repeat imports mostly skip the AI entirely.
8. **Branch protection on `main`**, so the CI in step 3 actually enforces something.
9. **One deployable artifact** — the frontend packaged into the jar and a Docker image, with CI
   starting the container and probing it rather than trusting a successful build.

---

## Working on it

`main` is protected. You can't push to it directly — changes go through a pull request, and the
required CI jobs have to pass before it can merge. A branch also has to be up to date with `main`
before merging, which stops two separately-green changes from being combined untested.

```bash
git checkout -b my-change
# ... make changes, then:
git push -u origin my-change
gh pr create --base main
```

If you ever genuinely need to bypass this, turn protection off in
**Settings → Branches**, do what you need, and turn it back on.

---

## Next improvements

The running to-do list, roughly in the order worth tackling. Updated as things get done.

**1. Export the predictions.** Transactions, categories and months export as CSV; the AI forecast
and its recommendations do not, and a PDF of the dashboard is still worth having.

**2. Smarter merchant memory.** Allow a merchant to map to different categories based on amount or
description detail, for cases like Amazon that genuinely span several.

**3. Predictions per date range.** Forecasts currently always use an account's full history and
the cached result is not keyed by range, so the dashboard's date filter does not apply to them.

**4. Publish the image.** Push tagged builds to a registry so running it somewhere doesn't mean
building it there. Worth doing after (1).

### Done

- ~~Authentication~~ — one shared password, session cookie, CSRF; the image will not start without it
- ~~CodeQL~~ — security scanning of the Java and the TypeScript, on every change and weekly
- ~~Budgets~~ — a monthly target per category, tracked on the dashboard
- ~~CSV export~~ — transactions, category totals and monthly totals, honouring the active filters
- ~~Deployable as one thing~~ — frontend packaged into the jar, Docker image, CI builds and boots it
- ~~Date range filtering~~ — presets and a custom window across dashboard, transactions, recurring
- ~~Edit and delete transactions~~
- ~~Branch protection on `main`~~ — required CI checks, enforced for admins
- ~~Merchant memory~~ — repeat imports skip the AI
- ~~Accounts, custom categories, recurring detection, duplicate detection~~
- ~~CI on GitHub Actions~~ and ~~Dependabot~~

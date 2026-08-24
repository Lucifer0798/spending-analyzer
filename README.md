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

**You'll need:** Java 21+, Node 20+, and an [Anthropic API key](https://console.anthropic.com/).

### 1. Add your API key

```bash
cd server-springboot
cp .env.example .env
```

Then open `.env` and set `ANTHROPIC_API_KEY=` to your key.

Without a key everything still runs — import, browsing, recurring charges, and any merchant
already in memory all work offline. Only fresh AI categorization and predictions need it.

### 2. Start the backend (port 4000)

```bash
cd server-springboot && ./mvnw spring-boot:run
```

On Windows use `mvnw.cmd`. No Maven install needed — the wrapper fetches it. The database and its
tables are created automatically on first run.

### 3. Start the frontend (port 5173)

```bash
cd client && npm install && npm run dev
```

Open **http://localhost:5173**.

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
    db/migration/           Versioned schema migrations (V1–V5)
  src/test/                 49 tests

.github/workflows/ci.yml    Runs on every push and pull request
.github/dependabot.yml      Weekly dependency updates
```

---

## Tech stack

**Backend** — Java 21, Spring Boot 4.1, SQLite (`sqlite-jdbc`), Flyway for schema migrations,
Apache Commons CSV and Apache POI for file parsing, and the official `anthropic-java` SDK
(`claude-opus-5`, using structured JSON output so responses match a fixed schema).

**Frontend** — React, TypeScript, Vite, Tailwind CSS, and Recharts for the charts.

---

## The database

Five tables, all created automatically:

| Table | Holds |
|---|---|
| `transactions` | Every imported transaction |
| `accounts` | Your accounts; everything belongs to one, defaulting to "Default" |
| `categories` | The 16 built-in categories plus any you add |
| `merchant_categories` | Merchant memory — how each merchant was last categorized |
| `predictions_cache` | The most recent AI forecast |

Schema changes are **Flyway migrations** in `db/migration/`. Each file runs once, in order, and
is recorded — so upgrading never wipes your data. To change the schema, add a new `V6__*.sql`
rather than editing an existing file.

Categories carry `is_income` and `is_transfer` flags rather than the code checking for the literal
names "Income" and "Transfer". That means a category *you* create can also be kept out of spending
totals.

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
| `GET` `POST` `PATCH` `DELETE` | `/accounts` | Manage accounts |
| `GET` `POST` `PATCH` `DELETE` | `/categories` | Manage categories |
| `GET` `DELETE` | `/merchants` | View or forget merchant memory |
| `DELETE` | `/reset` | Delete all transactions (keeps accounts and categories) |
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
```

**Tests (49).** Most cover pure logic and run in milliseconds: the duplicate counting rules, the
file-parsing edge cases, merchant name cleanup, and recurring detection — including the negative
cases that keep groceries and coffee *out* of the recurring list. A smoke test boots the whole
application with no API key, which is how CI runs it, and catches broken wiring or a failed
migration that a compile-only check would miss.

**CI.** Every push and pull request runs both sides in parallel on GitHub Actions. Nothing merges
without it passing.

**Dependencies.** Dependabot checks npm, Maven, and the CI actions weekly. Small updates are
grouped into one pull request; major ones arrive separately so they get a proper look.

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

---

## Working on it

`main` is protected. You can't push to it directly — changes go through a pull request, and both
CI jobs have to pass before it can merge. A branch also has to be up to date with `main` before
merging, which stops two separately-green changes from being combined untested.

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

**1. Make it deployable as one thing.** Have Spring Boot serve the built frontend, and add a
Docker image. One artifact to run instead of two dev servers — and the prerequisite for running
this anywhere but your own machine.

**2. Authentication.** There is none, and the API accepts requests from any origin. Fine on
localhost, not fine anywhere reachable. Needed before (1) goes anywhere public.

**3. Export.** Save the dashboard or the predictions as CSV or PDF.

**4. Budgets.** Set a monthly target per category and track against it — the natural next step
once predictions exist.

**5. Smarter merchant memory.** Allow a merchant to map to different categories based on amount or
description detail, for cases like Amazon that genuinely span several.

**6. CodeQL.** Free security scanning for public repositories, roughly ten minutes to set up.

**7. Predictions per date range.** Forecasts currently always use an account's full history and
the cached result is not keyed by range, so the dashboard's date filter does not apply to them.

### Done

- ~~Date range filtering~~ — presets and a custom window across dashboard, transactions, recurring
- ~~Edit and delete transactions~~
- ~~Branch protection on `main`~~ — required CI checks, enforced for admins
- ~~Merchant memory~~ — repeat imports skip the AI
- ~~Accounts, custom categories, recurring detection, duplicate detection~~
- ~~CI on GitHub Actions~~ and ~~Dependabot~~

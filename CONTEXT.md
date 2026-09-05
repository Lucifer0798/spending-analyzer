# Working context

Notes for picking this project up cold — environment quirks, conventions, and decisions
that would otherwise get re-litigated. `README.md` explains what the app *is*; this covers
how to work on it.

**Getting up to speed:** read `README.md`, then this file. That should be enough to start.

---

## Environment (this machine)

| | |
|---|---|
| Java | 21+ installed. No Maven — use `./mvnw` (`mvnw.cmd` on Windows). CI and the image use 25; the compile target stays 21, so any JDK from 21 up builds and runs it |
| Node | 22 |
| Shell | Git Bash and PowerShell both available |
| `gh` CLI | Installed at `C:\Program Files\GitHub CLI`, **not on PATH**. Prefix commands with `export PATH="$PATH:/c/Program Files/GitHub CLI"` |
| API key | **Not configured.** AI categorization and predictions return 401; everything else works, including merchant memory |

| Docker | Desktop 4.87 / engine 29.7.2, WSL2 backend. Must be **running** — start `Docker Desktop.exe` first, the daemon does not start on demand |

### Four traps that cost time

**A running app locks the jar.** If `./mvnw clean` fails with *"The process cannot access the
file because it is being used by another process"*, a Spring Boot process is still holding
`target/spending-analyzer.jar`. It is not a build problem. `pkill -f` does **not** reliably
kill Windows Java processes — use PowerShell:

```powershell
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like '*spending-analyzer*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**A running Vite dev server breaks `-Pfrontend`.** Same trap, other half of the app. The profile
runs `npm ci`, which deletes `client/node_modules` — and Windows refuses to unlink Rolldown's
native binding while Vite holds it, so the build dies with `EPERM ... unlink
rolldown-binding.win32-x64-msvc.node`. Nothing is wrong with the build; stop the dev server:

```powershell
Get-CimInstance Win32_Process -Filter "Name = 'node.exe'" |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

Docker builds are immune — they run against a clean copy inside the container, which is a good
reason to reach for `docker build` when only checking that packaging still works.

**`oxlint-disable-next-line` means the literal next line, not the next line of code.** A
multi-line explanatory comment placed between the directive and the statement it's meant to
cover breaks it silently — the warning stays, pointing at the same line, with no error telling
you the comment did nothing. Put the directive on the line immediately above the flagged
statement; if you want a longer explanation, put that comment *above* the directive instead:

```tsx
// Longer reason can go here, across as many lines as it needs.
// oxlint-disable-next-line react/set-state-in-effect
setLoading(true);
```

**Stale `target/test-classes` can mask a fix.** Maven does not remove deleted resources on an
incremental build, so a config file you deleted can still be on the test classpath and make a
correct fix look broken. When test behaviour contradicts the source, run `clean` before
debugging further.

---

## Workflow

`main` is protected: no direct pushes, the required CI checks must pass, and a branch must be up
to date with `main` before merging. Enforced for admins, so there is no bypass without turning
protection off in **Settings → Branches**.

Required checks are **Client (React + Vite)**, **Server (Spring Boot)** and **Docker image**.
Adding a job to the workflow does not make it required — that is a separate setting under
**Settings → Branches**.

> **CodeQL is deliberately not required.** It runs on every change and weekly, and its findings
> belong in the Security tab. Blocking a merge on a scanner's opinion trades a real cost for a
> judgement call that a human should be making.

- Branch names: `feat/*`, `chore/*`, `docs/*`
- Squash merge, delete the branch
- If a PR goes `BEHIND` after something else merges: `gh pr update-branch <n>`, wait for CI, then merge
- Dependabot runs weekly. Minor/patch are grouped per ecosystem; majors arrive separately

---

## Decisions worth not re-opening

Each of these looks like an oversight until you know the reason.

**Duplicate detection compares counts, not matches.** Rejecting every exact match would throw
away genuine repeat purchases — two identical coffees on one day are real. Only the surplus
over what is already stored gets imported.

**Recurring detection requires a steady amount *and* a steady rhythm.** Cadence alone flags the
weekly supermarket run, which makes the view useless. Charges sharing a date collapse into one
billing event; without that, two cards billed by the same merchant on the same day produce a
zero-day gap that drags the median interval to zero and hides the subscription entirely.

**Merchant memory maps one merchant to one category.** Wrong for somewhere like Amazon that
spans Shopping and Subscriptions — the first answer sticks until corrected. Accepted trade; the
correction path and per-entry *forget* are the escape hatches.

**`user` memory entries outrank `ai` ones.** A model run fills gaps but never overwrites a
category fixed by hand, otherwise the same correction is needed on every import.

**Categories carry `is_income` / `is_transfer` flags** rather than the code matching the literal
names. That is what lets a user-created category be excluded from spend totals.

**`transactions.category` is TEXT, not a foreign key.** Renaming cascades inside one transaction
in `CategoryRepository.rename`. Keeping it as text avoided rewriting every DTO and the frontend
types for a single-user local app. Revisit if categories grow more structure.

**Amounts are stored unsigned; direction lives in `type`.** A negative amount would corrupt
every total that sums the column, which is why the edit endpoint rejects one.

**Date presets anchor to the most recent transaction, not today.** Statements are imported after
the fact, so "last 3 months" from today shows nothing for data ending a few months back.

**Forecasts ignore the date filter.** A projection from a narrow window would be worse, and the
prediction cache is a single row not keyed by range. Listed in the README roadmap.

**Flyway owns the schema.** Never edit an applied migration — add `V6__*.sql`. SQLite cannot
alter a CHECK constraint in place, so widening one means the copy-and-swap rebuild used in `V5`.

**The `frontend` profile is off by default.** With it on, every `./mvnw verify` would download
Node and rebuild the client — around a minute added to a loop that otherwise finishes in seconds,
to produce something the tests never touch. Release builds and the Docker image pass `-Pfrontend`
explicitly. The cost is that a plain `java -jar` gives a working API and a 404 at `/`, which is
the right trade for how often each is run.

**Maven builds the frontend even inside Docker.** The obvious Dockerfile has a Node stage and a
Maven stage, which caches better — but then "how the client is built" is defined twice and the two
drift. The image runs `./mvnw -Pfrontend` instead, so `docker build` and a local release build
produce the same jar by the same path.

**The image is published from the job that tested it, not a separate one.** The `image` job
builds, boots and probes the container, and only then — on `main` only — tags and pushes it to
GHCR as `latest` and `sha-<commit>`. A separate publish job would rebuild, and would be free to
push something the smoke test never saw. Pull requests build and test but never push.

> GHCR rejects uppercase in a repository path and this owner has some, hence the `tr` in that
> step. The package inherited the repository's public visibility on first push — an anonymous
> `docker pull` worked immediately, with no manual step in Packages settings.

**Authentication is one shared password, and its default depends on how you run it.** No user
model: one person's data in one SQLite file, so accounts would mean an owner column on six
tables and a scoping clause in every query for a problem the app does not have. `AuthSettings`
holds the secret; blank means the gate is open and logs a warning, which is right for a local
run. The Dockerfile sets `APP_AUTH_REQUIRED=true`, so the *image* throws `MissingPasswordException`
at startup rather than coming up open — with a `FailureAnalyzer` so the operator sees a readable
block instead of sixty lines of stack trace. CI asserts both: 401 to an unauthenticated caller,
and a non-zero exit with no password.

> **A CSRF rejection reads as 401, not 403, when the caller is not signed in yet.** Spring routes
> `AccessDeniedException` for an anonymous user to the *authentication* entry point, which is
> ours and returns 401. So a stale `XSRF-TOKEN` cookie — from a redeploy, or another instance on
> the same port — looks exactly like a wrong password. `login()` in `api.ts` refreshes the token
> before submitting for precisely this reason; do not "optimise" that call away.

> **The CSRF cookie needs `CsrfCookieFilter` to exist at all.** Spring defers the token, so
> nothing writes the cookie until something reads its value — injecting a `CsrfToken` into a
> controller is not enough. Without the filter a client that has only made GETs holds no token
> and its first write is refused, as a 403 or, when not signed in, a 401 that reads exactly like
> a wrong password. `CsrfCookieTest` guards it, deliberately in its own class: the `csrf()`
> request post-processor primes a token for the request it decorates, which masks whether the
> app would have issued one on its own.

> **A `@SpringBootTest` that writes through MockMvc needs `@Transactional`.** The test database is
> a file, so anything not rolled back leaks into whichever test asserts on it next. `AuthGateTest`
> broke `BudgetControllerTest` this way before the annotation was added.

**Login lockouts are tracked per caller address, in memory, not in the database.** One account
means one thing to guess, so `LoginAttemptLimiter` locks a caller out after
`app.auth.max-attempts` consecutive wrong passwords, for `app.auth.lockout-minutes`. Per-address
rather than global, so guessing from one network never blocks the owner signing in from another.
In-memory rather than persisted: a restart already drops every other piece of session state, and
a table plus a cleanup job would be solving a problem that already resets itself for free.

> `LoginAttemptLimiter` takes a `Clock` via a package-private constructor specifically so
> `LoginAttemptLimiterTest` can advance time without a real sleep to prove a lockout expires.
> `LoginRateLimitTest` covers the HTTP-level wiring instead (status, `Retry-After`, per-caller
> isolation) against the real endpoint, and clears the shared limiter bean's state in
> `@BeforeEach` — it is a singleton that outlives each `@Test` method, so a lockout left behind by
> one case would otherwise leak into the next.

**CORS defaults to loopback on any port, not `*`.** Once the app is one artifact the frontend is
same-origin and needs no CORS at all; the only real caller from another origin is the dev server.
The port has to be a wildcard (`http://localhost:[*]`) rather than a pinned 5173: Vite moves to
5174 and upwards when another project already holds 5173, and a pinned origin turns that into a
403 on every write while reads appear to work — a genuinely confusing failure, and one that
already happened here. `CorsConfigTest` covers both the fallback port and the rejection cases.
`CORS_ALLOWED_ORIGINS` widens it, and the container sets it blank. This is *not* access control —
there is still no authentication, which is the top item on the README roadmap.

**Merchant memory is one row per amount band, not one per merchant.** `merchant_key` is no
longer unique; `MerchantCategory.bestMatch` picks the narrowest band containing the amount, with
source as the tie-break. Two consequences that bit during the change: `remember`'s upsert conflict
target is the whole band, and `recordHits` is keyed by row id — keyed by merchant it would credit
every band for a hit only one of them answered.

> Bounds are stored numbers with `1e12` standing in for "unbounded", **not** NULL. SQLite treats
> NULLs as distinct in a UNIQUE index, so NULL bounds would let duplicate catch-all rows through
> the upsert instead of updating.

**Budgets are keyed by category name, so category edits must cascade.** `CategoryRepository`
owns that: `rename` carries the budget (and merchant memory) across, `deleteAndReassign` drops
the budget rather than folding it into the fallback category. Anything else that starts storing
a category name belongs in those two methods too.

**`predictions_cache` is keyed by account id, same 0-sentinel trick as the merchant bands.**
It used to be one global row (`id = 1`) with no account attached — generate a forecast while
looking at one account, switch to another, and the dashboard kept showing the first account's
numbers with nothing to say they didn't belong. `PredictionsCacheRepository.key()` maps a null
`accountId` to `0` before it ever reaches SQL, for the same reason as `MerchantCategory.UNBOUNDED`:
`account_id` is `NOT NULL PRIMARY KEY`, so a real `NULL` couldn't be inserted at all, and even if
it could, SQLite treats every `NULL` as distinct in a key column — two "all accounts" upserts
would insert two rows instead of the second replacing the first. Real accounts start at 1
(`AUTOINCREMENT`), so `0` can never collide with one.

> `V8__predictions_cache_per_account.sql` drops the old single row rather than migrating it.
> There is no account recorded on it, so there is no honest guess at which account it belonged
> to — carrying it forward under an assumed scope would just be a different flavour of the same
> mismatch the migration exists to fix. Unlike the merchant-memory and transaction migrations,
> this one is fine to lose: regenerating a forecast is one click.

**Exports are links, not fetches.** `/api/export/*.csv` are plain GETs returning an attachment,
so the frontend renders an `<a download>` and the browser does the rest. Fetching them into a blob
would discard the `Content-Disposition` filename and force the client to invent one. They are also
unpaged on purpose — the transactions endpoint's 200-row default would truncate a file silently,
and `ExportControllerTest` seeds 250 rows specifically to catch that regression.

**The database path is an environment variable.** `${user.dir}/data.sqlite` is right for a local
run and useless in a container, where the file has to sit on a mounted volume to survive
`docker rm`. Hence `SPENDING_ANALYZER_DB`, defaulting to the old behaviour.

---

## Testing

- **Pure logic** → plain unit tests, no Spring. Fast, and most of the suite.
- **Database behaviour** → `@SpringBootTest` + `@ActiveProfiles("test")` + `@Transactional`, so
  inserts roll back and the shared test database is left as found.
- The **smoke test boots the app with no API key**, matching CI. This is what catches broken
  wiring and failed migrations. It has earned its place twice — see below.

> ⚠️ Test config must be `application-test.properties` (**profile-specific**). A plain
> `application.properties` under `src/test/resources` sits earlier on the classpath and
> *replaces* the main file wholesale, silently dropping datasource and migration settings.
> This one is genuinely hard to spot: the symptom is "no such table".

### Why compile-only CI is not enough

Both of these compiled cleanly and failed only at runtime:

- **Spring Boot 4 moved to Jackson 3** (`tools.jackson`), so the auto-configured Jackson 2
  `ObjectMapper` bean vanished and the services injecting it could not start. Jackson 3 still
  depends on the Jackson 2 *annotations* artifact, so `@JsonProperty` kept working and the wire
  format was unchanged — worth knowing before touching serialization.
- **Spring Boot 4 split auto-configuration into per-technology modules.** `flyway-core` alone is
  no longer auto-configured: no logs, no error, migrations silently skipped. Fixed by adding
  `org.springframework.boot:spring-boot-flyway`.

---

## Ports and commands

```bash
# Backend on :4000
cd server-springboot && ./mvnw spring-boot:run

# Frontend on :5173 (proxies /api to :4000)
cd client && npm run dev

# Full checks, as CI runs them
cd server-springboot && ./mvnw clean verify
cd client && npm run lint && npm run build
docker build -t spending-analyzer .

# The single artifact — whole app on :4000, no Vite
cd server-springboot && ./mvnw -Pfrontend clean package && java -jar target/spending-analyzer.jar

# Or the same thing containerised, with the database on a volume
docker compose up --build
```

`DELETE /api/reset` clears transactions but keeps accounts and categories.
`DELETE /api/merchants` clears merchant memory. Both are useful for resetting after testing.

---

## Keeping context small

This project is documented well enough that a fresh session costs almost nothing — start one
per feature rather than continuing a long thread. Read `README.md` and this file, and go.

When working: filter command output rather than dumping it, scope page reads instead of pulling
whole DOM trees, and do not re-read files immediately after writing them.

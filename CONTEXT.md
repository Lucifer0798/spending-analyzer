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
prediction cache is a single row not keyed by range.

**Period comparison needs both bounds of the range set, not just one.** A half-open filter (only
`from` or only `to`) has no defined length, so `StatsService.computeComparison` returns
`Optional.empty()` for it exactly as it does for "all time" — treating a missing bound as "use
the account's earliest/latest date instead" would produce a comparison window nobody asked for
and that changes size every time new data is imported. `ComparisonResponse.applicable` carries
that decision to the frontend explicitly, rather than the client having to infer "not applicable"
from an empty-looking payload.

**A custom comparison range is a third overload parameter, not a second code path.**
`StatsService.computeComparison(accountId, range)` still auto-derives the previous period exactly
as before; the new `computeComparison(accountId, range, explicitPreviousRange)` overload only
skips that derivation when a non-null range is actually passed, so every existing caller (and
every existing test) kept working unchanged. `explicitPreviousRange` still needs both of its own
bounds set — the same "no arbitrary guessing" rule `range` alone already had to satisfy — but,
unlike the auto-derived case, does *not* need to share `range`'s length: "this March vs last
March" is a real comparison worth making even though a leap year makes them different lengths,
and there's no length to defend once both ranges are independently chosen anyway.
`InsightsController.comparison` only builds that explicit range when the caller actually supplies
`compareFrom` or `compareTo` — if just one is given, `DateRange.of` produces a half-open range,
which `computeComparison` refuses the same honest way it already refuses a half-open primary
filter, rather than the controller having a second, different validation path to keep in sync
with the first.

**`PeriodComparison.custom` exists so the frontend doesn't have to compare two `DateRange`s to
find out which kind of comparison it's looking at.** The field is one boolean
(`explicitPreviousRange != null` at the point `computeComparison` builds the result), and it's
what lets `ComparisonCard` swap its heading between "Compared to the previous period" and "Custom
comparison" without re-deriving what the server already knows.

**`ComparisonCard`'s custom-range inputs stay visible through a half-typed pick, but the whole
card still hides by default.** The original behavior — render nothing for "all time," a half-open
filter, or mixed currencies — is preserved exactly when the user hasn't touched the new control:
`if (!applicable && !customizing) return null`. Once `customizing` is true, though, the card stays
up even if the in-progress custom range isn't complete yet (or comes back not-applicable), so
typing the first of two dates doesn't make the card vanish out from under the user mid-edit.
`customizing` can only become true by clicking a button that itself only exists on an
already-visible card, so this never makes the card appear before there is something to compare in
the first place — the discoverability of "you can customize this" is gated on the same
applicability check the card always had.

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

**`RecurringSeries` carries override fields it never sets itself.** `RecurringDetectionService`
always constructs one with `flaggedForCancellation=false, overrideId=null` — it has no dependency
on `RecurringOverrideRepository` and never will, the same separation `MerchantCategory`'s bands
keep from the categorization that reads them. `InsightsController.recurring()` is what actually
applies overrides, in two passes over the detected list: a `filter` drops any merchant with an
`exclude` row before the totals are summed (so an excluded merchant contributes nothing, not just
disappears from the visible list), then a `map` calls `RecurringSeries.withOverride(...)` — a
hand-written wither, since records don't get one for free — to layer in a `cancel` row's flag.
Both look the override up by `s.merchant()`, which is already the normalized key
`MerchantNormalizer.normalize()` produces, so there's no re-normalizing on the way in.

> A "cancel" flag is deliberately just a note that survives past the moment it stops being true.
> If the subscription is actually cancelled, the charges stop, and eventually the series ages out
> of the window and stops being detected at all — but the override row lingers, un-cleared, until
> someone visits Manage and removes it. That's accepted: cleaning it up automatically would need
> to guess whether "no longer detected" meant "cancelled as planned" or "range too narrow to see
> it," and guessing wrong silently would be worse than a stale row sitting in a list built
> specifically so stale rows have somewhere to be found and removed.

**Recurring income needed zero changes to `RecurringDetectionService` itself.** `detect(List
<Transaction>)` was already fully agnostic about spending vs. income — cadence and consistent
amount are the only signals it looks at, computed the same way regardless of what the list
contains. The only new code is `TransactionRepository.findIncomeTransactions`, the mirror of
`findSpendingTransactions` (credits in income categories, instead of debits outside them), and
`InsightsController.recurringIncome` wiring that list into the same detector. This is the payoff
of the detector taking a plain `List<Transaction>` rather than querying for spending transactions
itself.

**Recurring income deliberately has no cancel/exclude override, unlike `/recurring`.** "Cancel"
describes dropping a subscription you're paying for; there's no income-side equivalent of that
intent. "Exclude" (a false positive) is still theoretically possible, but a personal account has
far fewer income sources than spending merchants, making a whole override management screen for
it not worth building until it's actually needed. `RecurringSeries` objects returned from
`recurringIncome()` are used exactly as `detect()` produces them, with no `withOverride(...)`
pass the way `/recurring` has.

**`actualThisMonth` and `totalMonthlyEquivalent` are deliberately not compared against each
other.** `totalMonthlyEquivalent` is `annualizedIncome / 12`, the exact same smoothing
`/recurring`'s figure of the same name already does for spending — an average, not a claim that
the measured month specifically will see that much. A quarterly bonus averaged into a monthly
figure will honestly disagree with most individual months by design, so presenting the two as
"expected vs. actual" with an implied should-match relationship would be a precision the detector
doesn't have. They're computed independently and shown side by side instead:
`StatsService.computeIncomeTotal` sums actual income for one resolved month (mirroring
`BudgetService.resolveMonth`'s "newest month with data, not today" default, via
`StatsService.latestIncomeDate`), while the recurring list and its monthly-equivalent figure are
computed from whatever `from`/`to` range the page has active, same as `/recurring`'s own list.

**`computeIncomeTotal`/`latestIncomeDate` use `jdbc.queryForObject`, not
`jdbc.query(...).stream().findFirst()`.** The latter is a real trap for a nullable scalar
aggregate: `Stream.findFirst()` wraps its result in `Optional.of(...)`, which throws
`NullPointerException` outright when the single row's mapped value is itself `null` — exactly
what `MAX(t.date)` produces when there's no income yet. `availableRange` gets away with the
`.stream().findFirst()` pattern because its `RowMapper` wraps the nullable columns inside a
`DateRange` record — the record itself is never null, only its fields are — but a bare nullable
`String`/`Double` result has no such wrapper to hide behind. `queryForObject` doesn't have this
problem: for a single-row, single-column aggregate, it returns `null` directly rather than
throwing, which is exactly what every other single-scalar query in this codebase
(`CategoryRepository.exists`, `BudgetRepository`'s counts) already uses it for.

**`EFFECTIVE_AMOUNT` (the split-share fallback) applies to income the same as it does to
spend.** `computeIncomeTotal` sums `COALESCE(t.split_share, t.amount)`, not `t.amount` — a
deposit split with someone else (a shared reimbursement, a joint account payroll) counts only
your share toward "actual income," the same symmetry a shared expense already gets. No special
case was needed to make this true; it fell out of reusing the same constant.

**`AnomalyDetectionService` uses the median, not the mean, deliberately.** A category's "typical"
amount has to survive the very outlier it's being used to judge — a mean would get dragged toward
a $400 charge sitting in an otherwise-$80 category, quietly raising the bar the $400 charge itself
needs to clear. The median moves far less for one extreme value, the same reasoning
`RecurringDetectionService` already leans on for interval and amount consistency (median interval,
coefficient of variation off a mean amount — though there the mean is safe, since a recurring
charge is defined by having *no* outliers to begin with). `MIN_SAMPLES = 5` exists because a
median of two or three points isn't a "typical" worth naming; a category under that count is
skipped entirely rather than flagging or clearing everything in it by chance. Categories are never
compared to each other — grouped, judged, and thresholded independently, the same per-merchant
independence recurring detection already has for cadence.

> Like `RecurringSeries`, nothing about a `SpendingAnomaly` is stored — `AnomalyDetectionService`
> is pure logic over whatever `TransactionRepository.findSpendingTransactions` returns, recomputed
> on every `/api/anomalies` call. There's no override table the way `recurring_overrides` gives
> recurring detection somewhere to attach "I know, ignore this" — an anomaly is inherently a
> one-off past event, not an ongoing pattern something needs to be remembered about across
> requests the way a subscription's cancel/exclude flag does.

**`InsightsController.anomalies` refuses to compare across currencies, same as `/recurring`.** A
category's median has no single unit once "all accounts" spans more than one currency — comparing
a $400 USD charge to a category baseline built from mixed USD and EUR amounts would be
meaningless, not just imprecise. `mixedCurrencies: true` with an empty list is the same shape
`/recurring` already returns for the same reason, so `AnomaliesCard` on the frontend renders
nothing in that case exactly the way `BudgetsCard` does.

**Net worth is manual snapshots, not derived from a starting balance plus transactions — decided
explicitly, not by default.** The alternative (one starting balance per account, every transaction
netting against it automatically) needs no re-entry, but silently drifts from reality on any
statement gap, missed import, or miscategorization, with no way to detect the drift — a wrong
number that looks exactly as confident as a right one. `account_balances` instead holds exactly
what was logged, on request: `AccountBalanceRepository.upsert` keyed on `(account_id, date)`, the
same "one true value as of a day" `PredictionsCacheRepository`'s per-account key already models,
just with a date added. The number is taken literally, positive or negative, with no sign-flip
based on `accounts.type` — `AccountBalanceCard` on the frontend is the only place that knows a
credit card's balance is a bill rather than a holding, asking "how much do you owe" and negating
it before the request goes out; the API and the stored value never see a "type" concept at all.

**`NetWorthService.computeForCurrency` reconstructs history with a single forward pass, not a
query per date.** Rows come back from `AccountBalanceRepository.findAllForActiveAccounts` already
ordered by date then account id. Walking them once, updating a `Map<accountId, balance>` as each
row is seen and emitting a history point only when the date changes, gets the carry-forward
behavior (every account's *last known* balance counts toward every date, not just the ones it was
itself logged on) in O(n) with no re-fetching. The same map holds the answer for "current total"
and "each account's latest balance" at the end of the loop, so one pass produces both the current
snapshot and the whole history.

> Archived accounts are excluded at the repository query (`WHERE a.archived = 0`), not filtered
> in the service afterward — so an archived account's past balances vanish from history retroactively
> the moment it's archived, not just from the current total. This was a deliberate scope cut for
> v1: correctly preserving an archived account's contribution to *past* points while excluding it
> from the *current* one would need each history point to know which accounts were active as of
> that date, not just which are active now.

**`AccountBalanceRepository` and `NetWorthService` follow the goals/tags split: mutate on the
resource, read in its own controller.** `POST`/`GET`/`DELETE /api/accounts/{id}/balances` live on
`AccountController` since a balance is a sub-resource of one account, mirroring how transaction
tags live on `TransactionController`; the cross-account `/api/net-worth` view gets its own
`NetWorthController`, mirroring how `/api/goals` is separate from whatever reads across every
goal. Deleting an account calls `accountBalanceRepository.deleteByAccountId` directly in
`AccountController.delete`, the same explicit cleanup `GoalController.delete` does for
contributions — there are no foreign keys in this schema, so nothing does it automatically.

**`NetWorthResponse` mirrors `SummaryResponse`'s currency/perCurrency duality exactly, not
`/recurring`'s "refuse and ask for one account."** Recurring detection and budgets are scoped to
one thing at a time (a merchant, a category) where "pick an account" is a reasonable ask; net
worth is inherently "the whole picture across every account," the same shape a combined dashboard
summary is. `currency` is `null` with `perCurrency` populated once active accounts disagree,
`"USD"` with empty lists when nothing has been logged at all yet — that empty-state default matters
because `null` already means something specific (mixed currencies) and reusing it for "no data"
would make the frontend's `NetWorthPage` treat an empty instance as a currency mismatch it needs to
explain, rather than the plain "nothing logged yet" it actually is.

**`BackupService.restore` is the only `@Transactional` boundary; the six `restoreAll` methods
have none of their own.** Each is `DELETE FROM <table>` followed by a batch insert that writes
every column including `id` — deliberately raw SQL rather than going through `create`/`upsert`,
which would assign fresh ids and silently break every reference a backup file has between tables
(a transaction's `account_id`, a budget's category name). Calling six non-transactional methods
in sequence from one `@Transactional` service method is what makes a restore atomic: if row five
fails, Spring rolls back all of it, not just what came after the failure. `predictions_cache` is
cleared the same way from inside that method despite never being part of the backup itself —
`BackupData` has no field for it, so `PredictionsCacheRepository.deleteAll()` is called directly
rather than threaded through `BackupData` for a table that was never going to round-trip anyway.

> Explicit ids into an `AUTOINCREMENT` column are not a hack — SQLite advances `sqlite_sequence`
> to match the highest id it has ever seen inserted, explicit or not. An account created after a
> restore gets the next id in sequence, never one already used by a restored row.

**Goal progress is summed from logged contributions — there is no balance anywhere to read it
from.** Every other derived number in this app (spend totals, budgets, recurring detection) is
computed from categorized transactions, but a transaction only says "this much moved in a
category," never "this account currently holds this much." A savings goal needs the latter, and
this app has no source for it — so `GoalService.progress` doesn't try to infer one. It sums
`goal_contributions.amount` for the goal (`GoalContributionRepository.totalsByGoal`, one grouped
query for every goal rather than one query per goal) and compares that to `target_amount`
directly. A negative contribution is a withdrawal, not a validation error — `CHECK (amount <> 0)`
is the only constraint, so the sum can legitimately go down.

> `remaining` clamps at zero (`Math.max(0, target - saved)`) rather than going negative the way
> `BudgetProgress.remaining` does. The two read oppositely: a budget going negative means "over,"
> which is bad and worth surfacing as a specific number; a goal exceeding its target is already
> fully achieved, and "-200 remaining" would read as confusing rather than informative. `achieved`
> (`saved >= targetAmount`) is the flag the UI actually branches on for the "reached" state.

**Pace projection took an injectable `Clock`, following `LoginAttemptLimiter`'s precedent, not a
bare `LocalDate.now()`.** `GoalService` gained a package-private three-arg constructor
(`goals, contributions, Clock`) alongside the public two-arg one, which defaults to
`Clock.systemDefaultZone()` — the public constructor needs an explicit `@Autowired` once a second
constructor exists, or Spring can't pick one and the context fails to start (this broke on first
pass; the fix was adding the annotation, not removing the second constructor). Tests build a
`GoalService` directly with `Clock.fixed(...)` to assert a projected date exactly rather than
computing an expected value relative to whatever day the suite happens to run on.

> `Clock.systemDefaultZone()`, not `systemUTC()` the way `LoginAttemptLimiter` uses it. That
> class only measures elapsed *duration* for a lockout, where a timezone is irrelevant; this one
> answers "what calendar date is today" to match against `date` strings the rest of the schema
> already treats as local dates with no timezone of their own (same assumption `BudgetService`
> makes with `YearMonth.now()`). `systemUTC()` here could read as tomorrow or yesterday depending
> on where the server runs relative to the user.

**The pace calculation measures from the first contribution ever logged, not a trailing window.**
Unlike `StatsService.computeMonthlyCategorySeries`'s three-month moving average, which has months
of transaction history to smooth over, a goal typically has a handful of contributions total —
averaging only a recent slice would swing hard on every new entry rather than settling toward a
representative rate. `GoalService.progress` instead divides the total saved by
`ChronoUnit.DAYS.between(firstContributionDate, today)` (floored at 1 day, so a contribution
logged today doesn't divide by zero) and scales by 30.44 (the average days per month) to get a
`$/month` figure comparable to how budgets already frame targets.

> The 30.44 conversion cancels out algebraically in the "days to go" calculation
> (`(remaining / monthlyPace) * 30.44`, where `monthlyPace` already carries a `* 30.44` factor),
> so `GoalServiceTest.projectsCompletionDateFromPace` can assert an exact day count by hand rather
> than tolerating a rounding fudge factor.

**No projected date at all beats a nonsensical one.** `projectedCompletionDate` stays `null` in
three cases: the goal has no contributions yet (no pace to measure), it's already `achieved`
(nothing left to project), or `monthlyPace` isn't positive (net withdrawals — the goal is moving
away from its target, and `today + a negative or unbounded number of days` isn't a real date).
Only a target date **and** a positive projection together produce the "on track" / "behind pace"
distinction; a goal with no target date just gets an informational projection with no verdict
attached, since there's nothing to be on track *against*.

**A goal's currency is its own field, not inherited from an account.** Budgets and recurring
detection are account-scoped and use `StatsService.resolveCurrency`; goals deliberately aren't —
a savings target is a personal number ("save $5,000") that doesn't need to match whatever account
the money ends up sitting in, and manual contributions have no account to inherit from in the
first place. `GoalController.create` validates it the same way `AccountController` validates
`accounts.currency` (`java.util.Currency.getInstance`, uppercased), but there's no shared helper —
small enough to duplicate rather than extract, same call the account currency validation made.

**Deleting a goal deletes its contributions in the controller, not the database.** `goal_id` on
`goal_contributions` has no `REFERENCES` clause, matching every other id column in this schema —
foreign keys aren't enforced here, a standing choice explained in `V2__accounts.sql` and
`V8__predictions_cache_per_account.sql`'s comments. `GoalController.delete` calls
`contributions.deleteByGoalId(id)` right after `goals.delete(id)` succeeds; skip that call and a
deleted goal's contributions become permanently orphaned rows with no UI path to reach them.

**Adding goals to the backup bumped `BackupData.CURRENT_VERSION` from 1 to 2, which is a breaking
change on purpose.** `BackupController.restore` rejects anything whose `version` doesn't match
exactly — there's no upgrade path between backup versions, only "supported" or "400." A version-1
file predates the `goals`/`goalContributions` fields entirely, and there's no honest default to
backfill them with (unlike `V9__account_currency.sql`'s `DEFAULT 'USD'`, "no goals" isn't a
sensible guess about what a pre-goals export meant). This is the same reasoning
`V8__predictions_cache_per_account.sql` used to justify dropping data rather than migrating it —
just applied to the export format instead of a table.

**Adding tags bumped `BackupData.CURRENT_VERSION` again, 2 to 3, same reasoning as the goals
bump above.** `tags`/`transactionTags` are new fields a version-2 file has no values for, and
there's still no upgrade path between versions.

**`TransactionRepository.find`/`count` gained a `tag` parameter via overloads, not by changing the
existing signatures.** Both methods already had several call sites — `ExportController`,
`BackupService`, several tests — that don't need to know about tags at all. Changing the signature
in place would have forced every one of them to pass an extra `null`; the two-argument-list
overload lets the five-argument callers stay exactly as they were and only the transactions
endpoint and its CSV export (which does thread the filter through, so an export matches what's
filtered on screen) opt into the new parameter. The tag filter itself is an `EXISTS` subquery
against `transaction_tags`/`tags`, not a `JOIN` — a transaction can carry several tags, and joining
would multiply a matching row once per tag, corrupting both the list and `count`.

**Search followed the tag filter's overload pattern exactly, one layer deeper.** `find`/`count`
gained an eight/six-argument overload taking `search` after `tag`, with the seven/five-argument
versions delegating with `search = null` — same reasoning as the tag overload above, just one more
rung on the same ladder. `TransactionController.list` and `ExportController.transactions` are the
only two callers that moved to the new overload; everything else (`BackupService`, most tests)
still calls a shorter one and never finds out `search` exists.

**The search filter is a plain `LIKE`, not `FTS5`, and its wildcard characters are escaped by
hand.** `appendSearchFilter` builds `t.description LIKE :search ESCAPE '\\'` with `%`/`_` in the
term backslash-escaped before the surrounding `%...%` wildcards are added — SQLite's `LIKE` treats
both characters specially, so a search for a literal `%` (a discount code, "50% off") would
otherwise match every row instead of rows containing an actual percent sign. No `COLLATE NOCASE`
is needed the way `tags.name` needed it: SQLite's `LIKE` is already case-insensitive over ASCII by
default. `FTS5` (a virtual table, triggers to keep it in sync with `transactions`, a tokenizer)
would be the standard answer at web scale; a personal statement history is small enough that a
substring scan is fast without it, and there's no ranking need since every match is a single flat
list.

**Bulk actions are real `WHERE id IN (:ids)` statements, not a loop calling the single-transaction
methods once per id.** `TransactionRepository.updateCategoryBulk`/`deleteBulk` and
`TagRepository.addTagBulk` each do one SQL statement (a plain `UPDATE`/`DELETE`, or one
`batchUpdate` for the tag join rows) regardless of how many ids are selected — a thousand
selected transactions costs one round trip to SQLite, not a thousand. `TransactionController
.bulkCategory` still teaches merchant memory the same way a single-transaction category edit does,
but the source rows have to be fetched first (`repository.findByIds`) *before* the bulk `UPDATE`
runs — the description merchant memory keys on lives on the row being updated, so it has to be
read while it's still there. A `LinkedHashSet` of already-taught merchant keys means a shared
merchant among the selection is only remembered once, not once per row that happens to share it.

> `bulk-category`/`bulk-tags`/`bulk-delete` are literal path segments alongside `/transactions/{id}`,
> not nested under it — Spring's path matching prefers the more specific literal match over the
> `{id}` variable pattern, so both coexist safely without `bulk-category` ever being parsed as an id.

**Bulk delete is `POST /transactions/bulk-delete`, not `DELETE` with a body.** The set of ids to
remove has to travel as a request body, and while Spring and `fetch` both technically support a
body on `DELETE`, it's an unusual enough combination to be worth avoiding for no real benefit —
`POST` to an action-shaped URL sidesteps the question entirely, the same pragmatic choice
`POST /merchants` already makes for an upsert instead of forcing a `PUT`.

**`transactions` list responses gained tags via a wrapper DTO, not by adding a field to
`Transaction`.** `Transaction` round-trips through the backup file and the CSV export as-is; adding
a `tags` field there would mean every `restoreAll`/CSV path either has to know about tags or
silently ignore a field that doesn't apply to it. Instead `TransactionController.list` composes a
`TransactionWithTags(@JsonUnwrapped Transaction, List<String> tags)` per row — `@JsonUnwrapped`
flattens the wrapper in JSON, so the wire format is unaffected and the client's
`TransactionWithTags` type is just `Transaction` plus a `tags` array. `TagRepository.namesByTransactionId`
fetches every returned row's tags in one grouped query rather than one query per row, the same
batching `GoalContributionRepository.totalsByGoal` uses for goals.

**A tag name is `COLLATE NOCASE` at the column, not normalized in Java.** "Business Trip" and
"business trip" have to resolve to the same tag, and declaring the collation on `tags.name` gets
that for the UNIQUE constraint, `ON CONFLICT(name)`, and every `WHERE name = :name` lookup all at
once — no uppercasing convention to remember and apply consistently, unlike merchant keys
(`MerchantNormalizer`) or account names (`AccountRepository.nameExists`'s explicit `COLLATE
NOCASE`, since that check isn't declared on the column). The casing of whichever spelling was used
first is what sticks, since `ON CONFLICT ... DO NOTHING` leaves the existing row untouched.

**Untagging doesn't delete the tag; only `DELETE /api/tags/{name}` does.**
`TagRepository.removeTag` removes one `transaction_tags` row and leaves `tags` alone, so a tag with
zero current uses still appears in the filter dropdown and autocomplete — the same "flagged but
now unused" state `recurring_overrides` accepts (see above) rather than silently vanishing and
reappearing under a fresh row if reused later. Actually deleting a tag everywhere is a distinct,
explicit action from Manage, mirroring "Forget all" for merchant memory.

**A filter preset's name is `COLLATE NOCASE`, same convention as `tags.name`.** Both are natural
keys where a user retyping an existing name (different casing or not) should replace, not
duplicate — `filter_presets.upsert` is an `ON CONFLICT(name) DO UPDATE`, exactly the tag
create-if-missing pattern applied to a five-field row instead of a bare name.

**Applying a preset has to reach state `TransactionsTable` doesn't own.** Category, tag, and
search are local to the component; account and date range live in `App` and are shared with
every other tab. There was no way to "just setState" all five from inside `TransactionsTable`,
so `App` passes down `onAccountIdChange`/`onRangeChange` filled with the same `setAccountId`/
`setRange` it already uses for its own header controls — the preset's apply handler calls all
five setters (three local, two lifted) in one click handler, and the two lifted ones happen to
change state one level up instead of in place.

**Filter presets have no Manage-page section, unlike tags and recurring overrides.** Both of
those split "set in context" from "list and clear centrally" because deleting a tag *everywhere*
or clearing a stale override needs a view wider than any one transaction row. A preset has no
such need — apply and delete are already both one click away as chips on the Transactions page
itself, the only place a preset is ever used, so a second page listing the same chips would be
a redundant view onto the same seven-or-so rows a person is likely to ever save.

**Deleting an account clears its id from any preset that referenced it, rather than deleting the
preset.** `AccountController.delete` calls `filterPresetRepository.clearAccountReference(id)`
alongside the existing `accountBalanceRepository.deleteByAccountId(id)` — no foreign keys exist
in this schema, so both are explicit application-layer cleanup. A preset losing its account
becomes an "all accounts" preset rather than vanishing outright, since its category/tag/search
fields are still meaningful with no account attached.

**Adding filter presets bumped `BackupData.CURRENT_VERSION` again, 4 to 5, same reasoning as
every prior bump.** A version-4 file has no `filterPresets` field and there's still no upgrade
path between backup versions — see the goals (1→2) and tags (2→3) bumps above.

**Budgets are keyed by category name, so category edits must cascade.** `CategoryRepository`
owns that: `rename` carries the budget (and merchant memory) across, `deleteAndReassign` drops
the budget rather than folding it into the fallback category. Anything else that starts storing
a category name belongs in those two methods too.

**A category group is a flat label on the category row, not a parent-category relationship.**
`categories.group_name` is a plain nullable `TEXT` column, not a self-referential `parent_id` —
the ask was "roll several categories up under one coarser bucket," which a shared string
satisfies exactly, and a real tree would add cascade-on-rename/delete questions and arbitrary
depth this app has no use for. Two categories sharing a group is the entire relationship; nothing
else references it, so unlike a budget or merchant-memory entry, deleting or renaming a category
needs no group-side cleanup at all — the group lives and dies with the row it's a column on.

**Category-group rollup is computed client-side, not as a second backend aggregate query.**
`StatsService.computeCategoryTotals` is unchanged; the frontend already has every category's
totals from `/api/summary` and every category's group from `/api/categories`, so `Dashboard.tsx`'s
`groupTotals` folds one into the other in memory. The one thing that *is* server-side is the
categories CSV export's `group` column (`ExportController.groupByCategory`, a
`Map<String,String>` built once per request) — a CSV has no client afterward to do the folding,
so the same category→group lookup has to be resolved before the file leaves the server. Both
sides apply the identical fallback (a category with no group rolls up under its own name) so a
spreadsheet pivot and the dashboard chart never disagree about what "grouped" means for a category
nobody has grouped.

**A category's group can be set on a built-in category — renaming one still can't be.** These are
different guarantees: a built-in's *name* is load-bearing (recurring detection, merchant memory,
and every hardcoded default assume `"Groceries"` keeps meaning `"Groceries"`), but its *group* is
just a label a user chose, no different in kind from a custom category's group. `CategoryController
.update` gates the name change on `!category.isBuiltin()` and does not gate the group change at
all.

**Setting a category's group uses `body.containsKey("group_name")`, not a null check, to decide
whether to touch it.** A `Map<String, Object>` body returns `null` from `.get()` both when a key
is absent and when it's present with a `null` value, so a plain `body.get("group_name") != null`
check would be unable to tell "this PATCH doesn't mention the group" (leave it alone) apart from
"this PATCH explicitly clears the group" (an omitted key can't express that). `containsKey` is
what lets a rename-only or flags-only `PATCH` leave an existing group untouched while a `PATCH`
that explicitly sends `"group_name": null` (or a blank string) clears it — the same
whole-field-not-a-merge contract an upsert gives everywhere else in this app, applied to one
field of a `PATCH` instead of a whole row.

**A budget's escalation schedule is computed on read, not applied by a background job that
mutates `monthly_limit` over time.** This app has no scheduler or cron equivalent — everything
derived here (recurring detection, anomalies, net worth history) is recomputed fresh from stored
facts on every request, and a budget's effective limit follows the same rule.
`BudgetService.effectiveLimit` takes the base `monthly_limit`, the elapsed whole periods between
`escalation_start_month` and the month being measured (`ChronoUnit.MONTHS.between(...) /
escalationFrequencyMonths`, floored), and applies either a flat multiple (`fixed`) or a compounding
power (`percent`) of that period count. Measuring a month before the start reports the untouched
base, identical to having no schedule — there is no "negative periods" case to reason about.
`BudgetProgress.monthlyLimit` carries the *effective* value (what `spent` is actually compared
against, so the dashboard needs no changes to already be correct); `baseLimit` carries the raw
stored one, which is what an editor has to read from, not `monthlyLimit` — prefilling an edit
field from the escalated number would silently lock in the current inflated value as a new flat
base and erase the schedule's growth curve retroactively.

> All four escalation columns added in `V15__budget_escalation.sql` are nullable and start `NULL`
> on every existing row, which reads as "no schedule" — exactly today's behavior, unchanged. This
> is the same additive-nullable-column shape `V9__account_currency.sql` used, not the "new table"
> shape that bumped `BackupData.CURRENT_VERSION` for goals/tags/net-worth/filter-presets: `Budget`
> was already part of the backup, an old export just deserializes with the new fields `null`, and
> that's a meaningful, correct value here (not a lossy guess), so no version bump was needed.

> The upsert replaces the whole row every save, escalation included — same convention as the base
> target itself — so a save that omits the escalation fields entirely clears any schedule that was
> there. That makes it the frontend's job to always resend the current schedule on a save that
> isn't touching it (`ManagePage`'s escalation draft falls back to the saved budget's own fields,
> including `escalationStartMonth`, whenever the editor hasn't been opened) — the fix for a real
> bug caught during manual verification: falling back to everything *except* the start month meant
> every edit to the plain limit silently reset the schedule's start to "now" and erased whatever
> periods had already elapsed. An unset start month is only ever correct for a schedule that never
> had one before, which is also the one case `BudgetController.set` defaults it to the current
> calendar month for — "starts now" is a real-time action, not tied to the newest imported month
> the rest of this app's date defaults chase.

**Rollover carry-in is a sum of independent per-month contributions, not a compounding "available
balance" tracked month to month.** `BudgetService.rolloverCarryIn` walks every month from
`rolloverStartMonth` up to (not including) the measured month and sums
`effectiveLimit(thatMonth) - actualSpend(thatMonth)` for each one. This looks like it should
diverge from the more intuitive "May's available budget is April's leftover plus May's own
target, and May's leftover is what carries to June" framing — it doesn't, because leftover is a
purely additive quantity and addition doesn't care about grouping: `(base_April - spent_April) +
(base_May - spent_May)` and `((base_April - spent_April) + base_May) - spent_May` are the same
number. The additive form was chosen because it needs no extra state threaded through the loop
(no running "available" variable to carry between iterations) and it makes overspend "eating
into" a future month fall out for free — a negative contribution added is just subtraction,
requiring no separate code path from the underspend case.

**Rollover composes with escalation with no code aware of both at once.** Each historical month's
own contribution calls `effectiveLimit(budget, thatMonth)`, which already resolves that month's
own escalated base if a schedule is active — rollover never computes an escalation-free number
and never needs to. This is the same "derive it, don't special-case it" payoff `RecurringSeries`
detection got when income tracking needed zero changes to the detector itself.

> `BudgetService.progress` memoizes `computeCategoryTotals` per historical `YearMonth` in a map
> scoped to one `progress()` call (`spendByMonth`), not a field on the service. Several budgets
> can share overlapping rollover histories in one request — memoizing avoids re-querying the same
> month's spend once per budget, while keeping the cache request-scoped avoids the far worse bug
> of one request's history leaking into another's.

**Rollover has no value of its own, unlike escalation — which is what let the server manage its
start month entirely instead of asking the client to.** `BudgetController.set` takes a plain
`rollover: boolean`; when true, it checks the *existing* saved budget's `rollover_start_month`
and carries it forward if already set, defaulting to the current calendar month only when this is
a genuinely new enable. Escalation couldn't work this way — a change to its type, value, or
frequency is real configuration the client has to supply, so the client also has to resend the
existing start month on an unrelated save (the exact bug fixed during that feature's manual
verification). Rollover sidesteps the whole class of bug: there is no configuration for the
client to get out of sync with, so there was never a way for it to lose track of the start month
in the first place.

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

**`StatsService.resolveCurrency` is the single source of truth for whether "all accounts" means
anything.** It returns a specific account's own currency when `accountId` is given, the shared
currency when every account agrees, or `null` when they don't — `null` is the signal every caller
branches on. `InsightsController.summary` uses it to decide between the normal combined
`SummaryResponse` and a `perCurrency` breakdown (a second pass over `computeCategoryTotals` /
`computeMonthlyTotals`, now with an optional `currency` filter joined against `accounts.currency`
in `SPEND_FILTER`); `BudgetService.progress` and `InsightsController.recurring` use the same null
check to return an empty, `mixedCurrencies: true` result rather than a total that mixes units;
`InsightsController.refreshPredictions` and the `categories.csv`/`monthly.csv` exports use it to
refuse outright (400) rather than generate or export something wrong. Anything new that sums
across accounts needs to run this same check first — nothing enforces it structurally.

**`accounts.currency` has no sentinel; it's a plain column, unlike the two above.** There's no
NULL-collision problem here because there's no cache row keyed by it — currency is just an
attribute of a row that already has a real primary key. `V9__account_currency.sql` adds it with
`DEFAULT 'USD'`, so every account and every transaction imported before this migration keeps
meaning exactly what it meant. Changing an account's currency later (`AccountController.update`)
only relabels its numbers going forward; nothing recomputes past amounts, since there's no
exchange rate to recompute them with. Validation accepts any code `java.util.Currency` recognizes,
not just `Account.CURRENCIES` — that list is the dropdown's curated shortlist, not the whole rule.

**A split doesn't touch the stored `amount` — it adds a separate `split_share`.** `amount` is
always what the bank actually charged; splitting a transaction never rewrites it, the same way
categorizing one never rewrites its description. `split_share`/`split_note` are two new nullable
columns on `transactions` (`V17__transaction_splits.sql`), always set and cleared together since
a note with no share is meaningless. `TransactionController.update` treats `split_share` as the
field that actually sets or clears the pair — sending it `null` clears the note along with it —
while `split_note` alone can only edit the note on a split that already exists, mirroring how
`CategoryController.update` uses `body.containsKey("group_name")` to decide whether a `PATCH`
touches a field at all versus explicitly clearing it.

**"Spend" is computed from one `EFFECTIVE_AMOUNT` SQL fragment, not `t.amount`, everywhere
`StatsService` totals a category or a month.** `COALESCE(t.split_share, t.amount)` replaces
`t.amount` in `computeCategoryTotals`, `computeMonthlyTotals`, and `computeMonthlyCategorySeries`
— the three queries every "spend" number in this app is built from (the dashboard's charts,
budgets via `computeCategoryTotals`, period comparison, the categories/monthly CSV exports, and
the per-category series predictions read). Centralizing it there, rather than patching each call
site, is only possible because this app already routes every aggregate through `StatsService` —
the same chokepoint `resolveCurrency` already exploits for the mixed-currency check. Anomaly
detection and recurring detection deliberately do *not* get this treatment: both read `Transaction
.amount()` directly rather than through `StatsService`, and both are about recognizing what a
charge *is* (a subscription's cadence, an outlier against a category's typical amount), not what
share of it belongs to the signed-in person — using the split share there would flag a normal
$90 dinner as a shrunken $30 "typical" amount for reasons that have nothing to do with spending
behavior.

**The transactions CSV carries both the actual charge and your share, never just one.**
`amount`/`signed_amount` stay the literal charge, unaffected by a split — an export is supposed to
match what the bank statement said. `your_share`/`signed_your_share` sit alongside, falling back
to the full amount when there's no split, the same fallback `EFFECTIVE_AMOUNT` uses — so a
spreadsheet `SUM` over `signed_your_share` reproduces exactly what the dashboard already shows as
total spend, without the sheet author needing to know which rows happen to be split.

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

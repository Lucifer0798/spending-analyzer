# Working context

Notes for picking this project up cold — environment quirks, conventions, and decisions
that would otherwise get re-litigated. `README.md` explains what the app *is*; this covers
how to work on it.

**Getting up to speed:** read `README.md`, then this file. That should be enough to start.

---

## Environment (this machine)

| | |
|---|---|
| Java | 21+ installed. No Maven — use `./mvnw` (`mvnw.cmd` on Windows) |
| Node | 22 |
| Shell | Git Bash and PowerShell both available |
| `gh` CLI | Installed at `C:\Program Files\GitHub CLI`, **not on PATH**. Prefix commands with `export PATH="$PATH:/c/Program Files/GitHub CLI"` |
| API key | **Not configured.** AI categorization and predictions return 401; everything else works, including merchant memory |

### Two traps that cost time

**A running app locks the jar.** If `./mvnw clean` fails with *"The process cannot access the
file because it is being used by another process"*, a Spring Boot process is still holding
`target/spending-analyzer.jar`. It is not a build problem. `pkill -f` does **not** reliably
kill Windows Java processes — use PowerShell:

```powershell
Get-CimInstance Win32_Process -Filter "Name = 'java.exe'" |
  Where-Object { $_.CommandLine -like '*spending-analyzer*' } |
  ForEach-Object { Stop-Process -Id $_.ProcessId -Force }
```

**Stale `target/test-classes` can mask a fix.** Maven does not remove deleted resources on an
incremental build, so a config file you deleted can still be on the test classpath and make a
correct fix look broken. When test behaviour contradicts the source, run `clean` before
debugging further.

---

## Workflow

`main` is protected: no direct pushes, both CI jobs must pass, and a branch must be up to date
with `main` before merging. Enforced for admins, so there is no bypass without turning
protection off in **Settings → Branches**.

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
```

`DELETE /api/reset` clears transactions but keeps accounts and categories.
`DELETE /api/merchants` clears merchant memory. Both are useful for resetting after testing.

---

## Keeping context small

This project is documented well enough that a fresh session costs almost nothing — start one
per feature rather than continuing a long thread. Read `README.md` and this file, and go.

When working: filter command output rather than dumping it, scope page reads instead of pulling
whole DOM trees, and do not re-read files immediately after writing them.

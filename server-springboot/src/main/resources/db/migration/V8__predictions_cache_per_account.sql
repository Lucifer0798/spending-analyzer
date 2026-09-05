-- Predictions cache gains an account scope, so a forecast generated while looking at one
-- account is never shown while looking at another. The single row this table held before had
-- no account attached to it at all — there is no way to know in hindsight which account it was
-- for, and carrying it forward under a guessed scope would just be a different flavour of the
-- mismatch this migration exists to fix. It is dropped rather than migrated; regenerating a
-- forecast is one click, unlike the transactions and merchant memory earlier migrations took
-- care to preserve.
--
-- account_id uses 0 as the sentinel for "all accounts" rather than NULL: SQLite treats NULL as
-- distinct from every other NULL in a PRIMARY KEY / UNIQUE column, so two "all accounts" rows
-- would never conflict with each other and the upsert would insert a duplicate instead of
-- replacing the one cached forecast for that scope. Real accounts start at 1 (accounts.id is
-- AUTOINCREMENT), so 0 can never collide with one. No REFERENCES clause, matching every other
-- account_id column in this schema — foreign keys are not enforced here.

DROP TABLE predictions_cache;

CREATE TABLE predictions_cache (
  account_id INTEGER NOT NULL DEFAULT 0 PRIMARY KEY,
  payload TEXT NOT NULL,
  generated_at TEXT NOT NULL
);

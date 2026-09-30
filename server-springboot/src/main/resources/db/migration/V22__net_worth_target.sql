-- A single target for overall net worth -- at most one row at a time, enforced by the CHECK on
-- id. Unlike a savings goal, there's nothing to log contributions toward: net worth is already
-- derived from logged account balances, so a target is just a number (and optionally a date) to
-- compare the forecast's trend line against.
CREATE TABLE net_worth_target (
  id INTEGER PRIMARY KEY CHECK (id = 1),
  target_amount REAL NOT NULL CHECK (target_amount > 0),
  target_date TEXT,
  currency TEXT NOT NULL,
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

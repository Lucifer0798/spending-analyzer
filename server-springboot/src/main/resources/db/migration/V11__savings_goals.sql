-- Savings goals: a target amount (and optionally a date) with progress tracked from contributions
-- logged by hand, not derived from transactions. Unlike budgets, whose spend is inferred from
-- imports, this app has no notion of an account's actual balance to compute "how much has
-- actually been saved" from -- only categorized transaction activity -- so a goal's saved amount
-- is exactly what was logged toward it, nothing inferred.
--
-- goal_contributions.goal_id has no REFERENCES clause, matching every other id column in this
-- schema -- foreign keys are not enforced here (see V8's note). Deleting a goal deletes its
-- contributions explicitly in the repository layer instead of relying on cascade.

CREATE TABLE goals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  target_amount REAL NOT NULL CHECK (target_amount > 0),
  target_date TEXT,
  currency TEXT NOT NULL DEFAULT 'USD',
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- A contribution's amount can be negative -- money taken back out of a goal is still worth
-- recording, and the running total should reflect it rather than only ever going up.
CREATE TABLE goal_contributions (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  goal_id INTEGER NOT NULL,
  amount REAL NOT NULL CHECK (amount <> 0),
  date TEXT NOT NULL,
  note TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_goal_contributions_goal_id ON goal_contributions(goal_id);

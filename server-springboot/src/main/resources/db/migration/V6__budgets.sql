-- Budgets: a monthly spending target per category, compared against what was actually spent.

CREATE TABLE IF NOT EXISTS budgets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- Keyed by category name rather than id, matching transactions and merchant_categories.
  -- Renaming or deleting a category therefore has to cascade here too, which CategoryRepository
  -- does inside the same transaction as the rest of the rename.
  category TEXT NOT NULL UNIQUE,
  -- A zero or negative target is not a budget, and would make the percent-used calculation
  -- either meaningless or a division by zero.
  monthly_limit REAL NOT NULL CHECK (monthly_limit > 0),
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

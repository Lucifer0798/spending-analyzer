-- A short spending challenge: "no Dining & Coffee for two weeks" (limit_amount 0) or "under 200 on
-- Shopping this month". category NULL means all spending; account_id NULL means every account.
-- Progress -- spent so far, streak, whether it's broken -- is computed on read from transactions,
-- like budgets, so a late-imported statement updates it. Keyed by category name, like budgets, so
-- renaming or deleting a category cascades here (CategoryRepository).
CREATE TABLE challenges (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL,
  category TEXT,
  limit_amount REAL NOT NULL CHECK (limit_amount >= 0),
  start_date TEXT NOT NULL,
  end_date TEXT NOT NULL CHECK (end_date >= start_date),
  account_id INTEGER,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Multi-account support: transactions previously lived in one undifferentiated pool.

CREATE TABLE IF NOT EXISTS accounts (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  type TEXT NOT NULL CHECK (type IN ('checking', 'savings', 'credit_card', 'cash', 'investment', 'other')),
  archived INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Account 1 is the implicit home for anything imported before accounts existed,
-- and the fallback when an upload does not name an account.
INSERT INTO accounts (id, name, type) VALUES (1, 'Default', 'checking');

-- No REFERENCES clause: SQLite forbids adding a NOT NULL column with a foreign key
-- unless its default is NULL, and foreign keys are not enforced here anyway.
ALTER TABLE transactions ADD COLUMN account_id INTEGER NOT NULL DEFAULT 1;

CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_id);

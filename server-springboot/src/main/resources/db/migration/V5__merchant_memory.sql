-- Merchant memory: remember how a merchant was categorised so repeat imports do not
-- re-ask the model about a merchant it has already seen.

CREATE TABLE IF NOT EXISTS merchant_categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- Normalised merchant label (store numbers and order references stripped), so every
  -- "WHOLE FOODS MARKET #123" variant collapses onto one entry.
  merchant_key TEXT NOT NULL UNIQUE,
  category TEXT NOT NULL,
  -- 'user' entries are corrections and outrank 'ai' guesses: a later model answer must
  -- never silently overwrite a category the user fixed by hand.
  source TEXT NOT NULL CHECK (source IN ('ai', 'user')),
  hit_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX IF NOT EXISTS idx_merchant_categories_key ON merchant_categories(merchant_key);

-- Widen transactions.category_source to include 'cache', so a category applied from
-- merchant memory is distinguishable from one the model judged for this transaction.
-- SQLite cannot alter a CHECK constraint in place, so the table is rebuilt: create,
-- copy, drop, rename. Columns are listed explicitly rather than using SELECT * so the
-- copy does not depend on column order.
CREATE TABLE transactions_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  date TEXT NOT NULL,
  description TEXT NOT NULL,
  amount REAL NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('debit', 'credit')),
  category TEXT,
  category_source TEXT CHECK (category_source IN ('ai', 'user', 'import', 'cache')),
  upload_batch_id TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  account_id INTEGER NOT NULL DEFAULT 1
);

INSERT INTO transactions_new
  (id, date, description, amount, type, category, category_source, upload_batch_id, created_at, account_id)
SELECT
  id, date, description, amount, type, category, category_source, upload_batch_id, created_at, account_id
FROM transactions;

DROP TABLE transactions;

ALTER TABLE transactions_new RENAME TO transactions;

-- Dropping the table dropped its indexes; recreate every one from V1, V2 and V4.
CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date);
CREATE INDEX IF NOT EXISTS idx_transactions_category ON transactions(category);
CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(type);
CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_dedupe ON transactions(account_id, date, amount, type);

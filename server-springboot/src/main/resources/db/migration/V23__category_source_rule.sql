-- Categorization no longer calls a language model: anything merchant memory doesn't already know
-- is now matched by the app's own keyword rules. Widen transactions.category_source to include
-- 'rule' so those categories are labelled for what they are. 'ai' stays allowed -- rows the model
-- categorized before this change keep their honest label rather than being relabelled after the
-- fact.
--
-- SQLite cannot alter a CHECK constraint in place, so the table is rebuilt the same way V5 did:
-- create, copy, drop, rename. Columns are listed explicitly, including split_share and split_note
-- (added by ALTER TABLE since V5), so the copy doesn't depend on column order. Ids are copied as-is,
-- which is what keeps transaction_tags and transaction_receipts (no foreign keys) pointing at the
-- right rows.
CREATE TABLE transactions_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  date TEXT NOT NULL,
  description TEXT NOT NULL,
  amount REAL NOT NULL,
  type TEXT NOT NULL CHECK (type IN ('debit', 'credit')),
  category TEXT,
  category_source TEXT CHECK (category_source IN ('ai', 'user', 'import', 'cache', 'rule')),
  upload_batch_id TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  account_id INTEGER NOT NULL DEFAULT 1,
  split_share REAL,
  split_note TEXT
);

INSERT INTO transactions_new
  (id, date, description, amount, type, category, category_source, upload_batch_id, created_at,
   account_id, split_share, split_note)
SELECT
  id, date, description, amount, type, category, category_source, upload_batch_id, created_at,
  account_id, split_share, split_note
FROM transactions;

-- Carry the AUTOINCREMENT high-water mark across. Inserting explicit ids only advances the new
-- table's sequence to the highest id still present; if the newest transactions had been deleted,
-- their ids would otherwise be handed out again. Dropping the old table deletes its sequence row,
-- so this has to happen first; the rename below carries the row to the new name. The INSERT covers
-- an empty copy (every transaction deleted), which leaves the new table no sequence row to update.
INSERT INTO sqlite_sequence (name, seq)
SELECT 'transactions_new', seq FROM sqlite_sequence
 WHERE name = 'transactions'
   AND NOT EXISTS (SELECT 1 FROM sqlite_sequence WHERE name = 'transactions_new');

UPDATE sqlite_sequence
   SET seq = MAX(seq, COALESCE((SELECT seq FROM sqlite_sequence WHERE name = 'transactions'), 0))
 WHERE name = 'transactions_new';

DROP TABLE transactions;

ALTER TABLE transactions_new RENAME TO transactions;

-- Dropping the table dropped its indexes; recreate every one.
CREATE INDEX IF NOT EXISTS idx_transactions_date ON transactions(date);
CREATE INDEX IF NOT EXISTS idx_transactions_category ON transactions(category);
CREATE INDEX IF NOT EXISTS idx_transactions_type ON transactions(type);
CREATE INDEX IF NOT EXISTS idx_transactions_account ON transactions(account_id);
CREATE INDEX IF NOT EXISTS idx_transactions_dedupe ON transactions(account_id, date, amount, type);

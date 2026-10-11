-- Money moved between two of your own accounts shows up twice: a debit in one, a credit in the
-- other. Unless both land in a transfer category, the debit counts as spending and the credit can
-- count as income. A row here pairs the two halves.
--
-- 'confirmed' pairs were moved into a transfer category; the prev_* columns remember what each side
-- was before, so the pairing can be undone. 'dismissed' pairs were judged "not a transfer" and only
-- exist so the same suggestion isn't offered again -- nothing about the transactions changed.
--
-- No foreign keys, like transaction_tags: deleting a transaction deletes its pairs explicitly.
CREATE TABLE transfer_pairs (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  debit_id INTEGER NOT NULL,
  credit_id INTEGER NOT NULL,
  status TEXT NOT NULL CHECK (status IN ('confirmed', 'dismissed')),
  debit_prev_category TEXT,
  debit_prev_source TEXT,
  credit_prev_category TEXT,
  credit_prev_source TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE (debit_id, credit_id)
);

-- A transaction is half of at most one confirmed transfer. Dismissals don't count: ruling out one
-- partner says nothing about another.
CREATE UNIQUE INDEX idx_transfer_pairs_confirmed_debit ON transfer_pairs(debit_id) WHERE status = 'confirmed';
CREATE UNIQUE INDEX idx_transfer_pairs_confirmed_credit ON transfer_pairs(credit_id) WHERE status = 'confirmed';
CREATE INDEX idx_transfer_pairs_credit ON transfer_pairs(credit_id);

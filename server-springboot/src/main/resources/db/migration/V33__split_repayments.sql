-- Money paid back toward a split charge. A split (V17) says what share of a charge is yours; the
-- rest is owed to you by whoever split_note names. A row here records some of that coming back:
-- how much and when, and -- when it arrived as a transaction (a Zelle or Venmo credit) -- which one.
--
-- A linked credit is the others' money, not income, so it's left out of income totals while the
-- charge it repays is still split. Clearing the split (or undoing one) needs no clean-up: the rows
-- simply stop counting until the charge is split again.
--
-- No foreign keys, like transfer_pairs: deleting a transaction deletes its rows explicitly.
CREATE TABLE split_repayments (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  transaction_id INTEGER NOT NULL,
  amount REAL NOT NULL CHECK (amount > 0),
  repaid_on TEXT NOT NULL,
  repayment_id INTEGER,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

CREATE INDEX idx_split_repayments_transaction ON split_repayments(transaction_id);
-- A credit repays one split.
CREATE UNIQUE INDEX idx_split_repayments_repayment ON split_repayments(repayment_id) WHERE repayment_id IS NOT NULL;

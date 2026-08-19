-- Supports duplicate detection on upload, which counts existing rows matching
-- (account, date, description, amount, type). Deliberately NOT unique: two
-- identical coffees on the same day are legitimate, so dedup compares counts
-- rather than rejecting exact matches.

CREATE INDEX IF NOT EXISTS idx_transactions_dedupe
  ON transactions(account_id, date, amount, type);

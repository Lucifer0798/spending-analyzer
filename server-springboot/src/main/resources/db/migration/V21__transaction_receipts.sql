-- A transaction can carry at most one receipt image or PDF, stored as a BLOB in this same
-- database rather than a directory on disk -- this app already promises "everything in one
-- SQLite file, no hosted database," and a receipts folder would need its own volume mount and
-- its own backup story that keeping the bytes here avoids entirely.
--
-- Kept in its own table, not a column on transactions, so the transactions list's ordinary
-- SELECT never has to pull receipt bytes along with every row just to answer "does this one have
-- a receipt" -- that's a lightweight EXISTS/IN check instead, the same shape the tag filter's own
-- EXISTS subquery already uses.
CREATE TABLE transaction_receipts (
  transaction_id INTEGER PRIMARY KEY,
  filename TEXT NOT NULL,
  content_type TEXT NOT NULL,
  data BLOB NOT NULL,
  uploaded_at TEXT NOT NULL DEFAULT (datetime('now'))
);

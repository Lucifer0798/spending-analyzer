-- A group of look-alike charges the user said is not a double charge -- two real coffees on the
-- same day, two tickets bought together. Keyed by the group's transaction ids, sorted and
-- comma-joined, so the same group is never offered again; if another matching charge joins it, the
-- group is different and is offered afresh.
--
-- No foreign keys, like transaction_tags: a dismissal naming a deleted transaction just never
-- matches a group again.
CREATE TABLE duplicate_dismissals (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  transaction_ids TEXT NOT NULL UNIQUE,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- Free-form tags for cross-cutting filtering a fixed category can't express -- "business trip" or
-- "reimbursable" cuts across whatever categories those transactions already have. A transaction
-- can carry any number of tags, unlike its single category.
--
-- name is COLLATE NOCASE so "Business Trip" and "business trip" are the same tag: the UNIQUE
-- index, ON CONFLICT, and every WHERE name = comparison all inherit that collation from the
-- column, so adding an already-existing tag (by any casing) is a no-op rather than a duplicate.
CREATE TABLE tags (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

-- No REFERENCES clause, matching every other id column in this schema -- foreign keys are not
-- enforced here. No surrogate id either: (transaction_id, tag_id) is the natural key, and tagging
-- an already-tagged transaction is meant to be a harmless no-op via ON CONFLICT.
CREATE TABLE transaction_tags (
  transaction_id INTEGER NOT NULL,
  tag_id INTEGER NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  PRIMARY KEY (transaction_id, tag_id)
);

CREATE INDEX idx_transaction_tags_tag_id ON transaction_tags(tag_id);

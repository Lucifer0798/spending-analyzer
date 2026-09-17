-- Saved combinations of the Transactions page's own filters (category, tag, search) plus the
-- account/date-range scope from the header, so a view like "Business trips" can be reapplied in
-- one click instead of being rebuilt by hand every time.
--
-- name is COLLATE NOCASE and UNIQUE, matching tags.name: saving under an existing name replaces
-- it (an upsert) rather than creating a near-duplicate, the same "second entry under the same
-- identity is a correction" idiom account_balances follows for (account_id, date).
--
-- account_id has no REFERENCES clause, matching every other id column in this schema -- foreign
-- keys are not enforced here. NULL means "all accounts", the same as the accountId query
-- parameter everywhere else in the API; deleting that account clears the reference back to NULL
-- rather than leaving the preset pointing at nothing (see AccountController.delete).
CREATE TABLE filter_presets (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE COLLATE NOCASE,
  category TEXT,
  tag TEXT,
  search TEXT,
  account_id INTEGER,
  date_from TEXT,
  date_to TEXT,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

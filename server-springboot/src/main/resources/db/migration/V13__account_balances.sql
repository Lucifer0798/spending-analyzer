-- Net worth tracking: a manually-logged balance per account, since -- like savings goals -- this
-- app has no way to derive an account's actual balance from categorized transactions alone.
--
-- balance is the account's literal contribution to net worth: positive for an asset, negative for
-- a liability. A credit card with an outstanding bill is logged as a negative number; there is no
-- automatic sign-flip based on account type, the same "the number means what it says" choice
-- goal_contributions makes for a withdrawal.
--
-- UNIQUE(account_id, date) makes logging a second balance for the same account on the same day a
-- correction (upsert), not a duplicate fact -- there's only one true balance "as of" a given day.
-- No REFERENCES clause, matching every other id column in this schema -- foreign keys are not
-- enforced here.
CREATE TABLE account_balances (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  account_id INTEGER NOT NULL,
  date TEXT NOT NULL,
  balance REAL NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  UNIQUE(account_id, date)
);

CREATE INDEX idx_account_balances_account_id ON account_balances(account_id);

-- Recurring detection is fully derived from transactions every time it runs -- there is no row
-- of its own to attach a user's intent to. This table gives merchants exactly one: "cancel"
-- records that you're in the process of cancelling something, a reminder that outlives the
-- decision until the charges actually stop; "exclude" records that a coincidentally regular
-- pattern was never really a subscription, and hides it from the list for good. Keyed by
-- merchant_key, the same identity space merchant memory uses, since both answer "which merchant
-- is this" the same way and share the normalization that produces the key.
CREATE TABLE recurring_overrides (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  merchant_key TEXT NOT NULL UNIQUE,
  action TEXT NOT NULL CHECK (action IN ('cancel', 'exclude')),
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

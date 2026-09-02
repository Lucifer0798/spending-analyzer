-- Merchant memory gains an amount band, so one merchant can map to different categories
-- depending on how much was spent. The case this exists for: a merchant whose description is
-- identical whatever you bought — a £9.99 subscription and a £60 order both arriving as
-- "AMAZON.COM" — where the amount is the only signal left to tell them apart.
--
-- (Descriptions that differ, like "AMAZON PRIME" vs "AMAZON.COM", already normalise to separate
-- merchant keys and needed nothing.)

-- Bounds are stored, not NULL-able, because SQLite treats NULLs as distinct in a UNIQUE index:
-- with NULL bounds, two catch-all rows for the same merchant would not conflict, and the upsert
-- in `remember` would silently insert duplicates instead of updating.
--
-- 1e12 stands in for "no upper bound". No personal transaction approaches it, and using a real
-- number keeps both the uniqueness check and the range query ordinary comparisons.

CREATE TABLE merchant_categories_new (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  -- No longer UNIQUE on its own: a merchant may now have several rows, one per band.
  merchant_key TEXT NOT NULL,
  category TEXT NOT NULL,
  -- Inclusive lower bound, exclusive upper, so adjacent bands meet without overlapping.
  min_amount REAL NOT NULL DEFAULT 0,
  max_amount REAL NOT NULL DEFAULT 1000000000000,
  source TEXT NOT NULL CHECK (source IN ('ai', 'user')),
  hit_count INTEGER NOT NULL DEFAULT 0,
  created_at TEXT NOT NULL DEFAULT (datetime('now')),
  updated_at TEXT NOT NULL DEFAULT (datetime('now')),
  -- Amounts are stored unsigned, and an empty or backwards band could never match anything.
  CHECK (min_amount >= 0 AND min_amount < max_amount),
  UNIQUE (merchant_key, min_amount, max_amount)
);

-- Everything remembered so far becomes a catch-all covering every amount, which is exactly
-- what it meant before bands existed.
INSERT INTO merchant_categories_new
  (id, merchant_key, category, min_amount, max_amount, source, hit_count, created_at, updated_at)
SELECT
  id, merchant_key, category, 0, 1000000000000, source, hit_count, created_at, updated_at
FROM merchant_categories;

DROP TABLE merchant_categories;

ALTER TABLE merchant_categories_new RENAME TO merchant_categories;

-- Dropping the table dropped its index; lookups are still by merchant key.
CREATE INDEX IF NOT EXISTS idx_merchant_categories_key ON merchant_categories(merchant_key);

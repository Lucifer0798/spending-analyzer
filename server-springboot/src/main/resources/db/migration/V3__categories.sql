-- Categories move from a hardcoded Java list into the database so users can add
-- their own. The is_income / is_transfer flags replace hardcoded name comparisons
-- in the stats queries, so a custom category can also be excluded from spend totals.

CREATE TABLE IF NOT EXISTS categories (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  name TEXT NOT NULL UNIQUE,
  is_builtin INTEGER NOT NULL DEFAULT 0,
  is_income INTEGER NOT NULL DEFAULT 0,
  is_transfer INTEGER NOT NULL DEFAULT 0,
  sort_order INTEGER NOT NULL DEFAULT 100,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

INSERT INTO categories (name, is_builtin, is_income, is_transfer, sort_order) VALUES
  ('Groceries',       1, 0, 0,  1),
  ('Dining & Coffee', 1, 0, 0,  2),
  ('Transportation',  1, 0, 0,  3),
  ('Shopping',        1, 0, 0,  4),
  ('Entertainment',   1, 0, 0,  5),
  ('Utilities',       1, 0, 0,  6),
  ('Rent/Mortgage',   1, 0, 0,  7),
  ('Healthcare',      1, 0, 0,  8),
  ('Subscriptions',   1, 0, 0,  9),
  ('Travel',          1, 0, 0, 10),
  ('Fees & Charges',  1, 0, 0, 11),
  ('Personal Care',   1, 0, 0, 12),
  ('Education',       1, 0, 0, 13),
  ('Income',          1, 1, 0, 14),
  ('Transfer',        1, 0, 1, 15),
  ('Other',           1, 0, 0, 16);

CREATE INDEX IF NOT EXISTS idx_categories_sort ON categories(sort_order, name);

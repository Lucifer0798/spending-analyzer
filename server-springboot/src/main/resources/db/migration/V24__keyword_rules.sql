-- The user's own categorization rules: "a description containing this keyword (as a whole word)
-- goes in this category". Checked after merchant memory (an exact merchant the user corrected is
-- more specific than any keyword) and before the built-in rules (the user's word outranks the
-- app's guess). Like every other table, category is stored by name; renaming or deleting a
-- category cascades here in CategoryRepository, the same as for merchant memory and budgets.
--
-- keyword is unique case-insensitively, so "Vet" and "VET" can't become two rules that disagree.
CREATE TABLE keyword_rules (
  id INTEGER PRIMARY KEY AUTOINCREMENT,
  keyword TEXT NOT NULL UNIQUE COLLATE NOCASE,
  category TEXT NOT NULL,
  created_at TEXT NOT NULL DEFAULT (datetime('now'))
);

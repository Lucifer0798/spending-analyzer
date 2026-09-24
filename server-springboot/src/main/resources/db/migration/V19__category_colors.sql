-- An optional display color per category, used consistently across the dashboard charts, the
-- transaction list, and budgets, instead of every chart picking its own colors independently.
-- Nullable, like group_name: a category with no color keeps rendering exactly as it did before
-- this existed (a single flat chart color), so nobody who never opens the color picker sees any
-- change at all.
ALTER TABLE categories ADD COLUMN color TEXT;

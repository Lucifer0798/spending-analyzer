-- Optional label for rolling several categories up under one coarser bucket on the dashboard
-- and in exports (e.g. "Food" over Groceries + Dining & Coffee). NULL means ungrouped -- a
-- category with no group is its own group of one for rollup purposes. Named group_name, not
-- group, since GROUP is a reserved SQL keyword and would need quoting at every reference.
ALTER TABLE categories ADD COLUMN group_name TEXT;

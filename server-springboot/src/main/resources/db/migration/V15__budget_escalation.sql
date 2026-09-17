-- Optional auto-increase schedule on a budget: the stored monthly_limit stays the original
-- base the user set, and BudgetService derives the limit actually in effect for whichever
-- month is being measured. All four columns are NULL together on every existing row, which
-- means "no schedule" -- exactly today's behaviour, unchanged.

ALTER TABLE budgets ADD COLUMN escalation_type TEXT;
ALTER TABLE budgets ADD COLUMN escalation_value REAL;
ALTER TABLE budgets ADD COLUMN escalation_frequency_months INTEGER;
ALTER TABLE budgets ADD COLUMN escalation_start_month TEXT;

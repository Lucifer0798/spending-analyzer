-- Optional envelope/rollover on a budget: when set, unused budget from a month carries into the
-- next month's effective limit (and overspend eats into it) starting from this month. NULL means
-- disabled -- exactly today's behaviour, unchanged. Unlike escalation, there is no separate
-- value/frequency to configure: the server manages this column entirely, preserving it across a
-- resave so re-enabling never loses months of already-accumulated carry-in.
ALTER TABLE budgets ADD COLUMN rollover_start_month TEXT;

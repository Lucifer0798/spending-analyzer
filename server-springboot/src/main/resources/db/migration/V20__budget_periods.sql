-- A budget's period: how often its target renews and what date range its spend is measured
-- over. Every existing row is "monthly", exactly today's behaviour, unchanged. Escalation and
-- rollover stay monthly-only -- both are defined in terms of a whole number of months, and
-- neither was asked for on a weekly grocery allowance or a quarterly insurance premium, which
-- just want a plain target measured over a different, fixed-length window.
ALTER TABLE budgets ADD COLUMN period TEXT NOT NULL DEFAULT 'monthly' CHECK (period IN ('weekly', 'monthly', 'quarterly'));

import { useEffect, useState } from "react";
import { fetchBudgets, fetchRecurringIncome } from "../api";
import type { BudgetSummary, DateRangeValue, RecurringIncomeResponse } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

function monthLabel(month: string) {
  const [year, m] = month.split("-");
  const date = new Date(Number(year), Number(m) - 1, 1);
  return date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

/**
 * How much of a month's budgeted spend is covered by income, so it's visible at a glance
 * whether targets add up to less than what actually comes in. Composed entirely client-side
 * from two endpoints that already exist -- {@code /budgets} and {@code /recurring-income} --
 * rather than a new backend aggregate, the same way the dashboard's category-group rollup reuses
 * {@code /summary} and {@code /categories} instead of a third query.
 *
 * Both calls are pinned to the exact same month: `/budgets` resolves its own anchor first (the
 * newest month with spend data), and that resolved month is then passed explicitly to
 * `/recurring-income` rather than letting it independently resolve the newest month with *income*
 * data, which can easily be a different month once one side of the ledger is imported before the
 * other.
 */
export function BudgetVsIncomeCard({ accountId, range }: Props) {
  const [budgets, setBudgets] = useState<BudgetSummary | null>(null);
  const [income, setIncome] = useState<RecurringIncomeResponse | null>(null);

  // Same reduction BudgetsCard uses: budgets are measured by month, so a range collapses to the
  // month it ends in, leaving it undefined to let the server pick the newest month with data.
  const month = range.to ? range.to.slice(0, 7) : undefined;

  useEffect(() => {
    // Resetting first: switching accounts mid-flight should never show one account's budgets
    // next to another's income while the second fetch is still in flight.
    // oxlint-disable-next-line react/set-state-in-effect
    setBudgets(null);
    setIncome(null);

    fetchBudgets(accountId, month).then((b) => {
      setBudgets(b);
      if (b.currency === null || b.totalLimit <= 0) return;
      // Pinned to the budgets' own resolved month -- see the comment above this component.
      fetchRecurringIncome(accountId, undefined, b.month).then(setIncome).catch(() => setIncome(null));
    }).catch(() => setBudgets(null));
  }, [accountId, month]);

  // Renders nothing until there's a monthly budget to compare against, so the dashboard is
  // unchanged for anyone not using budgets -- the same convention BudgetsCard itself follows.
  if (!budgets || budgets.currency === null || budgets.totalLimit <= 0 || !income || income.mixedCurrencies) {
    return null;
  }

  const cur = budgets.currency;
  const allocatedPercent = income.actualThisMonth > 0 ? (budgets.totalLimit / income.actualThisMonth) * 100 : null;
  const leftover = income.actualThisMonth - budgets.totalLimit;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <h2 className="mb-4 text-sm font-semibold text-slate-700 dark:text-slate-300">
        Budget vs. income · {monthLabel(budgets.month)}
      </h2>

      <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Budgeted</p>
          <p className="mt-1 text-xl font-semibold text-slate-900 dark:text-slate-100">
            {currency(budgets.totalLimit, 0, cur)}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Actual income this month</p>
          <p className="mt-1 text-xl font-semibold text-slate-900 dark:text-slate-100">
            {currency(income.actualThisMonth, 0, cur)}
          </p>
        </div>
        <div>
          <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Recurring income</p>
          <p className="mt-1 text-xl font-semibold text-slate-900 dark:text-slate-100">
            {currency(income.totalMonthlyEquivalent, 0, cur)}
          </p>
          <p className="mt-0.5 text-[11px] text-slate-400">average, not this month specifically</p>
        </div>
      </div>

      <p className={`mt-4 text-sm ${leftover >= 0 ? "text-emerald-600 dark:text-emerald-400" : "text-red-600 dark:text-red-400"}`}>
        {income.actualThisMonth <= 0 ? (
          "No income recorded this month, so there's nothing yet to measure budgets against."
        ) : leftover >= 0 ? (
          <>
            {currency(leftover, 0, cur)} of this month's actual income is left unbudgeted
            {allocatedPercent !== null && ` (${Math.round(allocatedPercent)}% allocated)`}.
          </>
        ) : (
          <>
            Budgets add up to {currency(Math.abs(leftover), 0, cur)} more than this month's actual
            income{allocatedPercent !== null && ` (${Math.round(allocatedPercent)}% allocated)`}.
          </>
        )}
      </p>
    </div>
  );
}

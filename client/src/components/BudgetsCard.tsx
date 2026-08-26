import { useEffect, useState } from "react";
import { fetchBudgets } from "../api";
import type { BudgetProgress, BudgetStatus, BudgetSummary, DateRangeValue } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

const STATUS_COLOR: Record<BudgetStatus, string> = {
  under: "#0ca30c",
  near: "#fab219",
  over: "#d03b3b",
};

function monthLabel(month: string) {
  const [year, m] = month.split("-");
  const date = new Date(Number(year), Number(m) - 1, 1);
  return date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

function BudgetRow({ budget }: { budget: BudgetProgress }) {
  const color = STATUS_COLOR[budget.status];
  // The bar caps at 100% so it cannot overflow its track; the number beside it carries the
  // overspend, which is the part worth reading precisely anyway.
  const filled = Math.min(budget.percentUsed, 100);

  return (
    <div>
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-slate-800 dark:text-slate-200">{budget.category}</span>
        <span className="text-slate-600 dark:text-slate-400">
          {currency(budget.spent)} of {currency(budget.monthlyLimit)}
        </span>
      </div>

      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
        <div
          className="h-full rounded-full transition-[width]"
          style={{ width: `${filled}%`, backgroundColor: color }}
        />
      </div>

      <p className="mt-1 text-xs" style={{ color }}>
        {budget.remaining >= 0
          ? `${currency(budget.remaining)} left · ${Math.round(budget.percentUsed)}% used`
          : `${currency(Math.abs(budget.remaining))} over budget`}
      </p>
    </div>
  );
}

export function BudgetsCard({ accountId, range }: Props) {
  const [summary, setSummary] = useState<BudgetSummary | null>(null);

  // Budgets are monthly, so a range is reduced to the month it ends in. Leaving it undefined
  // lets the server pick the newest month on record, which beats defaulting to a calendar
  // month that has no imported data in it yet.
  const month = range.to ? range.to.slice(0, 7) : undefined;

  useEffect(() => {
    fetchBudgets(accountId, month).then(setSummary).catch(() => setSummary(null));
  }, [accountId, month]);

  if (!summary || summary.budgets.length === 0) return null;

  const overall = summary.totalLimit === 0 ? 0 : (summary.totalSpent / summary.totalLimit) * 100;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          Budgets · {monthLabel(summary.month)}
        </h2>
        <span className="text-xs text-slate-500">
          {currency(summary.totalSpent)} of {currency(summary.totalLimit)} · {Math.round(overall)}% used
        </span>
      </div>

      <div className="space-y-4">
        {summary.budgets.map((b) => (
          <BudgetRow key={b.id} budget={b} />
        ))}
      </div>
    </div>
  );
}

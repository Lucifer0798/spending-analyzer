import { useEffect, useState } from "react";
import { fetchBudgets } from "../api";
import type { BudgetProgress, BudgetStatus, BudgetSummary, DateRangeValue } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
  /** A category with no entry here just renders without a swatch. */
  colorByCategory?: Record<string, string>;
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

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00`).toLocaleDateString(undefined, { month: "short", day: "numeric" });
}

/** "" for a monthly budget, since the card's own heading already names the measured month --
 *  a weekly or quarterly one gets its own dates, since those don't line up with that heading. */
function periodNote(budget: BudgetProgress) {
  if (budget.period === "monthly") return "";
  return ` · ${shortDate(budget.periodStart)}–${shortDate(budget.periodEnd)}`;
}

function BudgetRow({
  budget,
  currencyCode,
  swatchColor,
}: {
  budget: BudgetProgress;
  currencyCode: string;
  swatchColor?: string;
}) {
  const color = STATUS_COLOR[budget.status];
  // The bar caps at 100% so it cannot overflow its track; the number beside it carries the
  // overspend, which is the part worth reading precisely anyway.
  const filled = Math.min(budget.percentUsed, 100);

  return (
    <div>
      <div className="flex items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-slate-800 dark:text-slate-200">
          {swatchColor && (
            <span
              className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full align-middle"
              style={{ backgroundColor: swatchColor }}
            />
          )}
          {budget.category}
          {periodNote(budget) && <span className="font-normal text-slate-400">{periodNote(budget)}</span>}
        </span>
        <span className="text-slate-600 dark:text-slate-400">
          {currency(budget.spent, 0, currencyCode)} of {currency(budget.monthlyLimit, 0, currencyCode)}
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
          ? `${currency(budget.remaining, 0, currencyCode)} left · ${Math.round(budget.percentUsed)}% used`
          : `${currency(Math.abs(budget.remaining), 0, currencyCode)} over budget`}
      </p>

      {budget.escalationType && (
        <p className="mt-0.5 text-[11px] text-slate-400">
          auto-increasing {budget.escalationType === "percent"
            ? `${budget.escalationValue}%`
            : currency(budget.escalationValue ?? 0, 0, currencyCode)}{" "}
          every {budget.escalationFrequencyMonths === 1 ? "month"
            : budget.escalationFrequencyMonths === 12 ? "year"
            : `${budget.escalationFrequencyMonths} months`}
        </p>
      )}

      {budget.rolloverStartMonth && budget.rolloverCarryIn !== 0 && (
        <p className="mt-0.5 text-[11px] text-slate-400">
          {budget.rolloverCarryIn >= 0 ? "+" : "−"}
          {currency(Math.abs(budget.rolloverCarryIn), 0, currencyCode)} rolled over from last month
        </p>
      )}
    </div>
  );
}

export function BudgetsCard({ accountId, range, colorByCategory = {} }: Props) {
  const [summary, setSummary] = useState<BudgetSummary | null>(null);

  // Every budget shares one anchor month -- a weekly or quarterly budget's own range is derived
  // from it server-side, so there's still only one "which month" control here. Leaving it
  // undefined lets the server pick the newest month on record, which beats defaulting to a
  // calendar month that has no imported data in it yet.
  const month = range.to ? range.to.slice(0, 7) : undefined;

  useEffect(() => {
    fetchBudgets(accountId, month).then(setSummary).catch(() => setSummary(null));
  }, [accountId, month]);

  // Also covers the mixed-currency case: the server returns an empty budgets list rather than
  // a total that would mix currencies, so this renders nothing rather than a wrong number.
  if (!summary || summary.budgets.length === 0 || summary.currency === null) return null;
  const cur = summary.currency;

  const overall = summary.totalLimit === 0 ? 0 : (summary.totalSpent / summary.totalLimit) * 100;
  const hasNonMonthly = summary.budgets.some((b) => b.period !== "monthly");

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-4 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          Budgets · {monthLabel(summary.month)}
        </h2>
        <span className="text-xs text-slate-500">
          {currency(summary.totalSpent, 0, cur)} of {currency(summary.totalLimit, 0, cur)} · {Math.round(overall)}% used
        </span>
      </div>

      {hasNonMonthly && (
        <p className="mb-3 -mt-2 text-[11px] text-slate-400">
          Weekly and quarterly targets aren't the same unit as a month, so they're left out of the
          total above -- each still shows its own progress below.
        </p>
      )}

      <div className="space-y-4">
        {summary.budgets.map((b) => (
          <BudgetRow key={b.id} budget={b} currencyCode={cur} swatchColor={colorByCategory[b.category]} />
        ))}
      </div>
    </div>
  );
}

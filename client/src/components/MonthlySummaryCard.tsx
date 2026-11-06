import { useEffect, useState } from "react";
import { fetchMonthlySummary } from "../api";
import type { DateRangeValue, MonthlySummary } from "../types";
import { currency } from "../format";
import { dayMonth, monthName } from "../dates";

interface Props {
  accountId: number | null;
  /** "Look at October": narrows the page's date filter to that month. */
  onOpenRange: (range: DateRangeValue) => void;
}

const DISMISSED_KEY = "monthly-summary-dismissed";

/** Which month was last dismissed. Only a convenience: without storage the card just comes back. */
function readDismissed() {
  try {
    return localStorage.getItem(DISMISSED_KEY);
  } catch {
    return null;
  }
}

function writeDismissed(month: string) {
  try {
    localStorage.setItem(DISMISSED_KEY, month);
  } catch {
    // Private window or blocked storage: dismissed for this visit only.
  }
}

/**
 * "How October went": the last complete month against the one before, its biggest changes, and the
 * budgets and challenges it settled. Stays until dismissed, and comes back when the next month ends.
 * Renders nothing without spend that month, or across mixed currencies.
 */
export function MonthlySummaryCard({ accountId, onOpenRange }: Props) {
  const [summary, setSummary] = useState<MonthlySummary | null>(null);
  const [dismissed, setDismissed] = useState(readDismissed);

  useEffect(() => {
    fetchMonthlySummary(accountId).then(setSummary).catch(() => setSummary(null));
  }, [accountId]);

  if (!summary?.available || !summary.month || summary.month === dismissed) return null;

  const cur = summary.currency ?? "USD";
  const month = monthName(summary.month);
  const before = summary.previousMonth ? monthName(summary.previousMonth) : "";
  const money = (n: number) => currency(n, 0, cur);
  const change = summary.change;
  const percent = summary.changePercent === null ? "" : ` (${Math.abs(Math.round(summary.changePercent))}%)`;

  return (
    <section className="mb-6 rounded-lg border border-indigo-200 bg-indigo-50/50 p-4 dark:border-indigo-900/50 dark:bg-indigo-950/20">
      <div className="flex items-start justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-800 dark:text-slate-200">How {month} went</h2>
        <button
          onClick={() => {
            writeDismissed(summary.month!);
            setDismissed(summary.month);
          }}
          aria-label={`Dismiss the ${month} summary`}
          title="Dismiss until next month"
          className="rounded px-1.5 text-slate-500 hover:bg-white hover:text-slate-800 dark:hover:bg-slate-800 dark:hover:text-slate-200"
        >
          ×
        </button>
      </div>

      <p className="mt-1 text-sm text-slate-800 dark:text-slate-200">
        <span className="text-lg font-semibold">{money(summary.total)}</span> spent
        {change === null ? (
          ""
        ) : change === 0 ? (
          <span className="text-slate-500">, the same as {before}</span>
        ) : (
          <span className={change < 0 ? "text-emerald-700 dark:text-emerald-400" : "text-amber-700 dark:text-amber-400"}>
            {" "}
            — {money(Math.abs(change))}
            {percent} {change < 0 ? "less" : "more"} than {before}
          </span>
        )}
      </p>

      <ul className="mt-2 space-y-0.5 text-sm text-slate-700 dark:text-slate-300">
        {summary.biggestRise && (
          <li>
            Biggest rise: <strong className="font-medium">{summary.biggestRise.category}</strong>, up{" "}
            {money(summary.biggestRise.change)} to {money(summary.biggestRise.total)}
          </li>
        )}
        {summary.biggestFall && (
          <li>
            Biggest drop: <strong className="font-medium">{summary.biggestFall.category}</strong>, down{" "}
            {money(-summary.biggestFall.change)} to {money(summary.biggestFall.total)}
          </li>
        )}
        {summary.budgetsTotal > 0 && (
          <li>
            Budgets: {summary.budgetsKept} of {summary.budgetsTotal} kept
            {summary.budgetsOver.length > 0 && ` — over on ${summary.budgetsOver.join(", ")}`}
          </li>
        )}
        {summary.challengesKept + summary.challengesBroken > 0 && (
          <li>
            Challenges: {summary.challengesKept} kept, {summary.challengesBroken} broken
          </li>
        )}
      </ul>

      {(summary.newestTransaction || summary.dataStarts) && (
        <p className="mt-2 text-xs text-amber-700 dark:text-amber-400">
          {summary.newestTransaction &&
            `Newest transaction is from ${dayMonth(summary.newestTransaction)}, so the rest of ${month} may not be imported yet. `}
          {summary.dataStarts && `Your transactions start on ${dayMonth(summary.dataStarts)}, so ${before} may read low.`}
        </p>
      )}

      <button
        onClick={() => onOpenRange({ from: summary.monthStart!, to: summary.monthEnd! })}
        className="mt-2 text-xs font-medium text-indigo-600 hover:underline dark:text-indigo-400"
      >
        Look at {month}
      </button>
    </section>
  );
}

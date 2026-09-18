import { useEffect, useMemo, useState } from "react";
import { fetchComparison } from "../api";
import type { CategoryComparison, ComparisonResponse, DateRangeValue, PeriodComparison } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

const GOOD = "#0ca30c";
const BAD = "#d03b3b";
const MUTED_TEXT = "text-slate-500";

/** How many category rows to show before collapsing the rest into a "N more" note -- enough to
 *  cover the interesting ends of the sorted list without turning into another full table. */
const VISIBLE_CATEGORIES = 6;

function formatDate(iso: string): string {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

function ChangeBadge({ amount, percent, currencyCode }: { amount: number; percent: number | null; currencyCode: string }) {
  // Spending more is the "bad" direction here, the reverse of a trend chart where up is good.
  const color = amount > 0 ? BAD : amount < 0 ? GOOD : undefined;
  const arrow = amount > 0 ? "↑" : amount < 0 ? "↓" : "→";
  return (
    <span className="text-sm font-semibold" style={{ color }}>
      {arrow} {currency(Math.abs(amount), 0, currencyCode)}
      {percent !== null && <span className="ml-1 font-normal opacity-80">({Math.abs(percent).toFixed(0)}%)</span>}
    </span>
  );
}

function CategoryRow({ category, currencyCode }: { category: CategoryComparison; currencyCode: string }) {
  return (
    <div className="flex items-center justify-between gap-2 py-1 text-sm">
      <span className="text-slate-700 dark:text-slate-300">{category.category}</span>
      <ChangeBadge amount={category.changeAmount} percent={category.changePercent} currencyCode={currencyCode} />
    </div>
  );
}

export function ComparisonCard({ accountId, range }: Props) {
  const [response, setResponse] = useState<ComparisonResponse | null>(null);
  // Only the fields the two date inputs below actually write to -- null until the user opts in,
  // so "not customizing" and "customizing but only one date typed so far" stay distinguishable.
  const [customFrom, setCustomFrom] = useState<string | null>(null);
  const [customTo, setCustomTo] = useState<string | null>(null);
  const [customizing, setCustomizing] = useState(false);

  // Only fires the custom request once both dates are actually filled in -- a half-open pair
  // would just come back "not applicable" anyway, and there's no reason to ask mid-edit. Memoized
  // so its identity only changes when the values do, not on every render -- a plain object
  // literal here would make the effect below refetch constantly regardless of the dep array.
  const compareAgainst = useMemo(
    () => (customizing && customFrom && customTo ? { from: customFrom, to: customTo } : null),
    [customizing, customFrom, customTo]
  );

  useEffect(() => {
    fetchComparison(accountId, range, compareAgainst).then(setResponse).catch(() => setResponse(null));
  }, [accountId, range, compareAgainst]);

  const resetCustom = () => {
    setCustomizing(false);
    setCustomFrom(null);
    setCustomTo(null);
  };

  const applicable = response?.applicable && response.comparison && response.currency;

  // Nothing to show for "all time" or a half-open filter -- there is no defined-length period
  // to mirror, and the comparison endpoint says so explicitly rather than this guessing. Also
  // nothing to show once "all accounts" spans more than one currency, for the same reason. Once
  // the card is already showing, though, and the user opts into a custom range, it stays up
  // through a half-typed pick rather than vanishing mid-edit -- `customizing` can only become
  // true by clicking a button that only exists on an already-visible card.
  if (!applicable && !customizing) return null;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
          {applicable && response!.comparison!.custom ? "Custom comparison" : "Compared to the previous period"}
        </h2>
        {customizing ? (
          <span className="flex items-center gap-1 text-xs">
            <input
              type="date"
              value={customFrom ?? ""}
              onChange={(e) => setCustomFrom(e.target.value || null)}
              className="rounded-md border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
            />
            <span className={MUTED_TEXT}>to</span>
            <input
              type="date"
              value={customTo ?? ""}
              onChange={(e) => setCustomTo(e.target.value || null)}
              className="rounded-md border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
            />
            <button onClick={resetCustom} className="ml-1 text-indigo-600 hover:underline dark:text-indigo-400">
              Use default
            </button>
          </span>
        ) : (
          <button
            onClick={() => setCustomizing(true)}
            className="text-xs text-indigo-600 hover:underline dark:text-indigo-400"
          >
            Compare to a custom range…
          </button>
        )}
      </div>

      {!applicable ? (
        <p className={`text-sm ${MUTED_TEXT}`}>
          {customizing
            ? "Pick both dates above to compare against that range."
            : "Nothing to compare -- pick a well-defined date range above."}
        </p>
      ) : (
        <ComparisonBody comparison={response!.comparison!} currencyCode={response!.currency!} />
      )}
    </div>
  );
}

function ComparisonBody({ comparison, currencyCode }: { comparison: PeriodComparison; currencyCode: string }) {
  const visible = comparison.categories.slice(0, VISIBLE_CATEGORIES);
  const remaining = comparison.categories.length - visible.length;

  return (
    <>
      <p className={`mb-3 text-xs ${MUTED_TEXT}`}>
        {/* Non-null: previousRange's bounds are typed nullable only because DateRangeValue is
            shared with ALL_TIME, but the server only ever computes one when both were concrete. */}
        vs {formatDate(comparison.previousRange.from!)} – {formatDate(comparison.previousRange.to!)}
      </p>

      <div className="flex items-baseline gap-3">
        <span className="text-2xl font-semibold text-slate-900 dark:text-slate-100">
          {currency(comparison.currentTotal, 0, currencyCode)}
        </span>
        <ChangeBadge amount={comparison.changeAmount} percent={comparison.changePercent} currencyCode={currencyCode} />
        <span className={`text-xs ${MUTED_TEXT}`}>was {currency(comparison.previousTotal, 0, currencyCode)}</span>
      </div>

      {visible.length > 0 && (
        <div className="mt-4 divide-y divide-slate-100 dark:divide-slate-800">
          {visible.map((c) => (
            <CategoryRow key={c.category} category={c} currencyCode={currencyCode} />
          ))}
        </div>
      )}

      {remaining > 0 && (
        <p className={`mt-2 text-xs ${MUTED_TEXT}`}>
          {remaining} more categor{remaining === 1 ? "y" : "ies"} with a smaller change
        </p>
      )}
    </>
  );
}

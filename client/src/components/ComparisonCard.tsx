import { useEffect, useState } from "react";
import { fetchComparison } from "../api";
import type { CategoryComparison, ComparisonResponse, DateRangeValue } from "../types";
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

function ChangeBadge({ amount, percent }: { amount: number; percent: number | null }) {
  // Spending more is the "bad" direction here, the reverse of a trend chart where up is good.
  const color = amount > 0 ? BAD : amount < 0 ? GOOD : undefined;
  const arrow = amount > 0 ? "↑" : amount < 0 ? "↓" : "→";
  return (
    <span className="text-sm font-semibold" style={{ color }}>
      {arrow} {currency(Math.abs(amount))}
      {percent !== null && <span className="ml-1 font-normal opacity-80">({Math.abs(percent).toFixed(0)}%)</span>}
    </span>
  );
}

function CategoryRow({ category }: { category: CategoryComparison }) {
  return (
    <div className="flex items-center justify-between gap-2 py-1 text-sm">
      <span className="text-slate-700 dark:text-slate-300">{category.category}</span>
      <ChangeBadge amount={category.changeAmount} percent={category.changePercent} />
    </div>
  );
}

export function ComparisonCard({ accountId, range }: Props) {
  const [response, setResponse] = useState<ComparisonResponse | null>(null);

  useEffect(() => {
    fetchComparison(accountId, range).then(setResponse).catch(() => setResponse(null));
  }, [accountId, range]);

  // Nothing to show for "all time" or a half-open filter -- there is no defined-length period
  // to mirror, and the comparison endpoint says so explicitly rather than this guessing.
  if (!response?.applicable || !response.comparison) return null;

  const { comparison } = response;
  const visible = comparison.categories.slice(0, VISIBLE_CATEGORIES);
  const remaining = comparison.categories.length - visible.length;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Compared to the previous period</h2>
        {/* Non-null: previousRange's bounds are typed nullable only because DateRangeValue is
            shared with ALL_TIME, but the server only ever computes one when both were concrete. */}
        <span className={`text-xs ${MUTED_TEXT}`}>
          vs {formatDate(comparison.previousRange.from!)} – {formatDate(comparison.previousRange.to!)}
        </span>
      </div>

      <div className="flex items-baseline gap-3">
        <span className="text-2xl font-semibold text-slate-900 dark:text-slate-100">
          {currency(comparison.currentTotal)}
        </span>
        <ChangeBadge amount={comparison.changeAmount} percent={comparison.changePercent} />
        <span className={`text-xs ${MUTED_TEXT}`}>was {currency(comparison.previousTotal)}</span>
      </div>

      {visible.length > 0 && (
        <div className="mt-4 divide-y divide-slate-100 dark:divide-slate-800">
          {visible.map((c) => (
            <CategoryRow key={c.category} category={c} />
          ))}
        </div>
      )}

      {remaining > 0 && (
        <p className={`mt-2 text-xs ${MUTED_TEXT}`}>
          {remaining} more categor{remaining === 1 ? "y" : "ies"} with a smaller change
        </p>
      )}
    </div>
  );
}

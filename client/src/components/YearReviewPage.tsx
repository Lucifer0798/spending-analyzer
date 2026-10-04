import { useEffect, useState } from "react";
import { Bar, BarChart, CartesianGrid, Legend, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fetchYearReview } from "../api";
import type { CategoryComparison, YearReview } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  /** Opens the Transactions page searching for this merchant. */
  onOpenMerchant: (merchant: string) => void;
}

const MUTED = "#898781";
const GRID = "#e1e0d9";
const MONTH_LABELS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function monthLabel(key: string) {
  const [year, month] = key.split("-").map(Number);
  return `${MONTH_LABELS[month - 1]} ${year}`;
}

function StatTile({ label, value, sub, tone }: { label: string; value: string; sub?: string; tone?: "good" | "bad" }) {
  const toneClass =
    tone === "good" ? "text-emerald-600 dark:text-emerald-400" : tone === "bad" ? "text-red-600 dark:text-red-400" : "text-slate-900 dark:text-slate-100";
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className={`mt-1 text-2xl font-semibold ${toneClass}`}>{value}</p>
      {sub && <p className="mt-0.5 text-xs text-slate-500">{sub}</p>}
    </div>
  );
}

function MonthTooltip({
  active,
  payload,
  label,
  currencyCode,
}: {
  active?: boolean;
  payload?: { value: number; name: string; color: string }[];
  label?: string;
  currencyCode: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900">
      <p className="font-medium text-slate-700 dark:text-slate-200">{label}</p>
      {payload.map((p) => (
        <p key={p.name} className="flex items-center gap-1.5 text-slate-600 dark:text-slate-400">
          <span className="inline-block h-2 w-2 rounded-sm" style={{ background: p.color }} />
          {p.name}: {currency(p.value, 0, currencyCode)}
        </p>
      ))}
    </div>
  );
}

function ChangeRow({ change, cur }: { change: CategoryComparison; cur: string }) {
  const up = change.changeAmount > 0;
  return (
    <li className="flex flex-wrap items-baseline justify-between gap-x-3 py-1.5 text-sm">
      <span className="text-slate-800 dark:text-slate-200">{change.category}</span>
      <span className="text-right">
        <span className={`font-medium ${up ? "text-red-600 dark:text-red-400" : "text-emerald-600 dark:text-emerald-400"}`}>
          {up ? "↑" : "↓"} {currency(Math.abs(change.changeAmount), 0, cur)}
          {change.changePercent !== null && ` (${Math.abs(change.changePercent).toFixed(0)}%)`}
        </span>
        <span className="ml-2 text-xs text-slate-500">
          {currency(change.previousTotal, 0, cur)} → {currency(change.currentTotal, 0, cur)}
        </span>
      </span>
    </li>
  );
}

/**
 * One calendar year at a glance. Uses the account filter but not the date filter -- the year is
 * the range -- so the header hides the date picker on this tab.
 */
export function YearReviewPage({ accountId, onOpenMerchant }: Props) {
  // null asks the server for the newest year with data; once a response names the year, the
  // selector shows it, and picking another year sets this explicitly.
  const [year, setYear] = useState<number | null>(null);
  const [review, setReview] = useState<YearReview | null>(null);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    fetchYearReview(accountId, year)
      .then((r) => {
        setReview(r);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load the year in review."));
  }, [accountId, year]);

  const header = (
    <div className="flex flex-wrap items-center justify-between gap-3">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Year in review</h1>
      {review && review.availableYears.length > 0 && (
        <select
          value={review.year ?? ""}
          onChange={(e) => setYear(Number(e.target.value))}
          aria-label="Year"
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
        >
          {[...review.availableYears].reverse().map((y) => (
            <option key={y} value={y}>{y}</option>
          ))}
        </select>
      )}
    </div>
  );

  if (error) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8">
        {header}
        <p className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">{error}</p>
      </div>
    );
  }
  if (!review) {
    return <p className="mt-8 text-center text-sm text-slate-500">Loading…</p>;
  }
  if (!review.applicable || !review.currency) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8">
        {header}
        <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Your accounts use different currencies, so a yearly total would mix them. Pick one account
          from the filter above to see its year.
        </p>
      </div>
    );
  }
  if (review.year === null) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8">
        {header}
        <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No transactions yet. Import a statement to see a year in review.
        </p>
      </div>
    );
  }

  const cur = review.currency;
  const chartData = review.months.map((m, i) => ({ label: MONTH_LABELS[i], income: m.income, spend: m.spend }));
  const increases = review.categoryChanges.filter((c) => c.changeAmount > 0).slice(0, 5);
  const decreases = review.categoryChanges.filter((c) => c.changeAmount < 0).slice(-5).reverse();

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      {header}

      {review.monthsWithData < 12 && (
        <p className="mt-2 text-sm text-slate-500">
          Covers {review.monthsWithData} month{review.monthsWithData === 1 ? "" : "s"} with transactions
          — totals are for what's been imported, not a full year.
        </p>
      )}

      <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
        <StatTile label="Income" value={currency(review.income, 0, cur)} />
        <StatTile label="Spent" value={currency(review.spend, 0, cur)} />
        <StatTile
          label={review.saved >= 0 ? "Saved" : "Overspent"}
          value={currency(Math.abs(review.saved), 0, cur)}
          tone={review.saved >= 0 ? "good" : "bad"}
          sub={review.savingsRate !== null ? `${review.savingsRate.toFixed(1)}% of income` : "no income recorded"}
        />
      </div>

      <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Income and spending by month</h2>
          {review.biggestMonth && (
            <span className="text-xs text-slate-500">
              Biggest month: {monthLabel(review.biggestMonth.month)} · {currency(review.biggestMonth.total, 0, cur)} spent
            </span>
          )}
        </div>
        <ResponsiveContainer width="100%" height={260}>
          <BarChart data={chartData} barGap={2} barCategoryGap="20%">
            <CartesianGrid stroke={GRID} vertical={false} />
            <XAxis dataKey="label" stroke={MUTED} fontSize={12} tickLine={false} axisLine={{ stroke: GRID }} />
            <YAxis stroke={MUTED} fontSize={12} tickLine={false} axisLine={false} width={56} />
            <Tooltip content={<MonthTooltip currencyCode={cur} />} cursor={{ fill: "rgba(137,135,129,0.12)" }} />
            <Legend wrapperStyle={{ fontSize: 12 }} />
            <Bar dataKey="income" name="Income" fill="var(--series-1)" radius={[4, 4, 0, 0]} />
            <Bar dataKey="spend" name="Spent" fill="var(--series-2)" radius={[4, 4, 0, 0]} />
          </BarChart>
        </ResponsiveContainer>
        <details className="mt-2 text-xs text-slate-600 dark:text-slate-400">
          <summary className="cursor-pointer text-slate-500">Show as a table</summary>
          <table className="mt-2 w-full text-left">
            <thead>
              <tr className="text-slate-500">
                <th className="py-1 font-medium">Month</th>
                <th className="py-1 text-right font-medium">Income</th>
                <th className="py-1 text-right font-medium">Spent</th>
                <th className="py-1 text-right font-medium">Net</th>
              </tr>
            </thead>
            <tbody>
              {review.months.map((m) => (
                <tr key={m.month} className="border-t border-slate-100 dark:border-slate-800">
                  <td className="py-1">{monthLabel(m.month)}</td>
                  <td className="py-1 text-right">{currency(m.income, 0, cur)}</td>
                  <td className="py-1 text-right">{currency(m.spend, 0, cur)}</td>
                  <td className="py-1 text-right">{currency(m.income - m.spend, 0, cur)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </details>
      </div>

      <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Top categories</h2>
          {review.topCategories.length === 0 ? (
            <p className="mt-2 text-sm text-slate-500">No spending this year.</p>
          ) : (
            <ul className="mt-3 space-y-2.5">
              {review.topCategories.map((c) => (
                <li key={c.category}>
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <span className="text-slate-800 dark:text-slate-200">{c.category}</span>
                    <span className="font-medium text-slate-900 dark:text-slate-100">
                      {currency(c.total, 0, cur)}
                      <span className="ml-1.5 text-xs font-normal text-slate-500">{c.sharePercent.toFixed(0)}%</span>
                    </span>
                  </div>
                  <div className="mt-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-800">
                    <div className="h-1.5 rounded-full bg-indigo-500" style={{ width: `${Math.max(2, c.sharePercent)}%` }} />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Top merchants</h2>
          {review.topMerchants.length === 0 ? (
            <p className="mt-2 text-sm text-slate-500">No spending this year.</p>
          ) : (
            <ul className="mt-3 divide-y divide-slate-100 dark:divide-slate-800">
              {review.topMerchants.map((m) => (
                <li key={m.merchant} className="flex flex-wrap items-baseline justify-between gap-x-3 py-1.5 text-sm">
                  <button
                    onClick={() => onOpenMerchant(m.merchant)}
                    title="Show this merchant's transactions"
                    className="truncate text-left text-slate-800 hover:text-indigo-600 hover:underline dark:text-slate-200 dark:hover:text-indigo-400"
                  >
                    {m.merchant}
                  </button>
                  <span className="text-right">
                    <span className="font-medium text-slate-900 dark:text-slate-100">{currency(m.total, 0, cur)}</span>
                    <span className="ml-2 text-xs text-slate-500">{m.count} visit{m.count === 1 ? "" : "s"}</span>
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>

      {review.previousYear !== null && review.categoryChanges.length > 0 && (
        <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Compared with {review.previousYear}</h2>
          {review.previousYearMonthsWithData !== review.monthsWithData && (
            <p className="mt-1 text-xs text-amber-600 dark:text-amber-400">
              {review.previousYear} has {review.previousYearMonthsWithData} month
              {review.previousYearMonthsWithData === 1 ? "" : "s"} of data and {review.year} has{" "}
              {review.monthsWithData}, so some of this difference is just coverage.
            </p>
          )}
          <div className="mt-3 grid grid-cols-1 gap-x-8 md:grid-cols-2">
            <div>
              <h3 className="text-xs font-medium uppercase tracking-wide text-slate-500">Spent more on</h3>
              {increases.length === 0 ? (
                <p className="mt-1 text-sm text-slate-500">Nothing went up.</p>
              ) : (
                <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                  {increases.map((c) => <ChangeRow key={c.category} change={c} cur={cur} />)}
                </ul>
              )}
            </div>
            <div className="mt-4 md:mt-0">
              <h3 className="text-xs font-medium uppercase tracking-wide text-slate-500">Spent less on</h3>
              {decreases.length === 0 ? (
                <p className="mt-1 text-sm text-slate-500">Nothing went down.</p>
              ) : (
                <ul className="divide-y divide-slate-100 dark:divide-slate-800">
                  {decreases.map((c) => <ChangeRow key={c.category} change={c} cur={cur} />)}
                </ul>
              )}
            </div>
          </div>
        </div>
      )}
    </div>
  );
}

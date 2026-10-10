import { useEffect, useState } from "react";
import { Bar, BarChart, CartesianGrid, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fetchBudgets, fetchCategories, fetchCategoryDetail } from "../api";
import type { CategoryDrilldown, DateRangeValue } from "../types";
import { currency, currencyPrecise } from "../format";

interface Props {
  category: string;
  accountId: number | null;
  range: DateRangeValue;
  onBack: () => void;
  onOpenMerchant: (merchant: string) => void;
  /** Opens Transactions filtered to this category. */
  onOpenTransactions: (category: string) => void;
}

const MUTED = "#898781";
const GRID = "#e1e0d9";

function monthLabel(key: string) {
  const [y, m] = key.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(undefined, { month: "short", year: "2-digit", timeZone: "UTC" });
}

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, { month: "short", day: "numeric", year: "numeric", timeZone: "UTC" });
}

function Tile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-xl font-semibold text-slate-900 dark:text-slate-100">{value}</p>
      {sub && <p className="mt-0.5 text-xs text-slate-500">{sub}</p>}
    </div>
  );
}

/**
 * One category up close for the active date range and account: how much, what share, the trend
 * month by month (with the monthly budget as a line, if there is one), where it goes, and the
 * latest transactions. Every figure comes from the same stats as the Dashboard bar.
 */
export function CategoryDetailPage({ category, accountId, range, onBack, onOpenMerchant, onOpenTransactions }: Props) {
  const [detail, setDetail] = useState<CategoryDrilldown | null>(null);
  // The category's own color from Manage, so the page matches the Dashboard bar it came from.
  const [categoryColor, setCategoryColor] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [monthlyBudget, setMonthlyBudget] = useState<number | null>(null);

  useEffect(() => {
    fetchCategoryDetail(category, accountId, range)
      .then((d) => {
        setDetail(d);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load this category."));
  }, [category, accountId, range]);

  useEffect(() => {
    fetchCategories()
      .then((r) => setCategoryColor(r.detailed.find((c) => c.name === category)?.color ?? null))
      .catch(() => setCategoryColor(null));
  }, [category]);

  useEffect(() => {
    // Only a monthly budget belongs on a monthly chart; weekly and quarterly limits aren't
    // comparable to one month's bar.
    fetchBudgets(accountId)
      .then((b) => {
        const mine = b.budgets.find((x) => x.category === category && x.period === "monthly");
        // The target as set, not this month's rollover-adjusted figure: it's drawn across every month.
        setMonthlyBudget(mine ? mine.baseLimit : null);
      })
      .catch(() => setMonthlyBudget(null));
  }, [category, accountId]);

  const back = (
    <button onClick={onBack} className="text-sm text-indigo-600 hover:underline dark:text-indigo-400">
      ← Dashboard
    </button>
  );

  if (error) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8">
        {back}
        <p className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">{error}</p>
      </div>
    );
  }
  if (!detail) return <p className="mt-8 text-center text-sm text-slate-500">Loading…</p>;
  if (!detail.applicable || !detail.currency) {
    return (
      <div className="mx-auto max-w-4xl px-4 py-8">
        {back}
        <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Your accounts use different currencies, so this category's total would mix them. Pick one
          account from the filter above.
        </p>
      </div>
    );
  }

  const cur = detail.currency;
  const color = categoryColor ?? "var(--series-1)";
  const chartData = detail.months.map((m) => ({ label: monthLabel(m.month), total: m.total }));
  const overBudgetMonths = monthlyBudget === null ? 0 : detail.months.filter((m) => m.total > monthlyBudget).length;

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      {back}
      <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
        <h1 className="flex items-center gap-2 text-xl font-semibold text-slate-900 dark:text-slate-100">
          <span className="inline-block h-3 w-3 rounded-full" style={{ background: color }} />
          {category}
        </h1>
        <button
          onClick={() => onOpenTransactions(category)}
          className="rounded-md border border-slate-300 px-3 py-1.5 text-sm text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
        >
          All {detail.count} transaction{detail.count === 1 ? "" : "s"} →
        </button>
      </div>

      <div className="mt-5 grid grid-cols-2 gap-3 sm:grid-cols-4">
        <Tile label="Spent" value={currency(detail.total, 0, cur)} sub={`${detail.count} transaction${detail.count === 1 ? "" : "s"}`} />
        <Tile label="Share of spending" value={`${detail.sharePercent.toFixed(1)}%`} />
        <Tile label="Per month" value={currency(detail.averagePerMonth, 0, cur)} sub={`average over ${detail.months.length} month${detail.months.length === 1 ? "" : "s"}`} />
        {detail.change ? (
          <Tile
            label="Vs. previous period"
            value={`${detail.change.changeAmount > 0 ? "↑" : detail.change.changeAmount < 0 ? "↓" : "→"} ${currency(Math.abs(detail.change.changeAmount), 0, cur)}`}
            sub={detail.change.changePercent !== null ? `${Math.abs(detail.change.changePercent).toFixed(0)}% ${detail.change.changeAmount >= 0 ? "more" : "less"}` : "nothing the period before"}
          />
        ) : (
          <Tile label="Vs. previous period" value="—" sub="pick a date range to compare" />
        )}
      </div>

      {chartData.length > 0 && (
        <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Month by month</h2>
            {monthlyBudget !== null && (
              <span className="text-xs text-slate-500">
                Dashed line: monthly budget {currency(monthlyBudget, 0, cur)}
                {overBudgetMonths > 0 && ` · over in ${overBudgetMonths} of ${detail.months.length} months`}
              </span>
            )}
          </div>
          <ResponsiveContainer width="100%" height={220}>
            <BarChart data={chartData}>
              <CartesianGrid stroke={GRID} vertical={false} />
              <XAxis dataKey="label" stroke={MUTED} fontSize={12} tickLine={false} axisLine={{ stroke: GRID }} />
              <YAxis stroke={MUTED} fontSize={12} tickLine={false} axisLine={false} width={56} />
              <Tooltip
                formatter={(v) => [currencyPrecise(Number(v), cur), "Spent"]}
                cursor={{ fill: "rgba(137,135,129,0.12)" }}
                contentStyle={{ fontSize: 12 }}
              />
              <Bar dataKey="total" fill={color} radius={[4, 4, 0, 0]} />
              {monthlyBudget !== null && <ReferenceLine y={monthlyBudget} stroke={MUTED} strokeDasharray="5 4" strokeWidth={2} />}
            </BarChart>
          </ResponsiveContainer>
        </div>
      )}

      <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
        <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">
            Where it goes
            {detail.merchantCount > detail.topMerchants.length && (
              <span className="ml-1 font-normal text-slate-500">· top {detail.topMerchants.length} of {detail.merchantCount}</span>
            )}
          </h2>
          {detail.topMerchants.length === 0 ? (
            <p className="mt-2 text-sm text-slate-500">Nothing spent in this range.</p>
          ) : (
            <ul className="mt-3 space-y-2">
              {detail.topMerchants.map((m) => (
                <li key={m.merchant}>
                  <div className="flex items-baseline justify-between gap-3 text-sm">
                    <button
                      onClick={() => onOpenMerchant(m.merchant)}
                      className="truncate text-left text-slate-800 hover:text-indigo-600 hover:underline dark:text-slate-200 dark:hover:text-indigo-400"
                    >
                      {m.merchant}
                    </button>
                    <span className="font-medium text-slate-900 dark:text-slate-100">{currency(m.total, 0, cur)}</span>
                  </div>
                  <div className="mt-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-800">
                    <div
                      className="h-1.5 rounded-full"
                      style={{ width: `${Math.max(2, (m.total / detail.topMerchants[0].total) * 100)}%`, background: color }}
                    />
                  </div>
                </li>
              ))}
            </ul>
          )}
        </div>

        <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
          <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Latest transactions</h2>
          {detail.recent.length === 0 ? (
            <p className="mt-2 text-sm text-slate-500">Nothing spent in this range.</p>
          ) : (
            <ul className="mt-3 divide-y divide-slate-100 dark:divide-slate-800">
              {detail.recent.map((t) => (
                <li key={t.id} className="flex items-baseline justify-between gap-3 py-1.5 text-sm">
                  <span className="min-w-0">
                    <span className="block truncate text-slate-800 dark:text-slate-200">{t.description}</span>
                    <span className="text-xs text-slate-500">{shortDate(t.date)}</span>
                  </span>
                  <span className="shrink-0 font-medium text-slate-900 dark:text-slate-100">
                    {currencyPrecise(t.split_share ?? t.amount, cur)}
                  </span>
                </li>
              ))}
            </ul>
          )}
        </div>
      </div>
    </div>
  );
}

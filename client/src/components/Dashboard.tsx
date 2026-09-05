import { useEffect, useState } from "react";
import {
  Bar,
  BarChart,
  CartesianGrid,
  Line,
  LineChart,
  ResponsiveContainer,
  Tooltip,
  XAxis,
  YAxis,
} from "recharts";
import { exportUrl, fetchPredictions, fetchSummary, refreshPredictions } from "../api";
import type { DateRangeValue, PredictionsPayload, SummaryResponse } from "../types";
import { currency } from "../format";
import { ExportLink } from "./ExportLink";
import { BudgetsCard } from "./BudgetsCard";
import { ComparisonCard } from "./ComparisonCard";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

const BLUE = "#2a78d6";
const MUTED = "#898781";
const GRID = "#e1e0d9";

const STATUS = {
  good: "#0ca30c",
  warning: "#fab219",
  serious: "#ec835a",
  critical: "#d03b3b",
};

function trendColor(trend: string) {
  if (trend === "decreasing") return STATUS.good;
  if (trend === "increasing") return STATUS.serious;
  return MUTED;
}

function trendArrow(trend: string) {
  if (trend === "decreasing") return "↓";
  if (trend === "increasing") return "↑";
  return "→";
}

function StatTile({ label, value, sub }: { label: string; value: string; sub?: string }) {
  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <p className="text-xs font-medium uppercase tracking-wide text-slate-500">{label}</p>
      <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">{value}</p>
      {sub && <p className="mt-1 text-xs text-slate-500">{sub}</p>}
    </div>
  );
}

function ChartTooltip({ active, payload, label }: { active?: boolean; payload?: { value: number; name: string }[]; label?: string }) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900">
      <p className="font-medium text-slate-700 dark:text-slate-200">{label}</p>
      {payload.map((p, i) => (
        <p key={i} className="text-slate-600 dark:text-slate-400">
          {p.name}: {currency(p.value)}
        </p>
      ))}
    </div>
  );
}

export function Dashboard({ accountId, range }: Props) {
  const [summary, setSummary] = useState<SummaryResponse | null>(null);
  const [predictions, setPredictions] = useState<PredictionsPayload | null>(null);
  const [generatedAt, setGeneratedAt] = useState<string | null>(null);
  const [refreshing, setRefreshing] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    // Clearing stale data before refetching from the API is synchronizing with an external
    // system, not a value that could instead be derived during render.
    // oxlint-disable-next-line react/set-state-in-effect
    setSummary(null);
    fetchSummary(accountId, range).then(setSummary);
  }, [accountId, range]);

  useEffect(() => {
    fetchPredictions().then((r) => {
      setPredictions(r.predictions);
      setGeneratedAt(r.generatedAt);
    });
  }, []);

  const handleRefresh = async () => {
    setRefreshing(true);
    setError(null);
    try {
      const r = await refreshPredictions(accountId);
      setPredictions(r.predictions);
      setGeneratedAt(r.generatedAt);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Failed to generate predictions.");
    } finally {
      setRefreshing(false);
    }
  };

  if (!summary) {
    return <div className="px-4 py-10 text-center text-sm text-slate-500">Loading dashboard…</div>;
  }

  const totalSpend = summary.monthlyTotals.reduce((a, b) => a + b.total, 0);
  const currentMonth = summary.monthlyTotals[summary.monthlyTotals.length - 1];
  const predictedTotal = predictions?.predictions.reduce((a, b) => a + b.predicted_next_month, 0) ?? null;

  // Income and transfers are already excluded server-side via category flags,
  // which also covers user-created categories marked as such.
  const categoryBars = [...summary.categoryTotals].sort((a, b) => b.total - a.total).slice(0, 12);

  const hasData = summary.monthlyTotals.length > 0;

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-6 flex items-center justify-between">
        <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Dashboard</h1>
        <button
          onClick={handleRefresh}
          disabled={refreshing || !hasData}
          className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {refreshing ? "Generating…" : "Generate predictions"}
        </button>
      </div>

      {!hasData && (
        <p className="rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No categorized spending yet. Upload a statement to get started.
        </p>
      )}

      {hasData && (
        <>
          <div className="grid grid-cols-1 gap-4 sm:grid-cols-3">
            <StatTile
              label="Total spend"
              value={currency(totalSpend)}
              sub={`${summary.monthlyTotals.length} ${summary.monthlyTotals.length === 1 ? "month" : "months"} of data`}
            />
            <StatTile
              label="Last month"
              value={currency(currentMonth.total)}
              sub={currentMonth.month}
            />
            <StatTile
              label="Predicted next month"
              value={predictedTotal !== null ? currency(predictedTotal) : "—"}
              // Always says "full history" — the forecast doesn't move when the date filter
              // does, and the tile should never look like it silently disagrees with it.
              sub={generatedAt ? `as of ${new Date(generatedAt).toLocaleDateString()} · full history` : "not generated yet"}
            />
          </div>

          {/* Renders nothing for "all time" or a half-open filter -- there's no defined-length
              period to compare against. */}
          <ComparisonCard accountId={accountId} range={range} />

          {/* Renders nothing until at least one budget is set, so the dashboard is unchanged
              for anyone not using them. */}
          <BudgetsCard accountId={accountId} range={range} />

          <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Monthly spend trend</h2>
              <ExportLink compact href={exportUrl("monthly", { accountId, range })} label="Export CSV" />
            </div>
            <ResponsiveContainer width="100%" height={240}>
              <LineChart data={summary.monthlyTotals}>
                <CartesianGrid stroke={GRID} vertical={false} />
                <XAxis dataKey="month" stroke={MUTED} fontSize={12} tickLine={false} axisLine={{ stroke: GRID }} />
                <YAxis stroke={MUTED} fontSize={12} tickLine={false} axisLine={false} tickFormatter={(v) => `$${v}`} />
                <Tooltip content={<ChartTooltip />} />
                <Line type="monotone" dataKey="total" name="Spend" stroke={BLUE} strokeWidth={2} dot={{ r: 4, fill: BLUE }} />
              </LineChart>
            </ResponsiveContainer>
          </div>

          <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <div className="mb-3 flex items-center justify-between">
              <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Spending by category</h2>
              {/* The chart shows the top 12; the export has every category, which is the point of it. */}
              <ExportLink
                compact
                href={exportUrl("categories", { accountId, range })}
                label="Export CSV"
                title="Download every category, not just the top 12 shown"
              />
            </div>
            <ResponsiveContainer width="100%" height={Math.max(240, categoryBars.length * 32)}>
              <BarChart data={categoryBars} layout="vertical" margin={{ left: 24 }}>
                <CartesianGrid stroke={GRID} horizontal={false} />
                <XAxis type="number" stroke={MUTED} fontSize={12} tickFormatter={(v) => `$${v}`} tickLine={false} axisLine={{ stroke: GRID }} />
                <YAxis type="category" dataKey="category" stroke={MUTED} fontSize={12} width={130} tickLine={false} axisLine={false} />
                <Tooltip content={<ChartTooltip />} />
                <Bar dataKey="total" name="Spend" fill={BLUE} radius={[0, 4, 4, 0]} />
              </BarChart>
            </ResponsiveContainer>
          </div>

          {error && (
            <div className="mt-6 rounded-lg bg-red-50 p-4 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
              {error}
            </div>
          )}

          {predictions && (
            <>
              <div className="mt-8 rounded-lg bg-indigo-50 p-4 text-sm text-indigo-900 dark:bg-indigo-950/30 dark:text-indigo-200">
                {predictions.summary}
              </div>

              <div className="mt-8">
                <div className="mb-1 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Next month predictions</h2>
                  {/* No filters on this one — there is a single forecast, built from full history. */}
                  <ExportLink compact href={exportUrl("predictions")} label="Export CSV" />
                </div>
                <p className="mb-3 text-xs text-slate-500">
                  Built from this account's full history, not the date range selected above.
                </p>
                <div className="grid grid-cols-1 gap-3 sm:grid-cols-2">
                  {predictions.predictions.map((p) => (
                    <div key={p.category} className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
                      <div className="flex items-center justify-between">
                        <span className="text-sm font-medium text-slate-800 dark:text-slate-200">{p.category}</span>
                        <span className="text-sm font-semibold" style={{ color: trendColor(p.trend) }}>
                          {trendArrow(p.trend)} {currency(p.predicted_next_month)}
                        </span>
                      </div>
                      <p className="mt-1 text-xs text-slate-500">{p.rationale}</p>
                    </div>
                  ))}
                </div>
              </div>

              <div className="mt-8">
                <div className="mb-3 flex items-center justify-between">
                  <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Where you could cut back</h2>
                  <ExportLink compact href={exportUrl("recommendations")} label="Export CSV" />
                </div>
                <div className="space-y-3">
                  {predictions.recommendations.map((r, i) => (
                    <div key={i} className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
                      <div className="flex items-center justify-between">
                        <span className="text-sm font-medium text-slate-800 dark:text-slate-200">{r.category}</span>
                        <span className="text-sm font-semibold" style={{ color: STATUS.good }}>
                          save ~{currency(r.potential_monthly_savings)}/mo
                        </span>
                      </div>
                      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">{r.insight}</p>
                      <p className="mt-1 text-sm font-medium text-slate-700 dark:text-slate-300">→ {r.suggested_action}</p>
                    </div>
                  ))}
                </div>
              </div>
            </>
          )}

          {!predictions && (
            <p className="mt-8 text-center text-sm text-slate-500">
              Click "Generate predictions" to get AI-powered spending forecasts and savings recommendations.
            </p>
          )}
        </>
      )}
    </div>
  );
}

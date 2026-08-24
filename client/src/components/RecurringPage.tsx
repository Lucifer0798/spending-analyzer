import { useEffect, useState } from "react";
import { fetchRecurring } from "../api";
import type { DateRangeValue, RecurringResponse } from "../types";
import { currency, currencyPrecise } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

const CADENCE_LABELS: Record<string, string> = {
  weekly: "Weekly",
  biweekly: "Every 2 weeks",
  monthly: "Monthly",
  quarterly: "Quarterly",
  yearly: "Yearly",
};

const CONFIDENCE_STYLES: Record<string, string> = {
  high: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300",
  medium: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300",
  low: "bg-amber-100 text-amber-800 dark:bg-amber-950/50 dark:text-amber-300",
};

export function RecurringPage({ accountId, range }: Props) {
  const [data, setData] = useState<RecurringResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    setLoading(true);
    setError(null);
    fetchRecurring(accountId, range)
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load recurring charges."))
      .finally(() => setLoading(false));
  }, [accountId, range]);

  if (loading) {
    return <div className="px-4 py-10 text-center text-sm text-slate-500">Finding recurring charges…</div>;
  }

  if (error) {
    return (
      <div className="mx-auto max-w-3xl px-4 py-8">
        <div className="rounded-lg bg-red-50 p-4 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      </div>
    );
  }

  const series = data?.recurring ?? [];

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Recurring charges</h1>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Charges that repeat on a regular schedule for a consistent amount. Merchants you visit often
        but spend a different amount at each time — groceries, coffee — are deliberately excluded.
      </p>

      {series.length === 0 ? (
        <p className="mt-8 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No recurring charges detected yet. This needs at least three occurrences of a charge at a
          steady interval and amount.
        </p>
      ) : (
        <>
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-3">
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Per month</p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {currency(data!.totalMonthlyEquivalent)}
              </p>
              <p className="mt-1 text-xs text-slate-500">committed on average</p>
            </div>
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Per year</p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {currency(data!.totalAnnualizedCost)}
              </p>
              <p className="mt-1 text-xs text-slate-500">if nothing changes</p>
            </div>
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Detected</p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {series.length}
              </p>
              <p className="mt-1 text-xs text-slate-500">recurring charges</p>
            </div>
          </div>

          <div className="mt-6 overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
            <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
              <thead className="bg-slate-50 dark:bg-slate-900">
                <tr>
                  <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Merchant</th>
                  <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Cadence</th>
                  <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500">Typical</th>
                  <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500">Per year</th>
                  <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Next due</th>
                  <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Seen</th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
                {series.map((r) => (
                  <tr key={r.merchant}>
                    <td className="px-4 py-2">
                      <div className="text-sm font-medium text-slate-800 dark:text-slate-200">{r.merchant}</div>
                      {r.category && <div className="text-xs text-slate-500">{r.category}</div>}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {CADENCE_LABELS[r.cadence] ?? r.cadence}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm font-medium text-slate-800 dark:text-slate-200">
                      {currencyPrecise(r.average_amount)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm text-slate-600 dark:text-slate-400">
                      {currency(r.annualized_cost)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {r.next_expected_date}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm">
                      <span className="text-slate-600 dark:text-slate-400">{r.occurrences}×</span>
                      <span
                        className={`ml-2 rounded px-1.5 py-0.5 text-[10px] font-medium uppercase ${
                          CONFIDENCE_STYLES[r.confidence]
                        }`}
                      >
                        {r.confidence}
                      </span>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>

          <p className="mt-3 text-xs text-slate-500">
            Low confidence usually means only a few occurrences so far, or an amount that moves a
            little between charges.
          </p>
        </>
      )}
    </div>
  );
}

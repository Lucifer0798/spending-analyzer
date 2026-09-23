import { useEffect, useState } from "react";
import { clearRecurringOverride, fetchRecurring, fetchRecurringIncome, saveRecurringOverride } from "../api";
import type { DateRangeValue, RecurringIncomeResponse, RecurringResponse, RecurringSeries } from "../types";
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

/** A short countdown next to next_expected_date — red once it's passed, amber inside a week. */
function DueBadge({ daysAway }: { daysAway: number }) {
  let text: string;
  let className: string;
  if (daysAway < 0) {
    text = `overdue ${Math.abs(daysAway)}d`;
    className = "text-red-600 dark:text-red-400";
  } else if (daysAway === 0) {
    text = "due today";
    className = "text-amber-700 dark:text-amber-400";
  } else if (daysAway <= 7) {
    text = `in ${daysAway}d`;
    className = "text-amber-700 dark:text-amber-400";
  } else {
    text = `in ${daysAway}d`;
    className = "text-slate-400 dark:text-slate-500";
  }
  return <span className={`ml-1.5 text-xs ${className}`}>({text})</span>;
}

/** Called out under the merchant name when the latest charge drifted from what it used to be. */
function PriceChangeNote({ series, currencyCode }: { series: RecurringSeries; currencyCode: string }) {
  if (!series.price_changed || series.previous_amount == null) return null;
  const rose = series.last_amount > series.previous_amount;
  return (
    <div className={`text-xs ${rose ? "text-red-600 dark:text-red-400" : "text-emerald-600 dark:text-emerald-400"}`}>
      {rose ? "↑" : "↓"} was {currencyPrecise(series.previous_amount, currencyCode)}
    </div>
  );
}

function RecurringActions({
  series,
  onChanged,
}: {
  series: RecurringSeries;
  onChanged: () => void;
}) {
  const [busy, setBusy] = useState(false);

  const run = async (action: () => Promise<unknown>) => {
    setBusy(true);
    try {
      await action();
      onChanged();
    } finally {
      setBusy(false);
    }
  };

  if (series.flagged_for_cancellation) {
    return (
      <div className="flex items-center justify-end gap-2">
        <span className="rounded bg-amber-100 px-1.5 py-0.5 text-[10px] font-medium uppercase text-amber-800 dark:bg-amber-950/50 dark:text-amber-300">
          Cancelling
        </span>
        <button
          disabled={busy}
          onClick={() => run(() => clearRecurringOverride(series.override_id!))}
          className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 disabled:opacity-40 dark:text-slate-400 dark:hover:bg-slate-800"
        >
          Undo
        </button>
      </div>
    );
  }

  return (
    <div className="flex items-center justify-end gap-1">
      <button
        disabled={busy}
        onClick={() => run(() => saveRecurringOverride(series.merchant, "cancel"))}
        className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 disabled:opacity-40 dark:text-slate-400 dark:hover:bg-slate-800"
        title="Mark that you're cancelling this, as a reminder until the charges stop"
      >
        Flag to cancel
      </button>
      <button
        disabled={busy}
        onClick={() => run(() => saveRecurringOverride(series.merchant, "exclude"))}
        className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-red-600 disabled:opacity-40 dark:hover:bg-slate-800"
        title="This isn't really a subscription — hide it from this list for good"
      >
        Not recurring
      </button>
    </div>
  );
}

function monthLabel(month: string) {
  const [year, m] = month.split("-");
  const date = new Date(Number(year), Number(m) - 1, 1);
  return date.toLocaleDateString(undefined, { month: "long", year: "numeric" });
}

/**
 * Regular deposits, detected the exact same way the charges above are -- a separate section
 * rather than one merged list, since "cadence + steady amount" means something different on the
 * credit side (a paycheck, not a subscription) and has no cancel/exclude actions to offer.
 */
function RecurringIncomeSection({ accountId, range }: Props) {
  const [data, setData] = useState<RecurringIncomeResponse | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    // Synchronizing with an external system on mount and on every filter change; there is no
    // render-time equivalent for "start loading before the fetch resolves."
    // oxlint-disable-next-line react/set-state-in-effect
    setLoading(true);
    fetchRecurringIncome(accountId, range)
      .then(setData)
      .catch(() => setData(null))
      .finally(() => setLoading(false));
  }, [accountId, range]);

  if (loading) {
    return <p className="mt-10 text-sm text-slate-500">Finding recurring income…</p>;
  }

  const series = data?.recurring ?? [];
  const cur = data?.currency ?? "USD";

  return (
    <section className="mt-10">
      <h2 className="text-lg font-semibold text-slate-900 dark:text-slate-100">Recurring income</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Deposits that repeat on a regular schedule for a consistent amount — paychecks and the
        like. A one-off deposit, or one that varies each time, won't show up here.
      </p>

      {data?.mixedCurrencies ? (
        <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          These accounts use different currencies. Select one account above to see its recurring
          income.
        </p>
      ) : series.length === 0 ? (
        <p className="mt-6 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No recurring income detected yet. This needs at least three deposits from the same
          source at a steady interval and amount.
        </p>
      ) : (
        <>
          <div className="mt-6 grid grid-cols-1 gap-4 sm:grid-cols-2">
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Per month</p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {currency(data!.totalMonthlyEquivalent, 0, cur)}
              </p>
              <p className="mt-1 text-xs text-slate-500">average, not a prediction for any one month</p>
            </div>
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">
                Received in {monthLabel(data!.month)}
              </p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {currency(data!.actualThisMonth, 0, cur)}
              </p>
              <p className="mt-1 text-xs text-slate-500">actual income that month, recurring or not</p>
            </div>
          </div>

          <div className="mt-6 overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
            <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
              <thead className="bg-slate-50 dark:bg-slate-900">
                <tr>
                  <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Source</th>
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
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {CADENCE_LABELS[r.cadence] ?? r.cadence}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm font-medium text-slate-800 dark:text-slate-200">
                      {currencyPrecise(r.average_amount, cur)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm text-slate-600 dark:text-slate-400">
                      {currency(r.annualized_cost, 0, cur)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {r.next_expected_date}
                      <DueBadge daysAway={r.due_in_days} />
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
        </>
      )}
    </section>
  );
}

/**
 * The original page content -- self-contained with its own loading/error state so a slow or
 * failed fetch here doesn't block {@link RecurringIncomeSection} from rendering below it; the
 * two sections load independently.
 */
function RecurringChargesSection({ accountId, range }: Props) {
  const [data, setData] = useState<RecurringResponse | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    // Synchronizing with an external system (the API) on mount and on every filter change;
    // resetting loading/error here has no render-time equivalent.
    // oxlint-disable-next-line react/set-state-in-effect
    setLoading(true);
    setError(null);
    fetchRecurring(accountId, range)
      .then(setData)
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load recurring charges."))
      .finally(() => setLoading(false));
  };

  useEffect(load, [accountId, range]);

  if (loading) {
    return <p className="mt-6 text-sm text-slate-500">Finding recurring charges…</p>;
  }

  if (error) {
    return (
      <div className="mt-6 rounded-lg bg-red-50 p-4 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
        {error}
      </div>
    );
  }

  const series = data?.recurring ?? [];
  const cur = data?.currency ?? "USD";

  return (
    <>
      {data?.mixedCurrencies ? (
        <p className="mt-8 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          These accounts use different currencies. Select one account above to see its recurring
          charges.
        </p>
      ) : series.length === 0 ? (
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
                {currency(data!.totalMonthlyEquivalent, 0, cur)}
              </p>
              <p className="mt-1 text-xs text-slate-500">committed on average</p>
            </div>
            <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
              <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Per year</p>
              <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
                {currency(data!.totalAnnualizedCost, 0, cur)}
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
                  <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500"></th>
                </tr>
              </thead>
              <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
                {series.map((r) => (
                  <tr key={r.merchant}>
                    <td className="px-4 py-2">
                      <div className="text-sm font-medium text-slate-800 dark:text-slate-200">{r.merchant}</div>
                      {r.category && <div className="text-xs text-slate-500">{r.category}</div>}
                      <PriceChangeNote series={r} currencyCode={cur} />
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {CADENCE_LABELS[r.cadence] ?? r.cadence}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm font-medium text-slate-800 dark:text-slate-200">
                      {currencyPrecise(r.average_amount, cur)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-right text-sm text-slate-600 dark:text-slate-400">
                      {currency(r.annualized_cost, 0, cur)}
                    </td>
                    <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                      {r.next_expected_date}
                      <DueBadge daysAway={r.due_in_days} />
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
                    <td className="whitespace-nowrap px-4 py-2 text-right">
                      <RecurringActions series={r} onChanged={load} />
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
    </>
  );
}

export function RecurringPage({ accountId, range }: Props) {
  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Recurring charges</h1>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Charges that repeat on a regular schedule for a consistent amount. Merchants you visit often
        but spend a different amount at each time — groceries, coffee — are deliberately excluded.
      </p>

      <RecurringChargesSection accountId={accountId} range={range} />
      <RecurringIncomeSection accountId={accountId} range={range} />
    </div>
  );
}

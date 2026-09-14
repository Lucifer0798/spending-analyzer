import { useEffect, useState } from "react";
import { fetchAnomalies } from "../api";
import type { AnomaliesResponse, DateRangeValue } from "../types";
import { currency } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

/** Shown collapsed to the biggest few; the rest are still real, just not worth the space. */
const MAX_SHOWN = 5;

export function AnomaliesCard({ accountId, range }: Props) {
  const [data, setData] = useState<AnomaliesResponse | null>(null);

  useEffect(() => {
    fetchAnomalies(accountId, range).then(setData).catch(() => setData(null));
  }, [accountId, range]);

  // Also covers the mixed-currency case the same way BudgetsCard does: nothing renders rather
  // than a comparison that would mix currencies.
  if (!data || data.mixedCurrencies || data.anomalies.length === 0) return null;
  const cur = data.currency;
  const shown = data.anomalies.slice(0, MAX_SHOWN);

  return (
    <div className="mt-8 rounded-lg border border-amber-200 bg-amber-50 p-4 dark:border-amber-900/50 dark:bg-amber-950/20">
      <h2 className="mb-3 text-sm font-semibold text-amber-900 dark:text-amber-200">
        ⚠ Unusually large charges
      </h2>

      <div className="space-y-2">
        {shown.map((a) => (
          <div key={a.transactionId} className="flex flex-wrap items-baseline justify-between gap-x-3 gap-y-0.5 text-sm">
            <span>
              <span className="font-medium text-slate-800 dark:text-slate-200">{a.description}</span>
              <span className="ml-2 text-xs text-slate-500">
                {a.category} · {a.date}
              </span>
            </span>
            <span className="whitespace-nowrap">
              <span className="font-semibold text-amber-700 dark:text-amber-400">
                {currency(a.amount, 0, cur)}
              </span>
              <span className="ml-2 text-xs text-slate-500">
                {a.multiplier.toFixed(1)}× typical ({currency(a.typicalAmount, 0, cur)})
              </span>
            </span>
          </div>
        ))}
      </div>

      {data.anomalies.length > MAX_SHOWN && (
        <p className="mt-2 text-xs text-slate-500">+{data.anomalies.length - MAX_SHOWN} more</p>
      )}
    </div>
  );
}

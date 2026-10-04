import { useEffect, useState } from "react";
import { fetchTopMerchants } from "../api";
import type { DateRangeValue, TopMerchantsResponse } from "../types";
import { currency, currencyPrecise } from "../format";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
  /** Opens the Transactions page searching for this merchant. */
  onOpenMerchant: (merchant: string) => void;
}

const COLLAPSED = 5;
const EXPANDED = 25;

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

/**
 * Where the money goes, by place rather than by category. Renders nothing without spend, or when
 * "all accounts" spans more than one currency -- the same refusal the comparison card makes.
 */
export function TopMerchantsCard({ accountId, range, onOpenMerchant }: Props) {
  const [response, setResponse] = useState<TopMerchantsResponse | null>(null);
  const [expanded, setExpanded] = useState(false);

  useEffect(() => {
    // Always fetch the expanded length: toggling "show more" then needs no second request.
    fetchTopMerchants(accountId, range, EXPANDED).then(setResponse).catch(() => setResponse(null));
  }, [accountId, range]);

  if (!response?.applicable || !response.currency || response.merchants.length === 0) return null;

  const cur = response.currency;
  const shown = response.merchants.slice(0, expanded ? EXPANDED : COLLAPSED);
  const max = response.merchants[0].total;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-3 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Top merchants</h2>
        <span className="text-xs text-slate-500">
          {response.merchantCount} merchant{response.merchantCount === 1 ? "" : "s"} in this period
        </span>
      </div>

      <ul className="space-y-2.5">
        {shown.map((m) => (
          <li key={m.merchant}>
            <div className="flex flex-wrap items-baseline justify-between gap-x-3 text-sm">
              <button
                onClick={() => onOpenMerchant(m.merchant)}
                title="Show this merchant's transactions"
                className="truncate text-left font-medium text-slate-800 hover:text-indigo-600 hover:underline dark:text-slate-200 dark:hover:text-indigo-400"
              >
                {m.merchant}
              </button>
              <span className="font-semibold text-slate-900 dark:text-slate-100">{currency(m.total, 0, cur)}</span>
            </div>
            <div className="mt-1 h-1.5 rounded-full bg-slate-100 dark:bg-slate-800">
              <div className="h-1.5 rounded-full bg-indigo-500" style={{ width: `${Math.max(2, (m.total / max) * 100)}%` }} />
            </div>
            <p className="mt-1 text-xs text-slate-500">
              {m.count} visit{m.count === 1 ? "" : "s"} · avg {currencyPrecise(m.averageAmount, cur)} · {m.topCategory}
              {" · last "}
              {shortDate(m.lastDate)}
            </p>
          </li>
        ))}
      </ul>

      {response.merchants.length > COLLAPSED && (
        <button
          onClick={() => setExpanded((e) => !e)}
          className="mt-3 text-xs font-medium text-indigo-600 hover:underline dark:text-indigo-400"
        >
          {expanded ? "Show fewer" : `Show top ${Math.min(EXPANDED, response.merchants.length)}`}
        </button>
      )}
    </div>
  );
}

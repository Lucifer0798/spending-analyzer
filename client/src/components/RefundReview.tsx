import { useEffect, useState } from "react";
import { confirmRefunds, dismissRefunds, fetchRefundSuggestions } from "../api";
import type { RefundSuggestion, RefundSuggestionsResponse } from "../types";
import { RefundPairLine } from "./RefundPairLine";

interface Props {
  /** Called after refunds are confirmed, so the transaction list can refresh. */
  onChanged: () => void;
  /** Any value that changes whenever the page's transactions change, so suggestions re-fetch. */
  refreshKey?: unknown;
}

const pairKey = (s: RefundSuggestion) => `${s.purchase.id}:${s.refund.id}`;
const ref = (s: RefundSuggestion) => ({ purchaseId: s.purchase.id, refundId: s.refund.id });

/**
 * Credits that look like refunds of earlier purchases. Until paired, the purchase still counts as
 * spending and the refund (usually filed under Income) as income. Confirming takes the refund off
 * the purchase instead; "Not a refund" stops the pair being suggested. Renders nothing when there's
 * nothing to review.
 */
export function RefundReview({ onChanged, refreshKey }: Props) {
  const [data, setData] = useState<RefundSuggestionsResponse | null>(null);
  const [open, setOpen] = useState(false);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastDone, setLastDone] = useState<string | null>(null);

  const load = () => {
    fetchRefundSuggestions()
      .then(setData)
      .catch(() => setData(null));
  };

  useEffect(load, [refreshKey]);

  if (!data || data.total === 0) {
    return lastDone ? (
      <p className="mb-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
        No more likely refunds to review. {lastDone} Unpair any of them under Manage → Refunds.
      </p>
    ) : null;
  }

  const run = async (key: string, action: () => Promise<string>) => {
    setBusy(key);
    setError(null);
    try {
      setLastDone(await action());
      load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong.");
    } finally {
      setBusy(null);
    }
  };

  const confirm = (pairs: RefundSuggestion[], key: string) =>
    run(key, async () => {
      const r = await confirmRefunds(pairs.map(ref));
      const skipped = r.skipped.length > 0 ? ` ${r.skipped.length} skipped: ${r.skipped[0].reason}` : "";
      return `Paired ${r.confirmed} refund${r.confirmed === 1 ? "" : "s"} with ${r.confirmed === 1 ? "its purchase" : "their purchases"}.${skipped}`;
    });

  const dismiss = (s: RefundSuggestion) =>
    run(pairKey(s), async () => {
      await dismissRefunds([ref(s)]);
      return "Got it — that pair won't be suggested again.";
    });

  const shown = data.suggestions;

  return (
    <div className="mb-4 rounded-lg border border-teal-200 bg-teal-50/60 p-4 dark:border-teal-900/60 dark:bg-teal-950/20">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-teal-900 dark:text-teal-200">
          <strong>{data.total}</strong> likely refund{data.total === 1 ? "" : "s"} — the purchase{" "}
          {data.total === 1 ? "still counts" : "s still count"} as spending.
        </p>
        <button
          onClick={() => setOpen((o) => !o)}
          className="rounded-md bg-teal-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-teal-800"
        >
          {open ? "Hide" : "Review refunds"}
        </button>
      </div>

      {open && (
        <>
          <p className="mt-2 text-xs text-teal-900/80 dark:text-teal-300/80">
            Each is money back from a merchant you bought from, in the same account, within {data.maxDaysAfter} days.
            Pairing takes the refund off the purchase, so you see what you really spent, and the refund stops counting
            as income. Neither transaction is changed.
          </p>
          {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          {lastDone && <p className="mt-2 text-xs text-emerald-700 dark:text-emerald-400">✓ {lastDone}</p>}

          <ul className="mt-3 divide-y divide-teal-200/70 dark:divide-teal-900/50">
            {shown.map((s) => {
              const key = pairKey(s);
              return (
                <li key={key} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3">
                  <RefundPairLine purchase={s.purchase} refund={s.refund} daysAfter={s.daysAfter} />
                  <div className="flex items-center gap-2">
                    <button
                      disabled={busy !== null}
                      onClick={() => confirm([s], key)}
                      className="rounded-md bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-40"
                    >
                      {busy === key ? "Saving…" : "Refund"}
                    </button>
                    <button
                      disabled={busy !== null}
                      onClick={() => dismiss(s)}
                      className="rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    >
                      Not a refund
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {shown.length > 1 && (
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-teal-200/70 pt-3 dark:border-teal-900/50">
              <p className="text-xs text-slate-600 dark:text-slate-400">
                {data.total > shown.length ? `Showing the newest ${shown.length} of ${data.total}.` : "Checked them all?"}
              </p>
              <button
                disabled={busy !== null}
                onClick={() => confirm(shown, "all")}
                className="rounded-md border border-indigo-300 px-3 py-1 text-xs font-medium text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300 dark:hover:bg-indigo-950"
              >
                {busy === "all" ? "Saving…" : `Pair all ${shown.length} shown`}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

import { useEffect, useState } from "react";
import { confirmTransfers, dismissTransfers, fetchTransferSuggestions } from "../api";
import type { TransferSuggestion, TransferSuggestionsResponse } from "../types";
import { TransferPairLine } from "./TransferPairLine";

interface Props {
  /** Called after pairs are confirmed, so the transaction list can refresh. */
  onChanged: () => void;
  /** Any value that changes whenever the page's transactions change, so suggestions re-fetch. */
  refreshKey?: unknown;
  /** Start expanded -- when arriving from an import summary's "Review transfers". */
  initiallyOpen?: boolean;
}

const pairKey = (s: TransferSuggestion) => `${s.debit.id}:${s.credit.id}`;
const ref = (s: TransferSuggestion) => ({ debit_id: s.debit.id, credit_id: s.credit.id });

/**
 * Money that left one of your accounts and arrived in another, still counted as spending (and
 * possibly income). Confirming moves both halves into the transfer category; "Not a transfer"
 * stops the pair being suggested. Renders nothing when there's nothing to review. Spans every
 * account on purpose -- the account filter would hide the other half.
 */
export function TransferReview({ onChanged, refreshKey, initiallyOpen = false }: Props) {
  const [data, setData] = useState<TransferSuggestionsResponse | null>(null);
  const [open, setOpen] = useState(initiallyOpen);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastDone, setLastDone] = useState<string | null>(null);

  const load = () => {
    fetchTransferSuggestions()
      .then(setData)
      .catch(() => setData(null));
  };

  useEffect(load, [refreshKey]);

  if (!data || data.total === 0) {
    return lastDone ? (
      <p className="mb-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
        No more likely transfers to review. {lastDone} Undo any of them under Manage → Transfers.
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

  const confirm = (pairs: TransferSuggestion[], key: string) =>
    run(key, async () => {
      const r = await confirmTransfers(pairs.map(ref));
      const skipped = r.skipped.length > 0 ? ` ${r.skipped.length} skipped: ${r.skipped[0].reason}` : "";
      return `Marked ${r.confirmed} pair${r.confirmed === 1 ? "" : "s"} as ${data.transfer_category}.${skipped}`;
    });

  const dismiss = (s: TransferSuggestion) =>
    run(pairKey(s), async () => {
      await dismissTransfers([ref(s)]);
      return "Got it — that pair won't be suggested again.";
    });

  const shown = data.suggestions;
  const noCategory = data.transfer_category === null;

  return (
    <div className="mb-4 rounded-lg border border-sky-200 bg-sky-50/60 p-4 dark:border-sky-900/60 dark:bg-sky-950/20">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-sky-900 dark:text-sky-200">
          <strong>{data.total}</strong> likely transfer{data.total === 1 ? "" : "s"} between your accounts{" "}
          {data.total === 1 ? "is" : "are"} still counted as spending.
        </p>
        <button
          onClick={() => setOpen((o) => !o)}
          className="rounded-md bg-sky-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-sky-800"
        >
          {open ? "Hide" : "Review transfers"}
        </button>
      </div>

      {open && (
        <>
          <p className="mt-2 text-xs text-sky-900/80 dark:text-sky-300/80">
            Each is a payment out of one account and the same amount into another within{" "}
            {data.max_days_apart} days. Confirming moves both into{" "}
            {noCategory ? "a transfer category" : <strong>{data.transfer_category}</strong>}, so neither counts as
            spending or income.
          </p>
          {noCategory && (
            <p className="mt-2 text-xs text-red-600 dark:text-red-400">
              No category is marked as a transfer. Mark one on the Manage page first.
            </p>
          )}
          {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          {lastDone && <p className="mt-2 text-xs text-emerald-700 dark:text-emerald-400">✓ {lastDone}</p>}

          <ul className="mt-3 divide-y divide-sky-200/70 dark:divide-sky-900/50">
            {shown.map((s) => {
              const key = pairKey(s);
              return (
                <li key={key} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-3">
                  <TransferPairLine debit={s.debit} credit={s.credit} />
                  <div className="flex items-center gap-2">
                    <button
                      disabled={busy !== null || noCategory}
                      onClick={() => confirm([s], key)}
                      className="rounded-md bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-40"
                    >
                      {busy === key ? "Saving…" : "Transfer"}
                    </button>
                    <button
                      disabled={busy !== null}
                      onClick={() => dismiss(s)}
                      className="rounded-md border border-slate-300 px-3 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    >
                      Not a transfer
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>

          {shown.length > 1 && (
            <div className="mt-3 flex flex-wrap items-center justify-between gap-2 border-t border-sky-200/70 pt-3 dark:border-sky-900/50">
              <p className="text-xs text-slate-600 dark:text-slate-400">
                {data.total > shown.length
                  ? `Showing the newest ${shown.length} of ${data.total}.`
                  : "Checked them all?"}
              </p>
              <button
                disabled={busy !== null || noCategory}
                onClick={() => confirm(shown, "all")}
                className="rounded-md border border-indigo-300 px-3 py-1 text-xs font-medium text-indigo-700 hover:bg-indigo-50 disabled:opacity-40 dark:border-indigo-800 dark:text-indigo-300 dark:hover:bg-indigo-950"
              >
                {busy === "all" ? "Saving…" : `Mark all ${shown.length} shown as transfers`}
              </button>
            </div>
          )}
        </>
      )}
    </div>
  );
}

import { useEffect, useState } from "react";
import { fetchTransferPairs, undoTransferPair } from "../api";
import type { TransferPairView } from "../types";
import { TransferPairLine } from "./TransferPairLine";

/** Collapsed past this many, so years of monthly transfers don't swamp the Manage page. */
const PREVIEW = 5;

function PairList({
  pairs,
  actionLabel,
  busyId,
  onAction,
}: {
  pairs: TransferPairView[];
  actionLabel: string;
  busyId: number | null;
  onAction: (p: TransferPairView) => void;
}) {
  const [showAll, setShowAll] = useState(false);
  const visible = showAll ? pairs : pairs.slice(0, PREVIEW);
  return (
    <>
      <ul className="mt-2 divide-y divide-slate-100 overflow-hidden rounded-lg border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-950">
        {visible.map((p) => (
          <li key={p.id} className="flex flex-wrap items-center justify-between gap-3 px-4 py-2.5">
            <TransferPairLine debit={p.debit} credit={p.credit} />
            <button
              disabled={busyId !== null}
              onClick={() => onAction(p)}
              className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-slate-800 disabled:opacity-40 dark:hover:bg-slate-800 dark:hover:text-slate-200"
            >
              {busyId === p.id ? "Working…" : actionLabel}
            </button>
          </li>
        ))}
      </ul>
      {pairs.length > PREVIEW && (
        <button onClick={() => setShowAll((s) => !s)} className="mt-1 text-xs text-indigo-600 hover:underline dark:text-indigo-400">
          {showAll ? "Show fewer" : `Show all ${pairs.length}`}
        </button>
      )}
    </>
  );
}

/**
 * Every matched transfer, with undo. Suggestions themselves are reviewed on the Transactions page;
 * this is where to take a decision back.
 */
export function TransfersSection() {
  const [confirmed, setConfirmed] = useState<TransferPairView[]>([]);
  const [dismissed, setDismissed] = useState<TransferPairView[]>([]);
  const [busyId, setBusyId] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = () => {
    fetchTransferPairs()
      .then((r) => {
        setConfirmed(r.confirmed);
        setDismissed(r.dismissed);
      })
      .catch(() => setError("Couldn't load transfers."));
  };

  useEffect(load, []);

  const undo = async (p: TransferPairView) => {
    setBusyId(p.id);
    setError(null);
    try {
      const r = await undoTransferPair(p.id);
      setNotice(
        p.status === "confirmed"
          ? r.restored > 0
            ? `Unmatched — ${r.restored} transaction${r.restored === 1 ? "" : "s"} back in ${r.restored === 1 ? "its" : "their"} previous category.`
            : "Unmatched. Both sides had been recategorized since, so they keep their current categories."
          : "That pair can be suggested again."
      );
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Undo failed.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Transfers</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Money moved between your own accounts, matched on the Transactions page. Unmatching puts
        both sides back in the category they had before, unless you've recategorized them since.
      </p>

      {error && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{error}</p>}
      {notice && <p className="mt-2 text-sm text-emerald-700 dark:text-emerald-400">{notice}</p>}

      {confirmed.length === 0 && dismissed.length === 0 ? (
        <p className="mt-3 text-sm text-slate-500">Nothing matched yet.</p>
      ) : (
        <>
          {confirmed.length > 0 && (
            <div className="mt-3">
              <h3 className="text-sm font-medium text-slate-800 dark:text-slate-200">Matched ({confirmed.length})</h3>
              <PairList pairs={confirmed} actionLabel="Unmatch" busyId={busyId} onAction={undo} />
            </div>
          )}
          {dismissed.length > 0 && (
            <div className="mt-4">
              <h3 className="text-sm font-medium text-slate-800 dark:text-slate-200">
                Marked “not a transfer” ({dismissed.length})
              </h3>
              <PairList pairs={dismissed} actionLabel="Suggest again" busyId={busyId} onAction={undo} />
            </div>
          )}
        </>
      )}
    </section>
  );
}

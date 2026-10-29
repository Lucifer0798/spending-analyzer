import { useEffect, useState } from "react";
import { dismissDuplicateCharges, fetchDuplicateCharges } from "../api";
import type { DuplicateChargeGroup } from "../types";
import { currencyPrecise } from "../format";
import { dayMonthYear } from "../dates";

interface Props {
  accountId: number | null;
  /** Narrows the list below to this merchant, so the charges can be looked at side by side. */
  onShow: (merchant: string) => void;
  /** Any value that changes whenever the page's transactions change, so the groups re-fetch. */
  refreshKey?: unknown;
  /** Start expanded -- when arriving from the header's alerts menu. */
  initiallyOpen?: boolean;
}

/**
 * Charges that look like the same purchase taken twice -- same merchant, same amount, a day or two
 * apart, at a place you don't buy that exact thing from all the time. Nothing here changes a
 * transaction: it's something to raise with the merchant or bank, or to say isn't a duplicate.
 * Renders nothing when there's nothing to flag.
 */
export function DuplicateReview({ accountId, onShow, refreshKey, initiallyOpen = false }: Props) {
  const [groups, setGroups] = useState<DuplicateChargeGroup[] | null>(null);
  const [open, setOpen] = useState(initiallyOpen);
  const [busy, setBusy] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    fetchDuplicateCharges(accountId)
      .then((r) => setGroups(r.groups))
      .catch(() => setGroups(null));
  };

  useEffect(load, [accountId, refreshKey]);

  if (!groups || groups.length === 0) return null;

  const keyOf = (g: DuplicateChargeGroup) => g.transactions.map((t) => t.id).join(",");

  const dismiss = async (g: DuplicateChargeGroup) => {
    setBusy(keyOf(g));
    setError(null);
    try {
      await dismissDuplicateCharges(g.transactions.map((t) => t.id));
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save that.");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="mb-4 rounded-lg border border-rose-200 bg-rose-50/60 p-4 dark:border-rose-900/60 dark:bg-rose-950/20">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-rose-900 dark:text-rose-200">
          <strong>{groups.length}</strong> possible double {groups.length === 1 ? "charge" : "charges"} — the same
          amount at the same place, a day or two apart.
        </p>
        <button
          onClick={() => setOpen((o) => !o)}
          className="rounded-md bg-rose-700 px-3 py-1.5 text-xs font-medium text-white hover:bg-rose-800"
        >
          {open ? "Hide" : "Review"}
        </button>
      </div>

      {open && (
        <>
          <p className="mt-2 text-xs text-rose-900/80 dark:text-rose-300/80">
            Worth checking with the merchant or your bank. Places you buy the same thing from all the time
            aren't flagged. Nothing here changes a transaction.
          </p>
          {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          <ul className="mt-3 divide-y divide-rose-200/70 dark:divide-rose-900/50">
            {groups.map((g) => {
              const key = keyOf(g);
              const cur = g.transactions[0]?.account_currency ?? "USD";
              return (
                <li key={key} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="text-sm font-medium text-slate-900 dark:text-slate-100">
                      {g.transactions.length}× {currencyPrecise(g.amount, cur)} at {g.merchant}
                    </p>
                    <p className="text-xs text-slate-600 dark:text-slate-400">
                      {g.transactions[0]?.account_name ?? "Account"} · {g.transactions.map((t) => dayMonthYear(t.date)).join(", ")}
                    </p>
                  </div>
                  <div className="flex items-center gap-2">
                    <button
                      onClick={() => onShow(g.merchant)}
                      className="rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-white dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    >
                      Show these
                    </button>
                    <button
                      disabled={busy !== null}
                      onClick={() => dismiss(g)}
                      className="rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-white disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    >
                      {busy === key ? "Saving…" : "Not a duplicate"}
                    </button>
                  </div>
                </li>
              );
            })}
          </ul>
        </>
      )}
    </div>
  );
}

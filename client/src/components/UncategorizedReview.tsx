import { useEffect, useState } from "react";
import { bulkCategorize, fetchUncategorizedMerchants } from "../api";
import type { UncategorizedMerchant } from "../types";
import { currencyPrecise } from "../format";

interface Props {
  accountId: number | null;
  /** Category names to offer. */
  categories: string[];
  /** Called after a group is categorized, so the transaction list can refresh. */
  onChanged: () => void;
  /** Start expanded -- when arriving from "Review now" after an import. */
  initiallyOpen?: boolean;
}

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

/**
 * What categorization couldn't place, one row per merchant. Choosing a category answers every
 * transaction in the row at once and teaches merchant memory, so the next statement from the same
 * merchant is categorized automatically. Renders nothing when there's nothing left to review.
 */
export function UncategorizedReview({ accountId, categories, onChanged, initiallyOpen = false }: Props) {
  const [groups, setGroups] = useState<UncategorizedMerchant[] | null>(null);
  const [open, setOpen] = useState(initiallyOpen);
  const [choices, setChoices] = useState<Record<string, string>>({});
  const [busyKey, setBusyKey] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [lastDone, setLastDone] = useState<string | null>(null);

  const load = () => {
    fetchUncategorizedMerchants(accountId)
      .then((r) => setGroups(r.merchants))
      .catch(() => setGroups([]));
  };

  useEffect(load, [accountId]);

  if (!groups || groups.length === 0) {
    return lastDone ? (
      <p className="mb-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
        All caught up — every transaction has a category. {lastDone}
      </p>
    ) : null;
  }

  const transactionCount = groups.reduce((n, g) => n + g.count, 0);
  const keyOf = (g: UncategorizedMerchant) => `${g.merchant}|${g.currency}`;

  const apply = async (g: UncategorizedMerchant) => {
    const category = choices[keyOf(g)];
    if (!category) return;
    setBusyKey(keyOf(g));
    setError(null);
    try {
      const r = await bulkCategorize(g.transaction_ids, category);
      setLastDone(`${g.merchant} → ${category} (${r.updated} transaction${r.updated === 1 ? "" : "s"}); remembered for next time.`);
      load();
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to categorize.");
    } finally {
      setBusyKey(null);
    }
  };

  return (
    <div className="mb-4 rounded-lg border border-amber-200 bg-amber-50/60 p-4 dark:border-amber-900/60 dark:bg-amber-950/20">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <p className="text-sm text-amber-900 dark:text-amber-200">
          <strong>{transactionCount}</strong> transaction{transactionCount === 1 ? "" : "s"} at{" "}
          <strong>{groups.length}</strong> merchant{groups.length === 1 ? "" : "s"} need a category.
        </p>
        <button
          onClick={() => setOpen((o) => !o)}
          className="rounded-md bg-amber-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-amber-700"
        >
          {open ? "Hide" : "Review by merchant"}
        </button>
      </div>

      {open && (
        <>
          <p className="mt-2 text-xs text-amber-800/80 dark:text-amber-300/80">
            One choice per merchant categorizes all of its transactions and is remembered, so the
            next statement from the same place is categorized automatically.
          </p>
          {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          {lastDone && <p className="mt-2 text-xs text-emerald-700 dark:text-emerald-400">✓ {lastDone}</p>}

          <ul className="mt-3 divide-y divide-amber-200/70 dark:divide-amber-900/50">
            {groups.map((g) => {
              const key = keyOf(g);
              return (
                <li key={key} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-2 py-2.5">
                  <div className="min-w-0 flex-1">
                    <p className="truncate text-sm font-medium text-slate-900 dark:text-slate-100">{g.merchant}</p>
                    <p className="text-xs text-slate-600 dark:text-slate-400">
                      {g.count} transaction{g.count === 1 ? "" : "s"} · {currencyPrecise(g.total, g.currency)}
                      {g.credits > 0 && g.debits > 0
                        ? ` · ${g.debits} out, ${g.credits} in`
                        : g.credits > 0
                          ? " · money in"
                          : ""}
                      {" · "}
                      {g.first_date === g.last_date ? shortDate(g.first_date) : `${shortDate(g.first_date)} – ${shortDate(g.last_date)}`}
                    </p>
                    {g.examples.some((e) => e.toUpperCase() !== g.merchant) && (
                      <p className="truncate text-[11px] text-slate-500" title={g.examples.join("\n")}>
                        e.g. {g.examples.join(" · ")}
                      </p>
                    )}
                  </div>
                  <div className="flex items-center gap-2">
                    <select
                      value={choices[key] ?? ""}
                      onChange={(e) => setChoices((c) => ({ ...c, [key]: e.target.value }))}
                      aria-label={`Category for ${g.merchant}`}
                      className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
                    >
                      <option value="">Choose…</option>
                      {categories.map((c) => (
                        <option key={c} value={c}>{c}</option>
                      ))}
                    </select>
                    <button
                      disabled={!choices[key] || busyKey !== null}
                      onClick={() => apply(g)}
                      className="rounded-md bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-40"
                    >
                      {busyKey === key ? "Saving…" : "Apply"}
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

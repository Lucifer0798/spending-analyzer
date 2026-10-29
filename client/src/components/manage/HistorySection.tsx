import { useEffect, useState } from "react";
import { fetchHistory, undoChange } from "../../api";
import type { HistoryEntry } from "../../types";
import { describeUndo } from "../../undo";
import type { Banner, Run } from "./types";

/** Shown before "Show more"; the server keeps up to 200. */
const PREVIEW = 10;

/** "just now", "5 min ago", "3 h ago", "2 days ago" -- `at` is the server's UTC "YYYY-MM-DD HH:MM:SS". */
function ago(at: string) {
  const minutes = Math.round((Date.now() - new Date(`${at.replace(" ", "T")}Z`).getTime()) / 60000);
  if (minutes < 1) return "just now";
  if (minutes < 60) return `${minutes} min ago`;
  const hours = Math.round(minutes / 60);
  if (hours < 24) return `${hours} h ago`;
  const days = Math.round(hours / 24);
  return days === 1 ? "yesterday" : `${days} days ago`;
}


/**
 * Recent changes to transactions -- edits, recategorizing, deletes, re-applied rules, imports --
 * with Undo. Undo never overwrites anything changed again since; it says how many it left.
 */
export function HistorySection({ run, banner, refreshKey }: { run: Run; banner: Banner; refreshKey: unknown }) {
  const [entries, setEntries] = useState<HistoryEntry[] | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);

  const load = () => {
    fetchHistory(50)
      .then((r) => setEntries(r.entries))
      .catch(() => setEntries(null));
  };

  // Follows the page's own reloads, so a change made in another section shows up here too.
  useEffect(load, [refreshKey]);

  const undo = async (e: HistoryEntry) => {
    setBusy(e.id);
    // Only set once the undo succeeded -- on failure `run` has already put the error in the banner.
    let message: string | null = null;
    await run(async () => {
      message = describeUndo(await undoChange(e.id));
    });
    if (message) banner.setNotice(message);
    setBusy(null);
    load();
  };

  const shown = entries ? (showAll ? entries : entries.slice(0, PREVIEW)) : [];

  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">History</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Recent changes to your transactions, newest first. Undo puts things back as they were — but never
        over something you've changed again since, and a deleted transaction's receipt can't come back.
      </p>

      {entries === null || entries.length === 0 ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Nothing yet. Edits, recategorizing, deletes and imports will show up here.
        </p>
      ) : (
        <>
          <ul className="mt-4 divide-y divide-slate-100 overflow-hidden rounded-lg border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-950">
            {shown.map((e) => (
              <li key={e.id} className={`flex flex-wrap items-center justify-between gap-3 px-4 py-2.5 ${e.undoneAt ? "opacity-60" : ""}`}>
                <div className="min-w-0 flex-1">
                  <p className="truncate text-sm text-slate-800 dark:text-slate-200" title={e.summary}>
                    {e.summary}
                  </p>
                  <p className="text-xs text-slate-500">
                    {ago(e.createdAt)}
                    {e.receiptsLost > 0 &&
                      ` · undo won't bring back ${e.receiptsLost} receipt${e.receiptsLost === 1 ? "" : "s"}`}
                  </p>
                </div>
                {e.undoneAt ? (
                  <span className="text-xs text-slate-500">Undone {ago(e.undoneAt)}</span>
                ) : (
                  <button
                    disabled={busy !== null}
                    onClick={() => undo(e)}
                    className="rounded px-2 py-1 text-xs font-medium text-indigo-600 hover:bg-indigo-50 disabled:opacity-40 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
                  >
                    {busy === e.id ? "Undoing…" : "Undo"}
                  </button>
                )}
              </li>
            ))}
          </ul>
          {entries.length > PREVIEW && (
            <button onClick={() => setShowAll((s) => !s)} className="mt-1 text-xs text-indigo-600 hover:underline dark:text-indigo-400">
              {showAll ? "Show fewer" : `Show all ${entries.length}`}
            </button>
          )}
        </>
      )}
    </section>
  );
}

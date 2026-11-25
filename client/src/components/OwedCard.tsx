import { useEffect, useState } from "react";
import { fetchOwed, recordRepayment, undoRepayment } from "../api";
import type { OwedResponse, OwedSplit } from "../types";
import { currencyPrecise } from "../format";
import { dayMonthYear } from "../dates";

interface Props {
  accountId: number | null;
}

/** Collapsed past this many -- the newest are the ones most likely still to be chased. */
const PREVIEW = 5;

const buttonClass =
  "rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800";

/**
 * Who owes you: split charges with money still to come back. "Paid back" records the rest as
 * repaid today; "Part" records some of it; a suggested credit (a Zelle from someone the split note
 * names, or one for exactly what's outstanding) records that credit -- which then stops counting as
 * income. Each offers Undo. Renders nothing when nobody owes you anything.
 */
export function OwedCard({ accountId }: Props) {
  const [data, setData] = useState<OwedResponse | null>(null);
  const [showAll, setShowAll] = useState(false);
  const [busy, setBusy] = useState<number | null>(null);
  const [partFor, setPartFor] = useState<number | null>(null);
  const [partAmount, setPartAmount] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [lastDone, setLastDone] = useState<{ id: number; text: string } | null>(null);

  const load = () => {
    fetchOwed(accountId)
      .then(setData)
      .catch(() => setData(null));
  };

  useEffect(load, [accountId]);

  if (!data || (data.splits.length === 0 && !lastDone)) return null;

  const record = async (s: OwedSplit, body: { amount?: number; repaymentId?: number }, text: string) => {
    setBusy(s.transaction.id);
    setError(null);
    try {
      const r = await recordRepayment(s.transaction.id, body);
      setLastDone({ id: r.id, text });
      setPartFor(null);
      setPartAmount("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't record that.");
    } finally {
      setBusy(null);
    }
  };

  const undo = async () => {
    if (!lastDone) return;
    try {
      await undoRepayment(lastDone.id);
      setLastDone(null);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Undo failed.");
    }
  };

  const shown = showAll ? data.splits : data.splits.slice(0, PREVIEW);

  return (
    <div className="mt-8 rounded-lg border border-violet-200 bg-violet-50/60 p-4 dark:border-violet-900/50 dark:bg-violet-950/20">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-violet-900 dark:text-violet-200">Owed to you</h2>
        <p className="text-sm text-violet-900 dark:text-violet-200">
          {data.totals.length === 0
            ? "All settled"
            : data.totals.map((t, i) => (
                <span key={t.currency}>
                  {i > 0 && " · "}
                  <strong className="tabular-nums">{currencyPrecise(t.outstanding, t.currency)}</strong> from {t.splits}{" "}
                  split{t.splits === 1 ? "" : "s"}
                </span>
              ))}
        </p>
      </div>

      {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
      {lastDone && (
        <p className="mt-2 text-xs text-emerald-700 dark:text-emerald-400">
          ✓ {lastDone.text} ·{" "}
          <button onClick={undo} className="font-medium underline">
            Undo
          </button>
        </p>
      )}

      <ul className="mt-3 divide-y divide-violet-200/70 dark:divide-violet-900/50">
        {shown.map((s) => {
          const t = s.transaction;
          const cur = t.account_currency ?? "USD";
          const id = t.id;
          return (
            <li key={id} className="py-3">
              <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
                <div className="min-w-0 grow basis-48">
                  <p className="truncate text-sm font-medium text-slate-900 dark:text-slate-100" title={t.description}>
                    {t.description}
                  </p>
                  <p className="text-xs text-slate-600 dark:text-slate-400">
                    {dayMonthYear(t.date)} · {currencyPrecise(t.amount, cur)}, yours {currencyPrecise(t.split_share ?? 0, cur)}
                    {t.split_note && <> · {t.split_note}</>}
                  </p>
                  {s.repaid > 0 && (
                    <p className="text-xs text-slate-500">
                      {currencyPrecise(s.repaid, cur)} of {currencyPrecise(s.owed, cur)} paid back
                    </p>
                  )}
                </div>
                <div className="flex flex-wrap items-center gap-2">
                  <span className="text-sm font-semibold tabular-nums text-violet-900 dark:text-violet-200">
                    {currencyPrecise(s.outstanding, cur)}
                  </span>
                  <button
                    disabled={busy !== null}
                    onClick={() =>
                      record(s, {}, `${currencyPrecise(s.outstanding, cur)} paid back on "${t.description}" — settled.`)
                    }
                    className={buttonClass}
                  >
                    {busy === id ? "Saving…" : "Paid back"}
                  </button>
                  <button
                    disabled={busy !== null}
                    onClick={() => {
                      setPartFor(partFor === id ? null : id);
                      setPartAmount("");
                    }}
                    className={buttonClass}
                  >
                    Part…
                  </button>
                </div>
              </div>

              {partFor === id && (
                <form
                  className="mt-2 flex flex-wrap items-center gap-2"
                  onSubmit={(e) => {
                    e.preventDefault();
                    const amount = Number(partAmount);
                    if (!Number.isFinite(amount) || amount <= 0) {
                      setError("Enter how much was paid back.");
                      return;
                    }
                    record(s, { amount }, `${currencyPrecise(amount, cur)} paid back on "${t.description}".`);
                  }}
                >
                  <input
                    autoFocus
                    inputMode="decimal"
                    value={partAmount}
                    onChange={(e) => setPartAmount(e.target.value)}
                    aria-label={`Amount paid back on ${t.description}`}
                    placeholder={`up to ${s.outstanding.toFixed(2)}`}
                    className="w-32 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
                  />
                  <button type="submit" disabled={busy !== null} className={buttonClass}>
                    Save
                  </button>
                </form>
              )}

              {s.suggestions.length > 0 && (
                <div className="mt-2 flex flex-wrap items-center gap-2 text-xs text-slate-600 dark:text-slate-400">
                  <span>Came back as?</span>
                  {s.suggestions.map((c) => (
                    <button
                      key={c.id}
                      disabled={busy !== null}
                      onClick={() =>
                        record(
                          s,
                          { repaymentId: c.id },
                          `${currencyPrecise(c.amount, cur)} from "${c.description}" recorded toward "${t.description}" — not counted as income.`
                        )
                      }
                      title="Record this credit as money paid back"
                      aria-label={`Record ${c.description}, ${currencyPrecise(c.amount, cur)} on ${dayMonthYear(c.date)}, as paid back on ${t.description}`}
                      className="rounded-full border border-violet-300 bg-white px-2.5 py-0.5 text-violet-800 hover:bg-violet-100 disabled:opacity-40 dark:border-violet-800 dark:bg-slate-900 dark:text-violet-300 dark:hover:bg-violet-950"
                    >
                      {c.description} · {currencyPrecise(c.amount, cur)} · {dayMonthYear(c.date)}
                    </button>
                  ))}
                </div>
              )}
            </li>
          );
        })}
      </ul>

      {data.splits.length > PREVIEW && (
        <button
          onClick={() => setShowAll((v) => !v)}
          className="mt-1 text-xs text-indigo-600 hover:underline dark:text-indigo-400"
        >
          {showAll ? "Show fewer" : `Show all ${data.splits.length}`}
        </button>
      )}
    </div>
  );
}

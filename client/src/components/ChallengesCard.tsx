import { useEffect, useRef, useState } from "react";
import { createChallenge, deleteChallenge, fetchCategories, fetchChallengeTemplates, fetchChallenges } from "../api";
import type { ChallengeProgress, ChallengeStatus, ChallengeTemplate } from "../types";
import { currency } from "../format";
import { addDays, dayMonth, todayIso } from "../dates";
import { changeIdOf } from "../undo";
import { UndoNote } from "./UndoNote";

interface Props {
  /** New challenges are scoped to the dashboard's account filter (null: every account). */
  accountId: number | null;
  /** Scroll here with finished ones shown -- read once at mount (a challenge alert was clicked). */
  focus?: boolean;
}

const LENGTHS = [
  { label: "1 week", days: 7 },
  { label: "2 weeks", days: 14 },
  { label: "30 days", days: 30 },
  { label: "Rest of this month", days: 0 },
] as const;

const STATUS: Record<ChallengeStatus, { label: string; className: string }> = {
  ACTIVE: { label: "Going", className: "bg-indigo-100 text-indigo-800 dark:bg-indigo-950/60 dark:text-indigo-300" },
  UPCOMING: { label: "Not started", className: "bg-slate-100 text-slate-700 dark:bg-slate-800 dark:text-slate-300" },
  COMPLETED: { label: "Kept", className: "bg-emerald-100 text-emerald-800 dark:bg-emerald-950/60 dark:text-emerald-300" },
  BROKEN: { label: "Broken", className: "bg-red-100 text-red-800 dark:bg-red-950/60 dark:text-red-300" },
};

/** The last day of the month `iso` is in. */
function endOfMonth(iso: string) {
  const d = new Date(`${iso}T00:00:00Z`);
  return new Date(Date.UTC(d.getUTCFullYear(), d.getUTCMonth() + 1, 0)).toISOString().slice(0, 10);
}

/**
 * One square per day: green for a day with nothing spent, red for one with spend (for a cap, only a
 * day over its even share of the limit is red -- smaller spends are amber), grey for days to come.
 */
function DayStrip({ p }: { p: ChallengeProgress }) {
  const allowance = p.challenge.limitAmount / p.daysTotal;
  return (
    <div className="mt-2 flex flex-wrap gap-0.5" aria-hidden>
      {p.days.map((d) => {
        const color =
          d.spent === null
            ? "bg-slate-200 dark:bg-slate-700"
            : d.spent === 0
              ? "bg-emerald-500"
              : p.challenge.limitAmount === 0 || d.spent > allowance
                ? "bg-red-500"
                : "bg-amber-400";
        const title =
          d.spent === null ? `${dayMonth(d.date)}: still to come` : `${dayMonth(d.date)}: ${currency(d.spent, 2, p.currency ?? "USD")}`;
        return <span key={d.date} title={title} className={`h-3 w-3 rounded-sm ${color}`} />;
      })}
    </div>
  );
}

function ChallengeRow({ p, onDelete }: { p: ChallengeProgress; onDelete: () => void }) {
  const c = p.challenge;
  const cur = p.currency ?? "USD";
  const status = STATUS[p.status];
  const today = todayIso();
  const through = c.endDate < today ? c.endDate : today;
  const lagging = p.status === "ACTIVE" && p.dataThrough !== null && p.dataThrough < through;

  return (
    <li className="py-3">
      <div className="flex items-start justify-between gap-2">
        <div className="min-w-0">
          <p className="text-sm font-medium text-slate-900 dark:text-slate-100">
            {c.name}{" "}
            <span className={`ml-1 rounded px-1.5 py-0.5 text-[11px] font-medium ${status.className}`}>
              {p.status === "UPCOMING" ? `Starts ${dayMonth(c.startDate)}` : status.label}
            </span>
          </p>
          <p className="text-xs text-slate-500">
            {dayMonth(c.startDate)}–{dayMonth(c.endDate)}
            {p.status === "ACTIVE" && ` · day ${p.daysElapsed} of ${p.daysTotal}`}
          </p>
        </div>
        <button
          onClick={onDelete}
          aria-label={`Delete "${c.name}"`}
          title="Delete"
          className="rounded px-1.5 text-slate-400 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
        >
          ×
        </button>
      </div>

      {!p.applicable ? (
        <p className="mt-1 text-xs text-slate-500">
          Your accounts use more than one currency, so spending across all of them can't be added up — this one
          needs to be for a single account.
        </p>
      ) : (
        p.status !== "UPCOMING" && (
          <>
            <p className="mt-1 text-sm text-slate-700 dark:text-slate-300">
              {c.limitAmount === 0
                ? p.status === "BROKEN"
                  ? `${currency(p.spent, 2, cur)} spent · ${p.streak}-day clear streak since`
                  : `${p.streak}-day streak, nothing spent`
                : `${currency(p.spent, 0, cur)} of ${currency(c.limitAmount, 0, cur)}` +
                  (p.status === "BROKEN"
                    ? " — over the limit"
                    : ` · ${currency(p.remaining ?? 0, 0, cur)} left` +
                      (p.status === "ACTIVE" ? (p.onPace ? " · on pace" : " · over pace") : ""))}
              {p.saved !== null && p.saved > 0 && (
                <span className="text-emerald-700 dark:text-emerald-400">
                  {" "}
                  · ≈{currency(p.saved, 0, cur)} saved vs your usual
                </span>
              )}
            </p>
            <DayStrip p={p} />
            {lagging && (
              <p className="mt-1 text-xs text-amber-700 dark:text-amber-400">
                Newest transaction is from {dayMonth(p.dataThrough!)} — anything since may not be imported yet.
              </p>
            )}
          </>
        )
      )}
    </li>
  );
}

/**
 * Short spending challenges -- "no Dining & Coffee for two weeks", "under $200 on Shopping this
 * month" -- tracked day by day from your transactions. Collapses to one line when there are none.
 */
export function ChallengesCard({ accountId, focus = false }: Props) {
  const [items, setItems] = useState<ChallengeProgress[] | null>(null);
  const [categories, setCategories] = useState<string[]>([]);
  const [open, setOpen] = useState(false);
  // A just-broken or just-kept challenge sits under "finished", so arriving from its alert shows them.
  const [showFinished, setShowFinished] = useState(focus);
  const sectionRef = useRef<HTMLElement>(null);
  // The last challenge started or deleted here, offered for undo. One at a time, like the other
  // pages: a new change replaces it, and each stays undoable from Manage → History.
  const [lastChange, setLastChange] = useState<{ id: number; text: string } | null>(null);
  const offerUndo = (id: number | null, text: string) => setLastChange(id === null ? null : { id, text });
  const loaded = items !== null;
  const [kind, setKind] = useState<"none" | "cap">("none");
  const [category, setCategory] = useState("");
  const [amount, setAmount] = useState("");
  const [length, setLength] = useState<number>(14);
  // Ideas from your own spending, fetched when the form opens; the one picked fills the fields.
  const [templates, setTemplates] = useState<ChallengeTemplate[]>([]);
  const [picked, setPicked] = useState<ChallengeTemplate | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    fetchChallenges()
      .then((r) => setItems(r.challenges))
      .catch(() => setItems(null));
  };
  useEffect(load, []);
  // Once the list is in (the card is empty until then), bring it into view for an alert's click.
  useEffect(() => {
    if (focus && loaded) sectionRef.current?.scrollIntoView({ block: "start" });
  }, [focus, loaded]);
  useEffect(() => {
    if (!open) return;
    fetchChallengeTemplates(accountId)
      .then((r) => setTemplates(r.templates))
      .catch(() => setTemplates([]));
  }, [open, accountId]);
  useEffect(() => {
    fetchCategories()
      .then((r) => setCategories(r.detailed.filter((c) => !c.is_income && !c.is_transfer).map((c) => c.name)))
      .catch(() => setCategories([]));
  }, []);

  if (items === null) return null;

  const apply = (t: ChallengeTemplate) => {
    setKind(t.limitAmount === 0 ? "none" : "cap");
    setAmount(t.limitAmount === 0 ? "" : String(t.limitAmount));
    setCategory(t.category ?? "");
    setLength(t.days);
    setPicked(t);
  };
  // The picked idea's title names the challenge only if it's started as suggested; once a field is
  // changed, the server's default name ("Under 150 on Shopping") describes it better.
  const pickedUnchanged =
    picked !== null &&
    kind === (picked.limitAmount === 0 ? "none" : "cap") &&
    (picked.limitAmount === 0 || Number(amount) === picked.limitAmount) &&
    category === (picked.category ?? "") &&
    length === picked.days;

  const current = items.filter((p) => p.status === "ACTIVE" || p.status === "UPCOMING");
  const finished = items.filter((p) => p.status === "COMPLETED" || p.status === "BROKEN");

  const start = async () => {
    const limit = kind === "none" ? 0 : Number(amount);
    if (kind === "cap" && (!Number.isFinite(limit) || limit <= 0)) {
      setError("Enter how much to stay under.");
      return;
    }
    const startDate = todayIso();
    const endDate = length === 0 ? endOfMonth(startDate) : addDays(startDate, length - 1);
    setBusy(true);
    setError(null);
    try {
      const created = await createChallenge({
        category: category || null,
        limitAmount: limit,
        startDate,
        endDate,
        accountId,
        name: pickedUnchanged ? picked!.title : undefined,
      });
      setPicked(null);
      offerUndo(changeIdOf(created), `Started "${created.challenge.name}"`);
      setOpen(false);
      setAmount("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't start that.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (p: ChallengeProgress) => {
    if (!confirm(`Delete the challenge "${p.challenge.name}"?`)) return;
    try {
      offerUndo(changeIdOf(await deleteChallenge(p.challenge.id)), `Deleted "${p.challenge.name}"`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't delete that.");
    }
  };

  const fieldClass = "rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900";

  return (
    <section ref={sectionRef} className="mb-6 scroll-mt-20 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-950">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Challenges</h2>
        {!open && (
          <button
            onClick={() => setOpen(true)}
            className="rounded-md px-2 py-1 text-xs font-medium text-indigo-600 hover:bg-indigo-50 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
          >
            + Start a challenge
          </button>
        )}
      </div>

      {items.length === 0 && !open && (
        <p className="mt-1 text-xs text-slate-500">
          Try a short one — no eating out for two weeks, or a cap on shopping this month — and see how it goes day by day.
        </p>
      )}

      {open && (
        <div className="mt-3 flex flex-wrap items-center gap-2 rounded-md bg-slate-50 p-3 text-sm dark:bg-slate-900">
          {templates.length > 0 && (
            <div className="w-full">
              <p className="text-xs text-slate-500">Ideas from your last three months:</p>
              <div className="mt-1 flex flex-wrap gap-1.5">
                {templates.map((t) => (
                  <button
                    key={t.key}
                    onClick={() => apply(t)}
                    title={t.basis}
                    aria-pressed={picked?.key === t.key}
                    className={`rounded-full border px-2.5 py-0.5 text-xs ${
                      picked?.key === t.key
                        ? "border-indigo-500 bg-indigo-50 text-indigo-800 dark:bg-indigo-950/50 dark:text-indigo-300"
                        : "border-slate-300 text-slate-700 hover:bg-white dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                    }`}
                  >
                    {t.title}
                  </button>
                ))}
              </div>
              {picked && <p className="mt-1 text-xs text-slate-500">{picked.basis}. Adjust anything below, then Start.</p>}
            </div>
          )}
          <select aria-label="Kind of challenge" value={kind} onChange={(e) => setKind(e.target.value as "none" | "cap")} className={fieldClass}>
            <option value="none">No spending on</option>
            <option value="cap">Spend under</option>
          </select>
          {kind === "cap" && (
            <input
              aria-label="Amount to stay under"
              type="number"
              min="1"
              step="1"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="200"
              className={`${fieldClass} w-24`}
            />
          )}
          {kind === "cap" && <span className="text-slate-600 dark:text-slate-400">on</span>}
          <select aria-label="Category" value={category} onChange={(e) => setCategory(e.target.value)} className={fieldClass}>
            <option value="">all spending</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <span className="text-slate-600 dark:text-slate-400">for</span>
          <select aria-label="How long" value={length} onChange={(e) => setLength(Number(e.target.value))} className={fieldClass}>
            {LENGTHS.map((l) => (
              <option key={l.label} value={l.days}>
                {l.label}
              </option>
            ))}
          </select>
          <span className="text-xs text-slate-500">starting today</span>
          <span className="ml-auto flex gap-1">
            <button
              disabled={busy}
              onClick={start}
              className="rounded-md bg-indigo-600 px-3 py-1 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
            >
              {busy ? "Starting…" : "Start"}
            </button>
            <button
              onClick={() => {
                setOpen(false);
                setError(null);
              }}
              className="rounded-md px-3 py-1 text-sm text-slate-600 hover:bg-slate-200 dark:text-slate-400 dark:hover:bg-slate-800"
            >
              Cancel
            </button>
          </span>
        </div>
      )}
      {error && <p className="mt-2 text-xs text-red-600 dark:text-red-400">{error}</p>}

      {lastChange && (
        <div className="mt-3 [&>*]:mb-0">
          <UndoNote
            key={lastChange.id}
            text={lastChange.text}
            changeId={lastChange.id}
            onUndone={load}
            onDismiss={() => setLastChange(null)}
          />
        </div>
      )}

      {current.length > 0 && (
        <ul className="mt-1 divide-y divide-slate-100 dark:divide-slate-800">
          {current.map((p) => (
            <ChallengeRow key={p.challenge.id} p={p} onDelete={() => remove(p)} />
          ))}
        </ul>
      )}

      {finished.length > 0 && (
        <>
          <button
            onClick={() => setShowFinished((s) => !s)}
            className="mt-2 text-xs text-indigo-600 hover:underline dark:text-indigo-400"
          >
            {showFinished ? "Hide finished" : `Show ${finished.length} finished`}
          </button>
          {showFinished && (
            <ul className="mt-1 divide-y divide-slate-100 opacity-80 dark:divide-slate-800">
              {finished.map((p) => (
                <ChallengeRow key={p.challenge.id} p={p} onDelete={() => remove(p)} />
              ))}
            </ul>
          )}
        </>
      )}
    </section>
  );
}

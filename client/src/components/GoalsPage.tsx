import { useEffect, useState } from "react";
import {
  addGoalContribution,
  createGoal,
  deleteGoal,
  deleteGoalContribution,
  fetchAccounts,
  fetchGoalContributions,
  fetchGoals,
  updateGoal,
} from "../api";
import type { GoalContribution, GoalProgress } from "../types";
import { currency, currencyPrecise } from "../format";

function todayIsoDate() {
  return new Date().toISOString().slice(0, 10);
}

function dateLabel(iso: string) {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function ContributionHistory({
  goalId,
  currencyCode,
  onChanged,
}: {
  goalId: number;
  currencyCode: string;
  onChanged: () => void;
}) {
  const [history, setHistory] = useState<GoalContribution[] | null>(null);

  useEffect(() => {
    fetchGoalContributions(goalId).then(setHistory).catch(() => setHistory([]));
  }, [goalId]);

  if (!history) {
    return <p className="mt-2 text-xs text-slate-500">Loading…</p>;
  }
  if (history.length === 0) {
    return <p className="mt-2 text-xs text-slate-500">Nothing logged yet.</p>;
  }

  return (
    <ul className="mt-2 space-y-1">
      {history.map((c) => (
        <li key={c.id} className="flex items-center justify-between gap-2 text-xs">
          <span className="text-slate-600 dark:text-slate-400">
            {dateLabel(c.date)}
            {c.note && <span className="text-slate-400"> · {c.note}</span>}
          </span>
          <span className="flex items-center gap-2">
            <span className={c.amount < 0 ? "text-red-600 dark:text-red-400" : "text-slate-800 dark:text-slate-200"}>
              {c.amount < 0 ? "-" : "+"}
              {currencyPrecise(Math.abs(c.amount), currencyCode)}
            </span>
            <button
              onClick={() =>
                deleteGoalContribution(goalId, c.id).then(() => {
                  setHistory((h) => h?.filter((x) => x.id !== c.id) ?? null);
                  onChanged();
                })
              }
              className="text-slate-400 hover:text-red-600"
              title="Remove this entry"
            >
              ×
            </button>
          </span>
        </li>
      ))}
    </ul>
  );
}

function GoalCard({ goal, onChanged }: { goal: GoalProgress; onChanged: () => void }) {
  const [showHistory, setShowHistory] = useState(false);
  const [showContributeForm, setShowContributeForm] = useState(false);
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(todayIsoDate());
  const [note, setNote] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const filled = Math.min(goal.percentComplete, 100);
  const color = goal.achieved ? "#0ca30c" : "#4f46e5";

  const submitContribution = async () => {
    const parsed = Number(amount);
    if (!amount.trim() || !Number.isFinite(parsed) || parsed === 0) return;

    setBusy(true);
    setError(null);
    try {
      await addGoalContribution(goal.id, { amount: parsed, date, note: note.trim() || undefined });
      setAmount("");
      setNote("");
      setShowContributeForm(false);
      setShowHistory(true);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to log contribution.");
    } finally {
      setBusy(false);
    }
  };

  const handleEdit = () => {
    const name = prompt("Goal name:", goal.name);
    if (name === null) return;
    const targetAmount = prompt("Target amount:", String(goal.targetAmount));
    if (targetAmount === null) return;
    const parsed = Number(targetAmount);
    if (!Number.isFinite(parsed) || parsed <= 0) {
      alert("Target amount must be greater than zero.");
      return;
    }
    const targetDate = prompt(
      "Target date (YYYY-MM-DD), or leave as-is:",
      goal.targetDate ?? ""
    );
    if (targetDate === null) return;

    updateGoal(goal.id, {
      name: name.trim() || goal.name,
      target_amount: parsed,
      ...(targetDate.trim() ? { target_date: targetDate.trim() } : {}),
    }).then(onChanged);
  };

  const handleDelete = () => {
    if (confirm(`Delete "${goal.name}"? This also removes everything logged toward it.`)) {
      deleteGoal(goal.id).then(onChanged);
    }
  };

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="font-medium text-slate-800 dark:text-slate-200">{goal.name}</h3>
          {goal.targetDate && (
            <p className="text-xs text-slate-500">
              {goal.achieved ? "Reached before " : "Target: "}
              {dateLabel(goal.targetDate)}
            </p>
          )}
        </div>
        <div className="flex shrink-0 gap-1">
          <button
            onClick={handleEdit}
            className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            Edit
          </button>
          <button
            onClick={handleDelete}
            className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
          >
            Delete
          </button>
        </div>
      </div>

      <div className="mt-3 flex items-baseline justify-between gap-2 text-sm">
        <span className="font-medium text-slate-800 dark:text-slate-200">
          {currency(goal.saved, 0, goal.currency)} of {currency(goal.targetAmount, 0, goal.currency)}
        </span>
        {goal.achieved && (
          <span className="rounded bg-emerald-100 px-1.5 py-0.5 text-[10px] font-medium uppercase text-emerald-800 dark:bg-emerald-950/50 dark:text-emerald-300">
            Achieved
          </span>
        )}
      </div>

      <div className="mt-1.5 h-2 overflow-hidden rounded-full bg-slate-100 dark:bg-slate-800">
        <div
          className="h-full rounded-full transition-[width]"
          style={{ width: `${filled}%`, backgroundColor: color }}
        />
      </div>

      <p className="mt-1 text-xs text-slate-500">
        {goal.achieved
          ? `${Math.round(goal.percentComplete)}% funded`
          : `${currency(goal.remaining, 0, goal.currency)} to go · ${Math.round(goal.percentComplete)}%`}
      </p>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          onClick={() => setShowContributeForm((s) => !s)}
          className="rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-indigo-700"
        >
          Log contribution
        </button>
        {goal.contributionCount > 0 && (
          <button
            onClick={() => setShowHistory((s) => !s)}
            className="rounded-md px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            {showHistory ? "Hide" : "Show"} history ({goal.contributionCount})
          </button>
        )}
      </div>

      {showContributeForm && (
        <div className="mt-3 rounded-md border border-dashed border-slate-300 p-3 dark:border-slate-700">
          {error && <p className="mb-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="number"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder="Amount"
              title="A negative amount records money taken back out"
              className="w-28 rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <input
              value={note}
              onChange={(e) => setNote(e.target.value)}
              placeholder="Note (optional)"
              className="min-w-32 flex-1 rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <button
              disabled={busy || !amount.trim()}
              onClick={submitContribution}
              className="rounded px-3 py-1 text-sm font-medium text-indigo-600 hover:bg-indigo-50 disabled:opacity-40 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
            >
              Save
            </button>
          </div>
        </div>
      )}

      {showHistory && <ContributionHistory goalId={goal.id} currencyCode={goal.currency} onChanged={onChanged} />}
    </div>
  );
}

export function GoalsPage() {
  const [goals, setGoals] = useState<GoalProgress[] | null>(null);
  const [currencies, setCurrencies] = useState<string[]>(["USD"]);
  const [error, setError] = useState<string | null>(null);

  const [newName, setNewName] = useState("");
  const [newTarget, setNewTarget] = useState("");
  const [newDate, setNewDate] = useState("");
  const [newCurrency, setNewCurrency] = useState("USD");
  const [creating, setCreating] = useState(false);

  const load = () => {
    fetchGoals()
      .then(setGoals)
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load goals."));
  };

  useEffect(() => {
    load();
    fetchAccounts().then((r) => setCurrencies(r.currencies)).catch(() => {});
  }, []);

  const handleCreate = async () => {
    const parsed = Number(newTarget);
    if (!newName.trim() || !Number.isFinite(parsed) || parsed <= 0) return;

    setCreating(true);
    setError(null);
    try {
      await createGoal({
        name: newName.trim(),
        target_amount: parsed,
        currency: newCurrency,
        ...(newDate ? { target_date: newDate } : {}),
      });
      setNewName("");
      setNewTarget("");
      setNewDate("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to create goal.");
    } finally {
      setCreating(false);
    }
  };

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Savings goals</h1>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        A target amount, and optionally a date to reach it by. Progress comes from contributions
        you log by hand — this app tracks categorized spending, not account balances, so there's
        no automatic way to know how much you've actually saved.
      </p>

      {error && (
        <div className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}

      <div className="mt-6 rounded-lg border border-dashed border-slate-300 p-4 dark:border-slate-700">
        <h2 className="text-sm font-medium text-slate-800 dark:text-slate-200">New goal</h2>
        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            value={newName}
            onChange={(e) => setNewName(e.target.value)}
            placeholder="Goal name, e.g. Emergency fund"
            className="min-w-48 flex-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <input
            type="number"
            min="0"
            step="0.01"
            value={newTarget}
            onChange={(e) => setNewTarget(e.target.value)}
            placeholder="Target amount"
            className="w-32 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <select
            value={newCurrency}
            onChange={(e) => setNewCurrency(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            {currencies.map((c) => (
              <option key={c} value={c}>{c}</option>
            ))}
          </select>
          <input
            type="date"
            value={newDate}
            onChange={(e) => setNewDate(e.target.value)}
            title="Target date (optional)"
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <button
            disabled={creating || !newName.trim() || !newTarget.trim()}
            onClick={handleCreate}
            className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Add goal
          </button>
        </div>
      </div>

      {goals === null ? (
        <p className="mt-8 text-center text-sm text-slate-500">Loading…</p>
      ) : goals.length === 0 ? (
        <p className="mt-8 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No goals yet. Add one above to start tracking progress toward it.
        </p>
      ) : (
        <div className="mt-6 grid grid-cols-1 gap-4 md:grid-cols-2">
          {goals.map((g) => (
            <GoalCard key={g.id} goal={g} onChanged={load} />
          ))}
        </div>
      )}
    </div>
  );
}

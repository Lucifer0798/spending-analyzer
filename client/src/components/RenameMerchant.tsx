import { useState } from "react";
import { clearMerchantNames, renameMerchants, undoChange } from "../api";
import { changeIdOf } from "../undo";

interface Props {
  /** The merchant keys this row stands for -- renamed together, so a merged group stays merged. */
  keys: string[];
  /** What the row shows now. */
  label: string;
  /** Whether that's a name (rather than the key itself), so "Remove name" makes sense. */
  named: boolean;
  /** Reloads whatever lists the merchant, after a rename or its undo. */
  onRenamed: () => void;
}

/**
 * A small ✎ beside a merchant wherever it's listed -- top merchants, the year in review, recurring --
 * to name it on the spot rather than in Manage → Merchant names. A name already used by other
 * merchants merges this one with them, as it does there. Saving offers Undo right there; every
 * change is in History too.
 */
export function RenameMerchant({ keys, label, named, onRenamed }: Props) {
  const [editing, setEditing] = useState(false);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [lastChange, setLastChange] = useState<number | null>(null);

  const act = async (action: () => Promise<unknown>) => {
    setBusy(true);
    setError(null);
    try {
      const id = changeIdOf(await action());
      setEditing(false);
      setLastChange(id);
      onRenamed();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't save that.");
    } finally {
      setBusy(false);
    }
  };

  const undo = async () => {
    if (lastChange === null) return;
    setBusy(true);
    try {
      await undoChange(lastChange);
      setLastChange(null);
      onRenamed();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Couldn't undo that.");
    } finally {
      setBusy(false);
    }
  };

  const linkClass = "rounded px-1 text-xs text-indigo-600 hover:underline disabled:opacity-40 dark:text-indigo-400";

  if (!editing) {
    return (
      <span className="inline-flex items-baseline gap-1">
        <button
          onClick={() => {
            setDraft(named ? label : "");
            setError(null);
            setEditing(true);
          }}
          aria-label={`Rename ${label}`}
          title={keys.length > 1 ? `Rename (${keys.length} spellings: ${keys.join(", ")})` : "Rename"}
          className="rounded px-1 text-xs text-slate-400 hover:bg-slate-100 hover:text-indigo-600 dark:hover:bg-slate-800 dark:hover:text-indigo-400"
        >
          ✎
        </button>
        {lastChange !== null && (
          <span className="text-xs text-slate-500">
            Saved ·{" "}
            <button disabled={busy} onClick={undo} className={linkClass}>
              Undo
            </button>
          </span>
        )}
        {error && <span className="text-xs text-red-600 dark:text-red-400">{error}</span>}
      </span>
    );
  }

  return (
    <span className="mt-1 flex w-full flex-wrap items-center gap-1.5">
      <input
        autoFocus
        aria-label={`New name for ${label}`}
        value={draft}
        onChange={(e) => setDraft(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter" && draft.trim()) act(() => renameMerchants(keys, draft.trim()));
          if (e.key === "Escape") setEditing(false);
        }}
        placeholder={keys[0]}
        maxLength={80}
        className="w-44 rounded-md border border-slate-300 bg-white px-2 py-0.5 text-sm dark:border-slate-700 dark:bg-slate-900"
      />
      <button disabled={busy || !draft.trim()} onClick={() => act(() => renameMerchants(keys, draft.trim()))} className={linkClass}>
        {busy ? "Saving…" : "Save"}
      </button>
      {named && (
        <button disabled={busy} onClick={() => act(() => clearMerchantNames(keys))} className={linkClass}>
          Remove name
        </button>
      )}
      <button onClick={() => setEditing(false)} className="rounded px-1 text-xs text-slate-500 hover:underline">
        Cancel
      </button>
      {error && <span className="w-full text-xs text-red-600 dark:text-red-400">{error}</span>}
    </span>
  );
}

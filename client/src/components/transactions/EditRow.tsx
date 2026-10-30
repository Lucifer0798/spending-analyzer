import { useState } from "react";
import { updateTransaction } from "../../api";
import type { TransactionWithTags } from "../../types";
import { CARD_CELL, CARD_ROW } from "./layout";
import type { OfferUndo, SetError } from "./types";

interface EditDraft {
  date: string;
  description: string;
  amount: string;
  type: "debit" | "credit";
}

interface Props {
  t: TransactionWithTags;
  selected: boolean;
  onToggleSelected: () => void;
  showAccountColumn: boolean;
  colorByCategory: Record<string, string>;
  /** Leaves edit mode -- after Cancel, after a save, or when nothing was changed. */
  onClose: () => void;
  /** Reloads the page of transactions. */
  onSaved: () => void;
  offerUndo: OfferUndo;
  setError: SetError;
}

const inputClass =
  "w-full rounded border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900";

/** A row opened for editing its date, description, amount and direction, in place. */
export function EditRow({ t, selected, onToggleSelected, showAccountColumn, colorByCategory, onClose, onSaved, offerUndo, setError }: Props) {
  const [draft, setDraft] = useState<EditDraft>({
    date: t.date,
    description: t.description,
    amount: String(t.amount),
    type: t.type,
  });
  const [saving, setSaving] = useState(false);

  const saveEdit = async () => {
    const amount = Number(draft.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      setError("Amount must be a number greater than zero.");
      return;
    }

    // Send only what actually changed, so an untouched field is never overwritten.
    const changes: Parameters<typeof updateTransaction>[1] = {};
    if (draft.date !== t.date) changes.date = draft.date;
    if (draft.description.trim() !== t.description) changes.description = draft.description.trim();
    if (amount !== t.amount) changes.amount = amount;
    if (draft.type !== t.type) changes.type = draft.type;

    if (Object.keys(changes).length === 0) {
      onClose();
      return;
    }

    setSaving(true);
    setError(null);
    try {
      const r = await updateTransaction(t.id, changes);
      offerUndo(r.changeId, `Saved changes to "${r.transaction.description}"`);
      onClose();
      onSaved();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save changes.");
    } finally {
      setSaving(false);
    }
  };

  return (
    <tr className={`${CARD_ROW} bg-indigo-50/50 dark:bg-indigo-950/20`}>
      <td className={`${CARD_CELL} col-start-1 row-start-1`}>
        <input type="checkbox" checked={selected} onChange={onToggleSelected} />
      </td>
      <td className={`${CARD_CELL} col-start-2 row-start-2`}>
        <input
          type="date"
          value={draft.date}
          onChange={(e) => setDraft({ ...draft, date: e.target.value })}
          className={inputClass}
        />
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-1`} colSpan={showAccountColumn ? 2 : 1}>
        <input
          value={draft.description}
          onChange={(e) => setDraft({ ...draft, description: e.target.value })}
          className={inputClass}
        />
      </td>
      <td className={`${CARD_CELL} col-start-3 row-start-2`}>
        <div className="flex items-center gap-1">
          <select
            value={draft.type}
            onChange={(e) => setDraft({ ...draft, type: e.target.value as "debit" | "credit" })}
            className={inputClass}
          >
            <option value="debit">−</option>
            <option value="credit">+</option>
          </select>
          <input
            type="number"
            step="0.01"
            min="0.01"
            value={draft.amount}
            onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
            className={`${inputClass} text-right`}
          />
        </div>
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-3 text-xs text-slate-500`}>
        {t.category && colorByCategory[t.category] && (
          <span
            className="mr-1.5 inline-block h-2 w-2 rounded-full align-middle"
            style={{ backgroundColor: colorByCategory[t.category] }}
          />
        )}
        {t.category ?? "Uncategorized"}
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-4 text-xs text-slate-500`}>
        {t.tags.length > 0 ? t.tags.join(", ") : "—"}
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-5 whitespace-nowrap md:text-right`}>
        <button
          disabled={saving}
          onClick={saveEdit}
          className="rounded bg-indigo-600 px-2 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {saving ? "Saving…" : "Save"}
        </button>
        <button
          disabled={saving}
          onClick={onClose}
          className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
        >
          Cancel
        </button>
      </td>
    </tr>
  );
}

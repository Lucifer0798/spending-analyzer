import { useState } from "react";
import { bulkAddTag, bulkCategorize, bulkDeleteTransactions } from "../../api";
import type { OfferUndo, SetError } from "./types";

interface Props {
  selectedIds: Set<number>;
  categories: string[];
  onClear: () => void;
  /** Reloads the page of transactions (which also clears the selection). */
  onChanged: () => void;
  /** The tag list (filter and suggestions) changed. */
  onTagsChanged: () => void;
  offerUndo: OfferUndo;
  setError: SetError;
}

/**
 * The bar shown while rows are ticked: set a category, add a tag, or delete them all at once.
 * Stays mounted (rendering nothing) with no selection, so a half-made choice survives re-ticking.
 */
export function BulkActions({ selectedIds, categories, onClear, onChanged, onTagsChanged, offerUndo, setError }: Props) {
  const [bulkCategoryChoice, setBulkCategoryChoice] = useState("");
  const [bulkTagInput, setBulkTagInput] = useState("");
  const [bulkBusy, setBulkBusy] = useState(false);

  if (selectedIds.size === 0) return null;

  const handleBulkCategorize = async () => {
    if (!bulkCategoryChoice) return;
    setBulkBusy(true);
    setError(null);
    try {
      const r = await bulkCategorize([...selectedIds], bulkCategoryChoice);
      offerUndo(r.changeId, `Categorized ${r.updated} transaction${r.updated === 1 ? "" : "s"} as ${bulkCategoryChoice}`);
      setBulkCategoryChoice("");
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to categorize the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  const handleBulkTag = async () => {
    const name = bulkTagInput.trim();
    if (!name) return;
    setBulkBusy(true);
    setError(null);
    try {
      await bulkAddTag([...selectedIds], name);
      setBulkTagInput("");
      onChanged();
      onTagsChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to tag the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  const handleBulkDelete = async () => {
    if (!confirm(`Delete ${selectedIds.size} selected transaction${selectedIds.size === 1 ? "" : "s"}?`)) return;
    setBulkBusy(true);
    setError(null);
    try {
      const r = await bulkDeleteTransactions([...selectedIds]);
      offerUndo(r.changeId, `Deleted ${r.deleted} transaction${r.deleted === 1 ? "" : "s"}`);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  return (
    <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 p-3 text-sm dark:border-indigo-900/50 dark:bg-indigo-950/30">
      <span className="font-medium text-indigo-900 dark:text-indigo-200">
        {selectedIds.size} selected
      </span>
      <select
        value={bulkCategoryChoice}
        onChange={(e) => setBulkCategoryChoice(e.target.value)}
        className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
      >
        <option value="">Set category…</option>
        {categories.map((c) => (
          <option key={c} value={c}>
            {c}
          </option>
        ))}
      </select>
      <button
        disabled={bulkBusy || !bulkCategoryChoice}
        onClick={handleBulkCategorize}
        className="rounded-md bg-indigo-600 px-3 py-1 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
      >
        Apply
      </button>
      <input
        list="tag-suggestions"
        value={bulkTagInput}
        onChange={(e) => setBulkTagInput(e.target.value)}
        onKeyDown={(e) => {
          if (e.key === "Enter") handleBulkTag();
        }}
        placeholder="Add tag…"
        className="w-28 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
      />
      <button
        disabled={bulkBusy || !bulkTagInput.trim()}
        onClick={handleBulkTag}
        className="rounded-md px-3 py-1 text-sm font-medium text-indigo-600 hover:bg-indigo-100 disabled:opacity-50 dark:text-indigo-400 dark:hover:bg-indigo-950/50"
      >
        Add tag
      </button>
      <button
        disabled={bulkBusy}
        onClick={handleBulkDelete}
        className="rounded-md px-3 py-1 text-sm font-medium text-red-600 hover:bg-red-100 disabled:opacity-50 dark:hover:bg-red-950/40"
      >
        Delete
      </button>
      <button
        onClick={onClear}
        className="ml-auto rounded-md px-3 py-1 text-sm text-slate-600 hover:bg-slate-200 dark:text-slate-400 dark:hover:bg-slate-800"
      >
        Clear selection
      </button>
    </div>
  );
}

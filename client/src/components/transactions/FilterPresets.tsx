import { useEffect, useState } from "react";
import { deleteFilterPreset, fetchFilterPresets, saveFilterPreset } from "../../api";
import type { DateRangeValue, FilterPreset } from "../../types";
import type { SetError } from "./types";

/** The page's filters as they stand -- what "Save current filters" stores. */
export interface CurrentFilters {
  category: string;
  tag: string;
  search: string;
  accountId: number | null;
  range: DateRangeValue;
}

/** Saved filter combinations as chips: one click applies a preset, × deletes it. */
export function FilterPresets({
  filters,
  onApply,
  setError,
}: {
  filters: CurrentFilters;
  onApply: (preset: FilterPreset) => void;
  setError: SetError;
}) {
  const [presets, setPresets] = useState<FilterPreset[]>([]);

  const loadPresets = () => {
    fetchFilterPresets().then(setPresets).catch(() => {});
  };
  useEffect(loadPresets, []);

  const saveCurrentAsPreset = async () => {
    const name = prompt("Save the current filters as a preset named:");
    if (!name || !name.trim()) return;
    setError(null);
    try {
      await saveFilterPreset({
        name: name.trim(),
        category: filters.category || undefined,
        tag: filters.tag || undefined,
        search: filters.search || undefined,
        account_id: filters.accountId,
        date_from: filters.range.from || undefined,
        date_to: filters.range.to || undefined,
      });
      loadPresets();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save the preset.");
    }
  };

  const handleDeletePreset = async (preset: FilterPreset) => {
    if (!confirm(`Delete the preset "${preset.name}"?`)) return;
    try {
      await deleteFilterPreset(preset.id);
      loadPresets();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete the preset.");
    }
  };

  return (
    <div className="mb-4 flex flex-wrap items-center gap-2">
      {presets.map((p) => (
        <span
          key={p.id}
          className="inline-flex items-center gap-1 rounded-full border border-slate-300 bg-white px-3 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
        >
          <button
            onClick={() => onApply(p)}
            className="font-medium text-slate-700 hover:text-indigo-600 dark:text-slate-300 dark:hover:text-indigo-400"
            title="Apply this preset — category, tag, search, account, and date range"
          >
            {p.name}
          </button>
          <button
            onClick={() => handleDeletePreset(p)}
            className="text-slate-400 hover:text-red-600"
            title={`Delete "${p.name}"`}
          >
            ×
          </button>
        </span>
      ))}
      <button
        onClick={saveCurrentAsPreset}
        className="rounded-full border border-dashed border-slate-300 px-3 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-400 dark:hover:bg-slate-800"
      >
        + Save current filters as preset…
      </button>
    </div>
  );
}

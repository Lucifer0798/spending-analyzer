import { useState } from "react";
import type { DateBounds, DateRangeValue } from "../types";
import { ALL_TIME } from "../types";

interface Props {
  value: DateRangeValue;
  bounds: DateBounds | null;
  onChange: (range: DateRangeValue) => void;
}

type PresetId = "all" | "3m" | "6m" | "12m" | "year" | "custom";

const PRESETS: { id: PresetId; label: string }[] = [
  { id: "all", label: "All time" },
  { id: "3m", label: "Last 3 months" },
  { id: "6m", label: "Last 6 months" },
  { id: "12m", label: "Last 12 months" },
  { id: "year", label: "This year" },
  { id: "custom", label: "Custom…" },
];

function iso(d: Date): string {
  return d.toISOString().slice(0, 10);
}

/**
 * Presets are anchored to the most recent transaction rather than today's date.
 * Statements are usually imported after the fact, so "last 3 months" measured from
 * today would show nothing at all for data that ends a few months back.
 */
function rangeForPreset(preset: PresetId, anchorDate: string): DateRangeValue {
  const anchor = new Date(`${anchorDate}T00:00:00Z`);

  const monthsBack = (months: number): DateRangeValue => {
    const start = new Date(anchor);
    start.setUTCMonth(start.getUTCMonth() - months + 1);
    start.setUTCDate(1);
    return { from: iso(start), to: anchorDate };
  };

  switch (preset) {
    case "3m":
      return monthsBack(3);
    case "6m":
      return monthsBack(6);
    case "12m":
      return monthsBack(12);
    case "year":
      return { from: `${anchor.getUTCFullYear()}-01-01`, to: anchorDate };
    default:
      return ALL_TIME;
  }
}

export function DateRangePicker({ value, bounds, onChange }: Props) {
  const [preset, setPreset] = useState<PresetId>("all");
  const [showCustom, setShowCustom] = useState(false);

  const anchor = bounds?.latest ?? iso(new Date());

  const handlePreset = (id: PresetId) => {
    setPreset(id);
    if (id === "custom") {
      setShowCustom(true);
      return;
    }
    setShowCustom(false);
    onChange(rangeForPreset(id, anchor));
  };

  return (
    <div className="flex flex-wrap items-center gap-2">
      <select
        value={preset}
        onChange={(e) => handlePreset(e.target.value as PresetId)}
        className="rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
        title="Filter by date"
      >
        {PRESETS.map((p) => (
          <option key={p.id} value={p.id}>
            {p.label}
          </option>
        ))}
      </select>

      {showCustom && (
        <span className="flex items-center gap-1">
          <input
            type="date"
            value={value.from ?? ""}
            min={bounds?.earliest ?? undefined}
            max={bounds?.latest ?? undefined}
            onChange={(e) => onChange({ ...value, from: e.target.value || null })}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <span className="text-xs text-slate-500">to</span>
          <input
            type="date"
            value={value.to ?? ""}
            min={value.from ?? bounds?.earliest ?? undefined}
            max={bounds?.latest ?? undefined}
            onChange={(e) => onChange({ ...value, to: e.target.value || null })}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
        </span>
      )}
    </div>
  );
}

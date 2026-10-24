import { useEffect, useRef, useState } from "react";

export interface AlertItem {
  key: string;
  /** "serious" (over budget, running low) or "nudge" (a statement due). */
  tone: "serious" | "nudge";
  title: string;
  detail: string;
  onSelect: () => void;
}

const TONE_DOT: Record<AlertItem["tone"], string> = {
  serious: "bg-red-500",
  nudge: "bg-amber-500",
};

/**
 * Everything that wants attention, behind one header button instead of a badge each -- three
 * badges and eight tabs wrapped onto several lines on a phone. The button takes the colour of the
 * most serious item; each item links where its badge used to. Renders nothing when there's nothing.
 */
export function AlertsMenu({ items }: { items: AlertItem[] }) {
  const [open, setOpen] = useState(false);
  const rootRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (!open) return;
    const onPointer = (e: PointerEvent) => {
      if (!rootRef.current?.contains(e.target as Node)) setOpen(false);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") setOpen(false);
    };
    document.addEventListener("pointerdown", onPointer);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("pointerdown", onPointer);
      document.removeEventListener("keydown", onKey);
    };
  }, [open]);

  if (items.length === 0) return null;

  const serious = items.some((i) => i.tone === "serious");
  const buttonClass = serious
    ? "bg-red-100 text-red-700 hover:bg-red-200 dark:bg-red-950/50 dark:text-red-300 dark:hover:bg-red-950"
    : "bg-amber-100 text-amber-800 hover:bg-amber-200 dark:bg-amber-950/50 dark:text-amber-300 dark:hover:bg-amber-950";

  return (
    <div ref={rootRef} className="relative mr-1">
      <button
        onClick={() => setOpen((o) => !o)}
        aria-haspopup="menu"
        aria-expanded={open}
        title={items.map((i) => `${i.title}: ${i.detail}`).join("\n")}
        className={`rounded-md px-2.5 py-1.5 text-sm font-medium ${buttonClass}`}
      >
        <span aria-hidden>⚠ </span>
        {items.length}
        {/* Just the count on a phone (still read out in full); the word too where there's room. */}
        <span className="sr-only sm:not-sr-only"> {items.length === 1 ? "alert" : "alerts"}</span>
      </button>

      {open && (
        <div
          role="menu"
          className="absolute right-0 z-20 mt-1 w-72 max-w-[calc(100vw-2rem)] overflow-hidden rounded-lg border border-slate-200 bg-white shadow-lg dark:border-slate-700 dark:bg-slate-900"
        >
          {items.map((item) => (
            <button
              key={item.key}
              role="menuitem"
              onClick={() => {
                setOpen(false);
                item.onSelect();
              }}
              className="flex w-full items-start gap-2.5 px-3 py-2.5 text-left hover:bg-slate-50 focus:bg-slate-50 focus:outline-none dark:hover:bg-slate-800 dark:focus:bg-slate-800"
            >
              <span aria-hidden className={`mt-1.5 h-2 w-2 shrink-0 rounded-full ${TONE_DOT[item.tone]}`} />
              <span className="min-w-0">
                <span className="block text-sm font-medium text-slate-800 dark:text-slate-200">{item.title}</span>
                <span className="block text-xs text-slate-500">{item.detail}</span>
              </span>
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

import { useState } from "react";
import { undoChange } from "../api";
import { describeUndo } from "../undo";

/**
 * "What just happened · Undo" -- shown right where the change was made, so taking it back doesn't
 * mean a trip to Manage → History (where the same change is listed too). Undo there and here are
 * the same call, so it can't overwrite anything changed again since. `onUndone` refreshes whatever
 * the page shows.
 */
export function UndoNote({
  text,
  changeId,
  onUndone,
  onDismiss,
}: {
  text: string;
  changeId: number;
  onUndone: () => void;
  onDismiss: () => void;
}) {
  const [state, setState] = useState<"idle" | "busy" | "done" | "failed">("idle");
  const [result, setResult] = useState<string | null>(null);

  const undo = async () => {
    setState("busy");
    try {
      setResult(describeUndo(await undoChange(changeId)));
      setState("done");
      onUndone();
    } catch (e) {
      setResult(e instanceof Error ? e.message : "Couldn't undo that.");
      setState("failed");
    }
  };

  return (
    <div
      role="status"
      className="mb-4 flex flex-wrap items-center justify-between gap-2 rounded-lg bg-slate-100 px-3 py-2 text-sm text-slate-800 dark:bg-slate-800 dark:text-slate-200"
    >
      <span className="min-w-0">{state === "done" || state === "failed" ? result : text}</span>
      <span className="flex items-center gap-1">
        {(state === "idle" || state === "busy") && (
          <button
            onClick={undo}
            disabled={state === "busy"}
            className="rounded px-2 py-0.5 text-sm font-medium text-indigo-700 hover:bg-white disabled:opacity-50 dark:text-indigo-300 dark:hover:bg-slate-700"
          >
            {state === "busy" ? "Undoing…" : "Undo"}
          </button>
        )}
        <button
          onClick={onDismiss}
          aria-label="Dismiss"
          className="rounded px-1.5 py-0.5 text-slate-500 hover:bg-white hover:text-slate-800 dark:hover:bg-slate-700 dark:hover:text-slate-200"
        >
          ×
        </button>
      </span>
    </div>
  );
}

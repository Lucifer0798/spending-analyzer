/** The History entry a write returned (see `request` in api.ts), or null when it changed nothing. */
export function changeIdOf(result: unknown): number | null {
  const id = result && typeof result === "object" ? (result as { changeId?: unknown }).changeId : undefined;
  return typeof id === "number" && Number.isFinite(id) ? id : null;
}

/** What an undo did, in words -- shared by Manage → History and the Undo notes beside each action. */
export function describeUndo(r: { restored: number; skipped: number; removed: number }) {
  const parts: string[] = [];
  if (r.removed > 0) parts.push(`removed ${r.removed} imported transaction${r.removed === 1 ? "" : "s"}`);
  if (r.restored > 0) parts.push(`put back ${r.restored} transaction${r.restored === 1 ? "" : "s"}`);
  if (r.skipped > 0) parts.push(`left ${r.skipped} that changed again since`);
  return parts.length > 0 ? `Undone — ${parts.join(", ")}.` : "Undone.";
}

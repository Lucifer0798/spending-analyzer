/** What an undo did, in words -- shared by Manage → History and the Undo notes beside each action. */
export function describeUndo(r: { restored: number; skipped: number; removed: number }) {
  const parts: string[] = [];
  if (r.removed > 0) parts.push(`removed ${r.removed} imported transaction${r.removed === 1 ? "" : "s"}`);
  if (r.restored > 0) parts.push(`put back ${r.restored} transaction${r.restored === 1 ? "" : "s"}`);
  if (r.skipped > 0) parts.push(`left ${r.skipped} that changed again since`);
  return parts.length > 0 ? `Undone — ${parts.join(", ")}.` : "Undone.";
}

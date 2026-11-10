/**
 * Runs a change, then reloads the page's data and reports the outcome in the page's one banner --
 * every section's writes go through it, so success and failure read the same everywhere. When the
 * write was recorded in History, the outcome comes with Undo. Resolves to the action's result once
 * it went through, or undefined when it failed (the banner already says why) -- so a caller that
 * needs either reads it here rather than setting a variable from inside the action.
 */
export type Run = <T>(action: () => Promise<T>, successMessage?: string) => Promise<T | undefined>;

/** The page's single banner, for sections whose writes don't fit `Run` (file imports). */
export interface Banner {
  setError: (message: string | null) => void;
  setNotice: (message: string | null) => void;
  /** Says `text` with an Undo for History entry `id`; a plain notice when `id` is null. */
  offerUndo: (id: number | null, text: string) => void;
}

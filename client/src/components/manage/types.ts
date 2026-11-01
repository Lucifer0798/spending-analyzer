/**
 * Runs a change, then reloads the page's data and reports the outcome in the page's one banner --
 * every section's writes go through it, so success and failure read the same everywhere. When the
 * write was recorded in History, the outcome comes with Undo.
 */
export type Run = (action: () => Promise<unknown>, successMessage?: string) => Promise<void>;

/** The page's single banner, for sections whose writes don't fit `Run` (file imports). */
export interface Banner {
  setError: (message: string | null) => void;
  setNotice: (message: string | null) => void;
  /** Says `text` with an Undo for History entry `id`; a plain notice when `id` is null. */
  offerUndo: (id: number | null, text: string) => void;
}

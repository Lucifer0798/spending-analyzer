/**
 * Runs a change, then reloads the page's data and reports the outcome in the page's one banner --
 * every section's writes go through it, so success and failure read the same everywhere.
 */
export type Run = (action: () => Promise<unknown>, successMessage?: string) => Promise<void>;

/** The page's single banner, for sections whose writes don't fit `Run` (file imports). */
export interface Banner {
  setError: (message: string | null) => void;
  setNotice: (message: string | null) => void;
}

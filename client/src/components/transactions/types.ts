/**
 * Offers the change just made for undo, beside where it was made -- `id` is the History entry the
 * server returned with it, or null when nothing changed (which clears the offer).
 */
export type OfferUndo = (id: number | null, text: string) => void;

/** The page's one error line; null clears it. */
export type SetError = (message: string | null) => void;

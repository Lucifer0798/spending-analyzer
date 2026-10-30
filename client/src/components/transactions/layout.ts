/**
 * Below md a row is a card: a three-column grid (checkbox | details | amount) with each cell
 * placed by col/row classes, stacking into lines -- description, date, category, tags, actions.
 * From md up the same markup is an ordinary table row and the grid classes do nothing.
 */
export const CARD_ROW = "grid grid-cols-[auto_1fr_auto] items-start gap-x-3 gap-y-1.5 p-3 md:table-row md:p-0";
export const CARD_CELL = "block md:table-cell md:px-4 md:py-2 md:align-middle";

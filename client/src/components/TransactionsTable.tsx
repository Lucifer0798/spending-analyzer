import { useEffect, useState } from "react";
import { exportUrl, fetchCategories, fetchTags, fetchTransactions } from "../api";
import type { DateRangeValue, FilterPreset, Tag, TransactionWithTags } from "../types";
import { ExportLink } from "./ExportLink";
import { UncategorizedReview } from "./UncategorizedReview";
import { TransferReview } from "./TransferReview";
import { DuplicateReview } from "./DuplicateReview";
import { UndoNote } from "./UndoNote";
import { FilterPresets } from "./transactions/FilterPresets";
import { BulkActions } from "./transactions/BulkActions";
import { TransactionRow } from "./transactions/TransactionRow";
import { EditRow } from "./transactions/EditRow";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
  onAccountIdChange: (id: number | null) => void;
  onRangeChange: (range: DateRangeValue) => void;
  /** Pre-fills the description search -- read once at mount, the way another page hands off
   *  "show me these" (App remounts this page whenever its tab is opened). */
  initialSearch?: string;
  /** Opens the uncategorized review panel expanded -- read once at mount, like initialSearch. */
  initialReviewOpen?: boolean;
  /** Opens the transfer review panel expanded -- read once at mount, like initialSearch. */
  initialTransfersOpen?: boolean;
  /** Opens the double-charge review expanded -- read once at mount, like initialSearch. */
  initialDuplicatesOpen?: boolean;
  /** Pre-selects the category filter -- read once at mount, like initialSearch. */
  initialCategory?: string;
}

const PAGE_SIZE = 50;

/**
 * The Transactions page: filters and presets, the review panels, the bulk-action bar, and a page of
 * rows. Each row's own actions live in transactions/TransactionRow (or EditRow while it's open);
 * this component owns the list itself, the filters, and the selection.
 */
export function TransactionsTable({
  accountId,
  range,
  onAccountIdChange,
  onRangeChange,
  initialSearch = "",
  initialReviewOpen = false,
  initialTransfersOpen = false,
  initialDuplicatesOpen = false,
  initialCategory = "",
}: Props) {
  const [transactions, setTransactions] = useState<TransactionWithTags[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [categoryFilter, setCategoryFilter] = useState(initialCategory);
  const [tagFilter, setTagFilter] = useState("");
  const [searchInput, setSearchInput] = useState(initialSearch);
  const [searchFilter, setSearchFilter] = useState(initialSearch);
  const [categories, setCategories] = useState<string[]>([]);
  // A category with no entry here just renders without a swatch.
  const [colorByCategory, setColorByCategory] = useState<Record<string, string>>({});
  const [tags, setTags] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [tagDrafts, setTagDrafts] = useState<Record<number, string>>({});
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  // The last change made from this page, offered for undo where it happened. One at a time: a new
  // change replaces the note (every one stays undoable from Manage → History).
  const [lastChange, setLastChange] = useState<{ id: number; text: string } | null>(null);
  const offerUndo = (id: number | null, text: string) => setLastChange(id === null ? null : { id, text });

  useEffect(() => {
    fetchCategories().then((r) => {
      setCategories(r.categories);
      const colors: Record<string, string> = {};
      for (const c of r.detailed) {
        if (c.color) colors[c.name] = c.color;
      }
      setColorByCategory(colors);
    });
  }, []);

  const applyPreset = (preset: FilterPreset) => {
    setCategoryFilter(preset.category ?? "");
    setTagFilter(preset.tag ?? "");
    setSearchInput(preset.search ?? "");
    setSearchFilter(preset.search ?? "");
    onAccountIdChange(preset.account_id);
    onRangeChange({ from: preset.date_from, to: preset.date_to });
  };

  const loadTags = () => {
    fetchTags().then(setTags).catch(() => {});
  };
  useEffect(loadTags, []);

  // Debounces the search box: the filter actually sent to the API only updates once typing
  // pauses, so each keystroke doesn't trigger its own request.
  useEffect(() => {
    const handle = setTimeout(() => setSearchFilter(searchInput.trim()), 300);
    return () => clearTimeout(handle);
  }, [searchInput]);

  // A filter change invalidates the current page number. Reset it during render rather than
  // in a follow-up effect: an effect would commit the stale page first and only fix it a
  // render later, so the fetch below would run once for the old page and again for page 0.
  // Comparing against the previous key and calling setState in this branch is React's own
  // pattern for adjusting state in response to a prop change — see "You Might Not Need an
  // Effect" in the React docs.
  const filterKey = `${accountId}|${categoryFilter}|${tagFilter}|${searchFilter}|${range.from}|${range.to}`;
  const [prevFilterKey, setPrevFilterKey] = useState(filterKey);
  if (filterKey !== prevFilterKey) {
    setPrevFilterKey(filterKey);
    setPage(0);
  }

  const load = () => {
    // load() is this effect's whole body (see useEffect(load, ...) below); marking loading
    // before an API fetch is synchronizing with an external system, not adjusting state
    // derived from a prop.
    // oxlint-disable-next-line react/set-state-in-effect
    setLoading(true);
    setSelectedIds(new Set());
    fetchTransactions({
      category: categoryFilter || undefined,
      tag: tagFilter || undefined,
      search: searchFilter || undefined,
      accountId,
      range,
      limit: PAGE_SIZE,
      offset: page * PAGE_SIZE,
    })
      .then((r) => {
        setTransactions(r.transactions);
        setTotal(r.total);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load transactions."))
      .finally(() => setLoading(false));
  };

  useEffect(load, [page, categoryFilter, tagFilter, searchFilter, accountId, range]);

  const patchRow = (id: number, patch: Partial<TransactionWithTags>) =>
    setTransactions((prev) => prev.map((x) => (x.id === id ? { ...x, ...patch } : x)));

  const toggleSelected = (id: number) => {
    setSelectedIds((prev) => {
      const next = new Set(prev);
      if (next.has(id)) next.delete(id);
      else next.add(id);
      return next;
    });
  };

  const allOnPageSelected = transactions.length > 0 && transactions.every((t) => selectedIds.has(t.id));

  const toggleSelectAllOnPage = () => {
    setSelectedIds(allOnPageSelected ? new Set() : new Set(transactions.map((t) => t.id)));
  };

  const clearSelection = () => setSelectedIds(new Set());

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const showAccountColumn = accountId === null;
  const columnCount = showAccountColumn ? 8 : 7;

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Transactions</h1>
        <div className="flex flex-wrap items-center gap-2">
          <input
            value={searchInput}
            onChange={(e) => setSearchInput(e.target.value)}
            placeholder="Search descriptions…"
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <select
            value={categoryFilter}
            onChange={(e) => setCategoryFilter(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="">All categories</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <select
            value={tagFilter}
            onChange={(e) => setTagFilter(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="">All tags</option>
            {tags.map((t) => (
              <option key={t.name} value={t.name}>
                {t.name} ({t.count})
              </option>
            ))}
          </select>
          {/* Exports the whole filtered set, not the page on screen — hence the row count. */}
          <ExportLink
            href={exportUrl("transactions", {
              accountId,
              range,
              category: categoryFilter || undefined,
              tag: tagFilter || undefined,
              search: searchFilter || undefined,
            })}
            label="Export CSV"
            disabled={total === 0}
            title={total > 0 ? `Download all ${total} matching transactions` : undefined}
          />
        </div>
      </div>

      {/* Renders nothing once every transaction has a category. */}
      <UncategorizedReview
        accountId={accountId}
        categories={categories}
        onChanged={load}
        initiallyOpen={initialReviewOpen}
        refreshKey={transactions}
      />
      {/* Same: nothing once every likely transfer is matched or dismissed. */}
      <TransferReview onChanged={load} refreshKey={transactions} initiallyOpen={initialTransfersOpen} />
      {/* And again: nothing unless something looks charged twice. */}
      <DuplicateReview
        accountId={accountId}
        refreshKey={transactions}
        initiallyOpen={initialDuplicatesOpen}
        onShow={(merchant) => {
          setSearchInput(merchant);
          setSearchFilter(merchant);
        }}
      />

      <FilterPresets
        filters={{ category: categoryFilter, tag: tagFilter, search: searchFilter, accountId, range }}
        onApply={applyPreset}
        setError={setError}
      />

      {error && (
        <div className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}

      {lastChange && (
        <UndoNote
          key={lastChange.id}
          text={lastChange.text}
          changeId={lastChange.id}
          onUndone={load}
          onDismiss={() => setLastChange(null)}
        />
      )}

      {/* Renders nothing with no rows ticked. */}
      <BulkActions
        selectedIds={selectedIds}
        categories={categories}
        onClear={clearSelection}
        onChanged={load}
        onTagsChanged={loadTags}
        offerUndo={offerUndo}
        setError={setError}
      />

      {/* Below md the header is hidden (rows become cards), so its select-all moves up here. */}
      {transactions.length > 0 && (
        <label className="mb-2 flex items-center gap-2 text-xs text-slate-500 md:hidden">
          <input type="checkbox" checked={allOnPageSelected} onChange={toggleSelectAllOnPage} />
          Select all on this page
        </label>
      )}

      {/* One set of markup for both layouts: a table from md up, and below that each row is a
          three-column grid card (checkbox | details | amount) -- see transactions/layout.ts. */}
      <div className="rounded-lg border border-slate-200 md:overflow-x-auto dark:border-slate-800">
        <table className="block min-w-full md:table md:divide-y md:divide-slate-200 dark:md:divide-slate-800">
          <thead className="hidden bg-slate-50 md:table-header-group dark:bg-slate-900">
            <tr>
              <th className="px-4 py-2">
                <input
                  type="checkbox"
                  checked={allOnPageSelected}
                  onChange={toggleSelectAllOnPage}
                  title="Select all on this page"
                />
              </th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Date</th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Description</th>
              {showAccountColumn && (
                <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Account</th>
              )}
              <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500">Amount</th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Category</th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Tags</th>
              <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500"></th>
            </tr>
          </thead>
          <tbody className="block divide-y divide-slate-100 bg-white md:table-row-group dark:divide-slate-800 dark:bg-slate-950">
            {loading && (
              <tr className="block md:table-row">
                <td colSpan={columnCount} className="block px-4 py-6 text-center text-sm text-slate-500 md:table-cell">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && transactions.length === 0 && (
              <tr className="block md:table-row">
                <td colSpan={columnCount} className="block px-4 py-6 text-center text-sm text-slate-500 md:table-cell">
                  No transactions found.
                </td>
              </tr>
            )}

            {transactions.map((t) =>
              editingId === t.id ? (
                <EditRow
                  key={t.id}
                  t={t}
                  selected={selectedIds.has(t.id)}
                  onToggleSelected={() => toggleSelected(t.id)}
                  showAccountColumn={showAccountColumn}
                  colorByCategory={colorByCategory}
                  onClose={() => setEditingId(null)}
                  onSaved={load}
                  offerUndo={offerUndo}
                  setError={setError}
                />
              ) : (
                <TransactionRow
                  key={t.id}
                  t={t}
                  selected={selectedIds.has(t.id)}
                  onToggleSelected={() => toggleSelected(t.id)}
                  showAccountColumn={showAccountColumn}
                  categories={categories}
                  colorByCategory={colorByCategory}
                  tagDraft={tagDrafts[t.id] ?? ""}
                  onTagDraftChange={(value) => setTagDrafts((d) => ({ ...d, [t.id]: value }))}
                  onPatch={(patch) => patchRow(t.id, patch)}
                  onReload={load}
                  onTagsChanged={loadTags}
                  onEdit={() => {
                    setError(null);
                    setEditingId(t.id);
                  }}
                  offerUndo={offerUndo}
                  setError={setError}
                />
              )
            )}
          </tbody>
        </table>
      </div>

      <datalist id="tag-suggestions">
        {tags.map((t) => (
          <option key={t.name} value={t.name} />
        ))}
      </datalist>

      <div className="mt-4 flex items-center justify-between text-sm text-slate-600 dark:text-slate-400">
        <span>
          Page {page + 1} of {totalPages} ({total} transactions)
        </span>
        <div className="flex gap-2">
          <button
            disabled={page === 0}
            onClick={() => setPage((p) => Math.max(0, p - 1))}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-40 dark:border-slate-700"
          >
            Previous
          </button>
          <button
            disabled={page + 1 >= totalPages}
            onClick={() => setPage((p) => p + 1)}
            className="rounded-md border border-slate-300 px-3 py-1 disabled:opacity-40 dark:border-slate-700"
          >
            Next
          </button>
        </div>
      </div>
    </div>
  );
}

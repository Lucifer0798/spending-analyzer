import { useEffect, useState } from "react";
import {
  addTransactionTag,
  bulkAddTag,
  bulkCategorize,
  bulkDeleteTransactions,
  deleteTransaction,
  exportUrl,
  fetchCategories,
  fetchTags,
  fetchTransactions,
  removeTransactionTag,
  updateTransaction,
  updateTransactionCategory,
} from "../api";
import type { DateRangeValue, Tag, TransactionWithTags } from "../types";
import { currencyPrecise } from "../format";
import { ExportLink } from "./ExportLink";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
}

interface EditDraft {
  date: string;
  description: string;
  amount: string;
  type: "debit" | "credit";
}

const PAGE_SIZE = 50;

export function TransactionsTable({ accountId, range }: Props) {
  const [transactions, setTransactions] = useState<TransactionWithTags[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [categoryFilter, setCategoryFilter] = useState("");
  const [tagFilter, setTagFilter] = useState("");
  const [searchInput, setSearchInput] = useState("");
  const [searchFilter, setSearchFilter] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [editingId, setEditingId] = useState<number | null>(null);
  const [draft, setDraft] = useState<EditDraft | null>(null);
  const [saving, setSaving] = useState(false);
  const [tagDrafts, setTagDrafts] = useState<Record<number, string>>({});
  const [selectedIds, setSelectedIds] = useState<Set<number>>(new Set());
  const [bulkCategoryChoice, setBulkCategoryChoice] = useState("");
  const [bulkTagInput, setBulkTagInput] = useState("");
  const [bulkBusy, setBulkBusy] = useState(false);

  useEffect(() => {
    fetchCategories().then((r) => setCategories(r.categories));
  }, []);

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

  const handleAddTag = async (t: TransactionWithTags) => {
    const name = (tagDrafts[t.id] ?? "").trim();
    if (!name) return;
    setTagDrafts((d) => ({ ...d, [t.id]: "" }));
    try {
      const { tags: updated } = await addTransactionTag(t.id, name);
      setTransactions((prev) => prev.map((x) => (x.id === t.id ? { ...x, tags: updated } : x)));
      loadTags();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to add tag.");
    }
  };

  const handleRemoveTag = async (t: TransactionWithTags, tag: string) => {
    try {
      const { tags: updated } = await removeTransactionTag(t.id, tag);
      setTransactions((prev) => prev.map((x) => (x.id === t.id ? { ...x, tags: updated } : x)));
      loadTags();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to remove tag.");
    }
  };

  const handleCategoryChange = async (id: number, category: string) => {
    setTransactions((prev) =>
      prev.map((t) => (t.id === id ? { ...t, category, category_source: "user" } : t))
    );
    try {
      await updateTransactionCategory(id, category);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update category.");
      load();
    }
  };

  const startEdit = (t: TransactionWithTags) => {
    setError(null);
    setEditingId(t.id);
    setDraft({
      date: t.date,
      description: t.description,
      amount: String(t.amount),
      type: t.type,
    });
  };

  const cancelEdit = () => {
    setEditingId(null);
    setDraft(null);
  };

  const saveEdit = async (original: TransactionWithTags) => {
    if (!draft) return;
    const amount = Number(draft.amount);
    if (!Number.isFinite(amount) || amount <= 0) {
      setError("Amount must be a number greater than zero.");
      return;
    }

    // Send only what actually changed, so an untouched field is never overwritten.
    const changes: Parameters<typeof updateTransaction>[1] = {};
    if (draft.date !== original.date) changes.date = draft.date;
    if (draft.description.trim() !== original.description) changes.description = draft.description.trim();
    if (amount !== original.amount) changes.amount = amount;
    if (draft.type !== original.type) changes.type = draft.type;

    if (Object.keys(changes).length === 0) {
      cancelEdit();
      return;
    }

    setSaving(true);
    setError(null);
    try {
      await updateTransaction(original.id, changes);
      cancelEdit();
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save changes.");
    } finally {
      setSaving(false);
    }
  };

  const handleDelete = async (t: TransactionWithTags) => {
    if (!confirm(`Delete "${t.description}" (${currencyPrecise(t.amount, t.account_currency ?? undefined)}) on ${t.date}?`)) return;
    setError(null);
    try {
      await deleteTransaction(t.id);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete transaction.");
    }
  };

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

  const handleBulkCategorize = async () => {
    if (!bulkCategoryChoice) return;
    setBulkBusy(true);
    setError(null);
    try {
      await bulkCategorize([...selectedIds], bulkCategoryChoice);
      setBulkCategoryChoice("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to categorize the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  const handleBulkTag = async () => {
    const name = bulkTagInput.trim();
    if (!name) return;
    setBulkBusy(true);
    setError(null);
    try {
      await bulkAddTag([...selectedIds], name);
      setBulkTagInput("");
      load();
      loadTags();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to tag the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  const handleBulkDelete = async () => {
    if (!confirm(`Delete ${selectedIds.size} selected transaction${selectedIds.size === 1 ? "" : "s"}?`)) return;
    setBulkBusy(true);
    setError(null);
    try {
      await bulkDeleteTransactions([...selectedIds]);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete the selection.");
    } finally {
      setBulkBusy(false);
    }
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));
  const showAccountColumn = accountId === null;
  const columnCount = showAccountColumn ? 8 : 7;

  const inputClass =
    "w-full rounded border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900";

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-4 flex flex-wrap items-center justify-between gap-2">
        <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Transactions</h1>
        <div className="flex items-center gap-2">
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

      {error && (
        <div className="mb-3 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}

      {selectedIds.size > 0 && (
        <div className="mb-3 flex flex-wrap items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 p-3 text-sm dark:border-indigo-900/50 dark:bg-indigo-950/30">
          <span className="font-medium text-indigo-900 dark:text-indigo-200">
            {selectedIds.size} selected
          </span>
          <select
            value={bulkCategoryChoice}
            onChange={(e) => setBulkCategoryChoice(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="">Set category…</option>
            {categories.map((c) => (
              <option key={c} value={c}>
                {c}
              </option>
            ))}
          </select>
          <button
            disabled={bulkBusy || !bulkCategoryChoice}
            onClick={handleBulkCategorize}
            className="rounded-md bg-indigo-600 px-3 py-1 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Apply
          </button>
          <input
            list="tag-suggestions"
            value={bulkTagInput}
            onChange={(e) => setBulkTagInput(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleBulkTag();
            }}
            placeholder="Add tag…"
            className="w-28 rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <button
            disabled={bulkBusy || !bulkTagInput.trim()}
            onClick={handleBulkTag}
            className="rounded-md px-3 py-1 text-sm font-medium text-indigo-600 hover:bg-indigo-100 disabled:opacity-50 dark:text-indigo-400 dark:hover:bg-indigo-950/50"
          >
            Add tag
          </button>
          <button
            disabled={bulkBusy}
            onClick={handleBulkDelete}
            className="rounded-md px-3 py-1 text-sm font-medium text-red-600 hover:bg-red-100 disabled:opacity-50 dark:hover:bg-red-950/40"
          >
            Delete
          </button>
          <button
            onClick={clearSelection}
            className="ml-auto rounded-md px-3 py-1 text-sm text-slate-600 hover:bg-slate-200 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            Clear selection
          </button>
        </div>
      )}

      <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
        <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
          <thead className="bg-slate-50 dark:bg-slate-900">
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
          <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
            {loading && (
              <tr>
                <td colSpan={columnCount} className="px-4 py-6 text-center text-sm text-slate-500">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && transactions.length === 0 && (
              <tr>
                <td colSpan={columnCount} className="px-4 py-6 text-center text-sm text-slate-500">
                  No transactions found.
                </td>
              </tr>
            )}

            {transactions.map((t) =>
              editingId === t.id && draft ? (
                <tr key={t.id} className="bg-indigo-50/50 dark:bg-indigo-950/20">
                  <td className="px-4 py-2">
                    <input
                      type="checkbox"
                      checked={selectedIds.has(t.id)}
                      onChange={() => toggleSelected(t.id)}
                    />
                  </td>
                  <td className="px-4 py-2">
                    <input
                      type="date"
                      value={draft.date}
                      onChange={(e) => setDraft({ ...draft, date: e.target.value })}
                      className={inputClass}
                    />
                  </td>
                  <td className="px-4 py-2" colSpan={showAccountColumn ? 2 : 1}>
                    <input
                      value={draft.description}
                      onChange={(e) => setDraft({ ...draft, description: e.target.value })}
                      className={inputClass}
                    />
                  </td>
                  <td className="px-4 py-2">
                    <div className="flex items-center gap-1">
                      <select
                        value={draft.type}
                        onChange={(e) => setDraft({ ...draft, type: e.target.value as "debit" | "credit" })}
                        className={inputClass}
                      >
                        <option value="debit">−</option>
                        <option value="credit">+</option>
                      </select>
                      <input
                        type="number"
                        step="0.01"
                        min="0.01"
                        value={draft.amount}
                        onChange={(e) => setDraft({ ...draft, amount: e.target.value })}
                        className={`${inputClass} text-right`}
                      />
                    </div>
                  </td>
                  <td className="px-4 py-2 text-xs text-slate-500">
                    {t.category ?? "Uncategorized"}
                  </td>
                  <td className="px-4 py-2 text-xs text-slate-500">
                    {t.tags.length > 0 ? t.tags.join(", ") : "—"}
                  </td>
                  <td className="whitespace-nowrap px-4 py-2 text-right">
                    <button
                      disabled={saving}
                      onClick={() => saveEdit(t)}
                      className="rounded bg-indigo-600 px-2 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                    >
                      {saving ? "Saving…" : "Save"}
                    </button>
                    <button
                      disabled={saving}
                      onClick={cancelEdit}
                      className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                    >
                      Cancel
                    </button>
                  </td>
                </tr>
              ) : (
                <tr key={t.id} className={selectedIds.has(t.id) ? "bg-indigo-50/30 dark:bg-indigo-950/10" : undefined}>
                  <td className="px-4 py-2">
                    <input
                      type="checkbox"
                      checked={selectedIds.has(t.id)}
                      onChange={() => toggleSelected(t.id)}
                    />
                  </td>
                  <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">
                    {t.date}
                  </td>
                  <td className="px-4 py-2 text-sm text-slate-800 dark:text-slate-200">{t.description}</td>
                  {showAccountColumn && (
                    <td className="whitespace-nowrap px-4 py-2 text-xs text-slate-500">{t.account_name}</td>
                  )}
                  <td
                    className={`whitespace-nowrap px-4 py-2 text-right text-sm font-medium ${
                      t.type === "credit" ? "text-emerald-600" : "text-slate-800 dark:text-slate-200"
                    }`}
                  >
                    {t.type === "credit" ? "+" : "-"}
                    {currencyPrecise(t.amount, t.account_currency ?? undefined)}
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <select
                      value={t.category ?? ""}
                      onChange={(e) => handleCategoryChange(t.id, e.target.value)}
                      className="rounded border border-slate-300 bg-white px-2 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
                    >
                      <option value="" disabled>
                        Uncategorized
                      </option>
                      {categories.map((c) => (
                        <option key={c} value={c}>
                          {c}
                        </option>
                      ))}
                    </select>
                    {t.category_source && (
                      <span className="ml-2 text-[10px] uppercase text-slate-400">{t.category_source}</span>
                    )}
                  </td>
                  <td className="px-4 py-2 text-sm">
                    <div className="flex flex-wrap items-center gap-1">
                      {t.tags.map((tag) => (
                        <span
                          key={tag}
                          className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300"
                        >
                          {tag}
                          <button
                            onClick={() => handleRemoveTag(t, tag)}
                            className="text-slate-400 hover:text-red-600"
                            title={`Remove "${tag}"`}
                          >
                            ×
                          </button>
                        </span>
                      ))}
                      <input
                        list="tag-suggestions"
                        value={tagDrafts[t.id] ?? ""}
                        onChange={(e) => setTagDrafts((d) => ({ ...d, [t.id]: e.target.value }))}
                        onKeyDown={(e) => {
                          if (e.key === "Enter") handleAddTag(t);
                        }}
                        placeholder="+ tag"
                        className="w-16 rounded border border-dashed border-slate-300 bg-transparent px-1 py-0.5 text-xs focus:w-28 dark:border-slate-700"
                      />
                    </div>
                  </td>
                  <td className="whitespace-nowrap px-4 py-2 text-right">
                    <button
                      onClick={() => startEdit(t)}
                      className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                    >
                      Edit
                    </button>
                    <button
                      onClick={() => handleDelete(t)}
                      className="ml-1 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
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

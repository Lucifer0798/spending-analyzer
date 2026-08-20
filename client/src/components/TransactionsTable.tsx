import { useEffect, useState } from "react";
import { fetchCategories, fetchTransactions, updateTransactionCategory } from "../api";
import type { Transaction } from "../types";
import { currencyPrecise } from "../format";

interface Props {
  accountId: number | null;
}

const PAGE_SIZE = 50;

export function TransactionsTable({ accountId }: Props) {
  const [transactions, setTransactions] = useState<Transaction[]>([]);
  const [total, setTotal] = useState(0);
  const [page, setPage] = useState(0);
  const [categoryFilter, setCategoryFilter] = useState("");
  const [categories, setCategories] = useState<string[]>([]);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    fetchCategories().then((r) => setCategories(r.categories));
  }, []);

  // Changing the account filter invalidates the current page number.
  useEffect(() => {
    setPage(0);
  }, [accountId, categoryFilter]);

  useEffect(() => {
    setLoading(true);
    fetchTransactions({
      category: categoryFilter || undefined,
      accountId,
      limit: PAGE_SIZE,
      offset: page * PAGE_SIZE,
    })
      .then((r) => {
        setTransactions(r.transactions);
        setTotal(r.total);
      })
      .finally(() => setLoading(false));
  }, [page, categoryFilter, accountId]);

  const handleCategoryChange = async (id: number, category: string) => {
    setTransactions((prev) =>
      prev.map((t) => (t.id === id ? { ...t, category, category_source: "user" } : t))
    );
    await updateTransactionCategory(id, category);
  };

  const totalPages = Math.max(1, Math.ceil(total / PAGE_SIZE));

  return (
    <div className="mx-auto max-w-5xl px-4 py-8">
      <div className="mb-4 flex items-center justify-between">
        <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Transactions</h1>
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
      </div>

      <div className="overflow-x-auto rounded-lg border border-slate-200 dark:border-slate-800">
        <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
          <thead className="bg-slate-50 dark:bg-slate-900">
            <tr>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Date</th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Description</th>
              {accountId === null && (
                <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Account</th>
              )}
              <th className="px-4 py-2 text-right text-xs font-medium uppercase text-slate-500">Amount</th>
              <th className="px-4 py-2 text-left text-xs font-medium uppercase text-slate-500">Category</th>
            </tr>
          </thead>
          <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
            {loading && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-sm text-slate-500">
                  Loading…
                </td>
              </tr>
            )}
            {!loading && transactions.length === 0 && (
              <tr>
                <td colSpan={5} className="px-4 py-6 text-center text-sm text-slate-500">
                  No transactions found.
                </td>
              </tr>
            )}
            {transactions.map((t) => (
              <tr key={t.id}>
                <td className="whitespace-nowrap px-4 py-2 text-sm text-slate-600 dark:text-slate-400">{t.date}</td>
                <td className="px-4 py-2 text-sm text-slate-800 dark:text-slate-200">{t.description}</td>
                {accountId === null && (
                  <td className="whitespace-nowrap px-4 py-2 text-xs text-slate-500">{t.account_name}</td>
                )}
                <td
                  className={`whitespace-nowrap px-4 py-2 text-right text-sm font-medium ${
                    t.type === "credit" ? "text-emerald-600" : "text-slate-800 dark:text-slate-200"
                  }`}
                >
                  {t.type === "credit" ? "+" : "-"}
                  {currencyPrecise(t.amount)}
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
              </tr>
            ))}
          </tbody>
        </table>
      </div>

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

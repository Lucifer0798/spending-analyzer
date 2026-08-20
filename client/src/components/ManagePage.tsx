import { useEffect, useState } from "react";
import {
  createAccount,
  createCategory,
  deleteAccount,
  deleteCategory,
  fetchAccounts,
  fetchCategories,
  updateAccount,
  updateCategory,
} from "../api";
import type { Account, AccountType, CategoryDetail } from "../types";
import { accountTypeLabel } from "../format";

interface Props {
  onAccountsChanged: () => void;
}

export function ManagePage({ onAccountsChanged }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [types, setTypes] = useState<AccountType[]>([]);
  const [categories, setCategories] = useState<CategoryDetail[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const [newAccountName, setNewAccountName] = useState("");
  const [newAccountType, setNewAccountType] = useState<AccountType>("checking");
  const [newCategoryName, setNewCategoryName] = useState("");
  const [newCategoryKind, setNewCategoryKind] = useState<"spending" | "income" | "transfer">("spending");

  const reload = async () => {
    const [accountsRes, categoriesRes] = await Promise.all([fetchAccounts(true), fetchCategories()]);
    setAccounts(accountsRes.accounts);
    setTypes(accountsRes.types);
    setCategories(categoriesRes.detailed);
  };

  useEffect(() => {
    reload().catch((e) => setError(e instanceof Error ? e.message : "Failed to load."));
  }, []);

  /** Runs an action, then refreshes and reports the outcome in one place. */
  const run = async (action: () => Promise<unknown>, successMessage?: string) => {
    setError(null);
    setNotice(null);
    try {
      await action();
      await reload();
      onAccountsChanged();
      if (successMessage) setNotice(successMessage);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong.");
    }
  };

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Manage</h1>

      {error && (
        <div className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}
      {notice && (
        <div className="mt-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          {notice}
        </div>
      )}

      {/* ---------------- Accounts ---------------- */}
      <section className="mt-8">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Accounts</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Keep a checking account and a credit card separate so each has its own totals and
          duplicate checking.
        </p>

        <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
            <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
              {accounts.map((a) => (
                <tr key={a.id} className={a.archived ? "opacity-50" : ""}>
                  <td className="px-4 py-2">
                    <div className="text-sm font-medium text-slate-800 dark:text-slate-200">
                      {a.name}
                      {a.id === 1 && <span className="ml-2 text-[10px] uppercase text-slate-400">default</span>}
                      {a.archived && <span className="ml-2 text-[10px] uppercase text-slate-400">archived</span>}
                    </div>
                    <div className="text-xs text-slate-500">
                      {accountTypeLabel(a.type)} · {a.transactionCount} transactions
                    </div>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => run(() => updateAccount(a.id, { archived: !a.archived }))}
                      className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                    >
                      {a.archived ? "Unarchive" : "Archive"}
                    </button>
                    {a.id !== 1 && (
                      <button
                        onClick={() => {
                          const msg = a.transactionCount > 0
                            ? `Delete "${a.name}"? Its ${a.transactionCount} transactions will move to the Default account.`
                            : `Delete "${a.name}"?`;
                          if (confirm(msg)) {
                            run(() => deleteAccount(a.id), `Deleted "${a.name}".`);
                          }
                        }}
                        className="ml-1 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
                      >
                        Delete
                      </button>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="mt-3 flex flex-wrap gap-2">
          <input
            value={newAccountName}
            onChange={(e) => setNewAccountName(e.target.value)}
            placeholder="Account name"
            className="flex-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <select
            value={newAccountType}
            onChange={(e) => setNewAccountType(e.target.value as AccountType)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            {types.map((t) => (
              <option key={t} value={t}>
                {accountTypeLabel(t)}
              </option>
            ))}
          </select>
          <button
            disabled={!newAccountName.trim()}
            onClick={() =>
              run(() => createAccount(newAccountName.trim(), newAccountType)).then(() =>
                setNewAccountName("")
              )
            }
            className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Add account
          </button>
        </div>
      </section>

      {/* ---------------- Categories ---------------- */}
      <section className="mt-10">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Categories</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          Custom categories are offered to the AI when it categorizes transactions. Anything marked
          as income or transfer is left out of spending totals.
        </p>

        <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
            <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
              {categories.map((c) => (
                <tr key={c.id}>
                  <td className="px-4 py-2">
                    <div className="text-sm text-slate-800 dark:text-slate-200">
                      {c.name}
                      {c.is_income && (
                        <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] uppercase text-slate-600 dark:bg-slate-800 dark:text-slate-400">
                          income
                        </span>
                      )}
                      {c.is_transfer && (
                        <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[10px] uppercase text-slate-600 dark:bg-slate-800 dark:text-slate-400">
                          transfer
                        </span>
                      )}
                      {!c.is_builtin && (
                        <span className="ml-2 text-[10px] uppercase text-indigo-500">custom</span>
                      )}
                    </div>
                    <div className="text-xs text-slate-500">{c.transactionCount} transactions</div>
                  </td>
                  <td className="px-4 py-2 text-right">
                    {c.is_builtin ? (
                      <span className="text-xs text-slate-400">built-in</span>
                    ) : (
                      <>
                        <button
                          onClick={() => {
                            const name = prompt(`Rename "${c.name}" to:`, c.name);
                            if (name && name.trim() && name.trim() !== c.name) {
                              run(() => updateCategory(c.id, { name: name.trim() }));
                            }
                          }}
                          className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                        >
                          Rename
                        </button>
                        <button
                          onClick={() => {
                            const msg = c.transactionCount > 0
                              ? `Delete "${c.name}"? Its ${c.transactionCount} transactions will move to "Other".`
                              : `Delete "${c.name}"?`;
                            if (confirm(msg)) {
                              run(() => deleteCategory(c.id), `Deleted "${c.name}".`);
                            }
                          }}
                          className="ml-1 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
                        >
                          Delete
                        </button>
                      </>
                    )}
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <div className="mt-3 flex flex-wrap gap-2">
          <input
            value={newCategoryName}
            onChange={(e) => setNewCategoryName(e.target.value)}
            placeholder="Category name"
            className="flex-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <select
            value={newCategoryKind}
            onChange={(e) => setNewCategoryKind(e.target.value as typeof newCategoryKind)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="spending">Counts as spending</option>
            <option value="income">Income (excluded)</option>
            <option value="transfer">Transfer (excluded)</option>
          </select>
          <button
            disabled={!newCategoryName.trim()}
            onClick={() =>
              run(() =>
                createCategory(newCategoryName.trim(), {
                  is_income: newCategoryKind === "income",
                  is_transfer: newCategoryKind === "transfer",
                })
              ).then(() => setNewCategoryName(""))
            }
            className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Add category
          </button>
        </div>
      </section>
    </div>
  );
}

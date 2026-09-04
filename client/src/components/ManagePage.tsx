import { useEffect, useState } from "react";
import {
  createAccount,
  createCategory,
  deleteAccount,
  deleteBudget,
  deleteCategory,
  fetchAccounts,
  fetchBudgets,
  fetchCategories,
  fetchMerchants,
  forgetAllMerchants,
  forgetMerchant,
  saveMerchantRule,
  setBudget,
  updateAccount,
  updateCategory,
} from "../api";
import type {
  Account,
  AccountType,
  BudgetSummary,
  CategoryDetail,
  MerchantsResponse,
} from "../types";
import { accountTypeLabel, currency } from "../format";

interface Props {
  onAccountsChanged: () => void;
}

export function ManagePage({ onAccountsChanged }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [types, setTypes] = useState<AccountType[]>([]);
  const [categories, setCategories] = useState<CategoryDetail[]>([]);
  const [memory, setMemory] = useState<MerchantsResponse | null>(null);
  const [budgets, setBudgets] = useState<BudgetSummary | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  // Only holds categories the user has actually typed into. Everything else reads its value
  // straight from the saved budget, so there is no state to keep in step after a reload.
  const [budgetDrafts, setBudgetDrafts] = useState<Record<string, string>>({});

  const [newAccountName, setNewAccountName] = useState("");
  const [newAccountType, setNewAccountType] = useState<AccountType>("checking");
  const [newCategoryName, setNewCategoryName] = useState("");
  const [newCategoryKind, setNewCategoryKind] = useState<"spending" | "income" | "transfer">("spending");
  const [ruleMerchant, setRuleMerchant] = useState("");
  const [ruleCategory, setRuleCategory] = useState("");
  const [ruleMin, setRuleMin] = useState("");
  const [ruleMax, setRuleMax] = useState("");

  const reload = async () => {
    const [accountsRes, categoriesRes, merchantsRes, budgetsRes] = await Promise.all([
      fetchAccounts(true),
      fetchCategories(),
      fetchMerchants(),
      fetchBudgets(null),
    ]);
    setAccounts(accountsRes.accounts);
    setTypes(accountsRes.types);
    setCategories(categoriesRes.detailed);
    setMemory(merchantsRes);
    setBudgets(budgetsRes);
  };

  useEffect(() => {
    // reload() fetches from the API on mount; effects are exactly where that belongs, and
    // there is no render-time value to derive instead.
    // oxlint-disable-next-line react/set-state-in-effect
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

  // Budgeting income or transfers is meaningless — neither counts as spending anywhere else.
  const spendingCategories = categories.filter((c) => !c.is_income && !c.is_transfer);
  const budgetByCategory = new Map((budgets?.budgets ?? []).map((b) => [b.category, b]));

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

      {/* ---------------- Budgets ---------------- */}
      <section className="mt-10">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Budgets</h2>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          A monthly target per category. Progress against it shows on the dashboard. Income and
          transfer categories are left out — they are not spending, so there is nothing to cap.
        </p>

        <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
            <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
              {spendingCategories.map((c) => {
                const saved = budgetByCategory.get(c.name);
                const draft = budgetDrafts[c.name] ?? (saved ? String(saved.monthlyLimit) : "");
                const parsed = Number(draft);
                const canSave =
                  draft.trim() !== "" &&
                  Number.isFinite(parsed) &&
                  parsed > 0 &&
                  parsed !== saved?.monthlyLimit;

                return (
                  <tr key={c.id}>
                    <td className="px-4 py-2">
                      <div className="text-sm text-slate-800 dark:text-slate-200">{c.name}</div>
                      {saved && (
                        <div className="text-xs text-slate-500">
                          {currency(saved.spent)} spent of {currency(saved.monthlyLimit)} this month
                        </div>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <input
                          type="number"
                          min="0"
                          step="10"
                          inputMode="decimal"
                          value={draft}
                          placeholder="No budget"
                          onChange={(e) =>
                            setBudgetDrafts((d) => ({ ...d, [c.name]: e.target.value }))
                          }
                          onKeyDown={(e) => {
                            if (e.key === "Enter" && canSave) {
                              run(() => setBudget(c.name, parsed), `Budget set for ${c.name}.`);
                            }
                          }}
                          className="w-28 rounded border border-slate-300 bg-white px-2 py-1 text-right text-sm dark:border-slate-700 dark:bg-slate-900"
                        />
                        <button
                          disabled={!canSave}
                          onClick={() =>
                            run(() => setBudget(c.name, parsed), `Budget set for ${c.name}.`)
                          }
                          className="rounded px-2 py-1 text-xs text-indigo-600 hover:bg-indigo-50 disabled:opacity-40 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
                        >
                          Save
                        </button>
                        <button
                          disabled={!saved}
                          onClick={() =>
                            run(() => deleteBudget(saved!.id), `Budget cleared for ${c.name}.`).then(
                              // Drop the draft too, or the input keeps showing the old number.
                              () => setBudgetDrafts((d) => ({ ...d, [c.name]: "" }))
                            )
                          }
                          className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 disabled:opacity-40 dark:hover:bg-slate-800"
                        >
                          Clear
                        </button>
                      </div>
                    </td>
                  </tr>
                );
              })}
            </tbody>
          </table>
        </div>

        {budgets && budgets.budgets.length > 0 && (
          <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
            {currency(budgets.totalSpent)} spent against {currency(budgets.totalLimit)} budgeted.
          </p>
        )}
      </section>

      {/* ---------------- Merchant memory ---------------- */}
      <section className="mt-10 mb-10">
        <div className="flex items-center justify-between">
          <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Merchant memory</h2>
          {memory && memory.count > 0 && (
            <button
              onClick={() => {
                if (confirm(`Forget all ${memory.count} remembered merchants? They'll be sent to the AI again on the next import.`)) {
                  run(() => forgetAllMerchants(), "Merchant memory cleared.");
                }
              }}
              className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
            >
              Forget all
            </button>
          )}
        </div>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          How each merchant was categorized last time. Known merchants are categorized without
          asking the AI, so repeat imports are quicker and cheaper. Correcting a transaction's
          category updates the entry here, which is what makes the correction stick.
        </p>

        {!memory || memory.count === 0 ? (
          <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
            Nothing learned yet. Import a statement, or correct a transaction's category, and the
            merchant will be remembered.
          </p>
        ) : (
          <>
            <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
              <strong>{memory.count}</strong> merchants remembered ·{" "}
              <strong>{memory.totalMemoryHits}</strong> transactions categorized from memory instead
              of the AI
            </p>
            <div className="mt-3 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
              <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
                <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
                  {memory.merchants.map((m) => (
                    <tr key={m.id}>
                      <td className="px-4 py-2">
                        <div className="text-sm text-slate-800 dark:text-slate-200">
                          {m.merchant_key}
                          {m.source === "user" && (
                            <span className="ml-2 rounded bg-indigo-100 px-1.5 py-0.5 text-[10px] uppercase text-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300">
                              your correction
                            </span>
                          )}
                        </div>
                        <div className="text-xs text-slate-500">
                          {m.category}
                          {/* A band is the whole reason one merchant can appear more than once. */}
                          {!m.is_catch_all && (
                            <span className="ml-1 text-indigo-600 dark:text-indigo-400">
                              when {currency(m.min_amount)}–{currency(m.max_amount)}
                            </span>
                          )}
                          {m.hit_count > 0 && ` · reused ${m.hit_count}×`}
                        </div>
                      </td>
                      <td className="px-4 py-2 text-right">
                        <button
                          onClick={() => run(() => forgetMerchant(m.id))}
                          className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                          title="Ask the AI about this merchant again next time"
                        >
                          Forget
                        </button>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
          </>
        )}

        <div className="mt-4 rounded-lg border border-dashed border-slate-300 p-4 dark:border-slate-700">
          <h3 className="text-sm font-medium text-slate-800 dark:text-slate-200">
            Split a merchant by amount
          </h3>
          <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
            For a merchant whose description never changes whatever you bought — a subscription
            and an order both arriving as the same text. A rule with an amount range wins over the
            merchant's general category, so smaller charges can go somewhere else. Leave the range
            empty to set the general category instead.
          </p>

          <div className="mt-3 flex flex-wrap items-center gap-2">
            <input
              value={ruleMerchant}
              onChange={(e) => setRuleMerchant(e.target.value)}
              placeholder="Merchant, e.g. AMAZON.COM"
              className="min-w-48 flex-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <select
              value={ruleCategory}
              onChange={(e) => setRuleCategory(e.target.value)}
              className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
            >
              <option value="">Category…</option>
              {spendingCategories.map((c) => (
                <option key={c.id} value={c.name}>{c.name}</option>
              ))}
            </select>
            <input
              type="number"
              min="0"
              value={ruleMin}
              onChange={(e) => setRuleMin(e.target.value)}
              placeholder="from"
              className="w-24 rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <input
              type="number"
              min="0"
              value={ruleMax}
              onChange={(e) => setRuleMax(e.target.value)}
              placeholder="to"
              className="w-24 rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <button
              disabled={!ruleMerchant.trim() || !ruleCategory}
              onClick={() =>
                run(
                  () =>
                    saveMerchantRule({
                      merchant_key: ruleMerchant.trim(),
                      category: ruleCategory,
                      // Blank means unbounded on that side; the server fills in the default.
                      ...(ruleMin.trim() ? { min_amount: Number(ruleMin) } : {}),
                      ...(ruleMax.trim() ? { max_amount: Number(ruleMax) } : {}),
                    }),
                  `Rule saved for ${ruleMerchant.trim().toUpperCase()}.`
                ).then(() => {
                  setRuleMerchant("");
                  setRuleMin("");
                  setRuleMax("");
                })
              }
              className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
            >
              Save rule
            </button>
          </div>
        </div>
      </section>
    </div>
  );
}

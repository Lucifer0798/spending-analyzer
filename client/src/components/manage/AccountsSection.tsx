import { useState } from "react";
import { createAccount, deleteAccount, updateAccount } from "../../api";
import type { Account, AccountType } from "../../types";
import { accountTypeLabel } from "../../format";
import type { Run } from "./types";

export function AccountsSection({
  accounts,
  types,
  currencies,
  run,
}: {
  accounts: Account[];
  types: AccountType[];
  currencies: string[];
  run: Run;
}) {
  const [newAccountName, setNewAccountName] = useState("");
  const [newAccountType, setNewAccountType] = useState<AccountType>("checking");
  const [newAccountCurrency, setNewAccountCurrency] = useState("USD");

  return (
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
                  <select
                    value={a.currency}
                    onChange={(e) => run(() => updateAccount(a.id, { currency: e.target.value }))}
                    title="Changes only how amounts are displayed — past transactions keep their stored numbers"
                    className="rounded border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
                  >
                    {(currencies.includes(a.currency) ? currencies : [a.currency, ...currencies]).map((c) => (
                      <option key={c} value={c}>{c}</option>
                    ))}
                  </select>
                  <button
                    onClick={() => run(() => updateAccount(a.id, { archived: !a.archived }))}
                    className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
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
        <select
          value={newAccountCurrency}
          onChange={(e) => setNewAccountCurrency(e.target.value)}
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
        >
          {currencies.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <button
          disabled={!newAccountName.trim()}
          onClick={() =>
            run(() => createAccount(newAccountName.trim(), newAccountType, newAccountCurrency)).then(() =>
              setNewAccountName("")
            )
          }
          className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          Add account
        </button>
      </div>
    </section>
  );
}

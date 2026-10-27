import { useState } from "react";
import { createCategory, deleteCategory, updateCategory } from "../../api";
import type { CategoryDetail } from "../../types";
import type { Run } from "./types";

export function CategoriesSection({ categories, run }: { categories: CategoryDetail[]; run: Run }) {
  const [newCategoryName, setNewCategoryName] = useState("");
  const [newCategoryKind, setNewCategoryKind] = useState<"spending" | "income" | "transfer">("spending");

  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Categories</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Custom categories are picked up by merchant memory once you categorize a transaction
        into one; the built-in rules only ever choose built-in categories. Anything marked
        as income or transfer is left out of spending totals. A group rolls several categories
        up under one label on the dashboard and in exports — built-in categories can be grouped too.
      </p>

      <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
        <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
          <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
            {categories.map((c) => (
              <tr key={c.id}>
                <td className="px-4 py-2">
                  <div className="text-sm text-slate-800 dark:text-slate-200">
                    {c.color && (
                      <span
                        className="mr-1.5 inline-block h-2.5 w-2.5 rounded-full align-middle"
                        style={{ backgroundColor: c.color }}
                        title={c.color}
                      />
                    )}
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
                    {c.group_name && (
                      <span className="ml-2 rounded bg-indigo-50 px-1.5 py-0.5 text-[10px] uppercase text-indigo-600 dark:bg-indigo-950/40 dark:text-indigo-300">
                        {c.group_name}
                      </span>
                    )}
                  </div>
                  <div className="text-xs text-slate-500">{c.transactionCount} transactions</div>
                </td>
                <td className="px-4 py-2 text-right">
                  <input
                    type="color"
                    value={c.color ?? "#94a3b8"}
                    title="Set this category's color"
                    onChange={(e) => run(() => updateCategory(c.id, { color: e.target.value }))}
                    className="ml-1 h-6 w-6 cursor-pointer rounded border border-slate-300 bg-white p-0 align-middle dark:border-slate-700 dark:bg-slate-900"
                  />
                  {c.color && (
                    <button
                      onClick={() => run(() => updateCategory(c.id, { color: null }))}
                      title="Clear color"
                      className="ml-0.5 rounded px-1 py-1 text-xs text-slate-400 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
                    >
                      ×
                    </button>
                  )}
                  <button
                    onClick={() => {
                      const group = prompt(
                        `Roll "${c.name}" up under a group (e.g. "Food"). Leave blank to ungroup:`,
                        c.group_name ?? ""
                      );
                      if (group !== null) {
                        run(() => updateCategory(c.id, { group_name: group.trim() || null }));
                      }
                    }}
                    className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                  >
                    {c.group_name ? "Change group" : "+ Group"}
                  </button>
                  {c.is_builtin ? (
                    <span className="ml-1 text-xs text-slate-400">built-in</span>
                  ) : (
                    <>
                      <button
                        onClick={() => {
                          const name = prompt(`Rename "${c.name}" to:`, c.name);
                          if (name && name.trim() && name.trim() !== c.name) {
                            run(() => updateCategory(c.id, { name: name.trim() }));
                          }
                        }}
                        className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
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
  );
}

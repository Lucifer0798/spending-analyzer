import { deleteTag } from "../../api";
import type { Tag } from "../../types";
import type { Run } from "./types";

export function TagsSection({ tags, run }: { tags: Tag[]; run: Run }) {
  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Tags</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Free-form labels set from Transactions, for filtering that cuts across categories — "business
        trip" or "reimbursable" alongside whatever categories those transactions already have.
      </p>

      {tags.length === 0 ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Nothing tagged yet. Add a tag to a transaction from the Transactions page.
        </p>
      ) : (
        <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
            <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
              {tags.map((t) => (
                <tr key={t.name}>
                  <td className="px-4 py-2">
                    <div className="text-sm text-slate-800 dark:text-slate-200">{t.name}</div>
                    <div className="text-xs text-slate-500">
                      {t.count} transaction{t.count === 1 ? "" : "s"}
                    </div>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => {
                        if (
                          confirm(
                            `Delete the tag "${t.name}"? It will be removed from ${t.count} transaction${t.count === 1 ? "" : "s"}.`
                          )
                        ) {
                          run(() => deleteTag(t.name), `Deleted "${t.name}".`);
                        }
                      }}
                      className="rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
                    >
                      Delete
                    </button>
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  );
}

import { clearRecurringOverride } from "../../api";
import type { RecurringOverride } from "../../types";
import type { Run } from "./types";

export function RecurringOverridesSection({ recurringOverrides, run }: { recurringOverrides: RecurringOverride[]; run: Run }) {
  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Recurring overrides</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Merchants flagged from the Recurring page — cancelling a reminder that stays until you
        clear it, or excluded from that list for good because it was never really a subscription.
      </p>

      {recurringOverrides.length === 0 ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Nothing flagged. Use "Flag to cancel" or "Not recurring" on a charge in Recurring.
        </p>
      ) : (
        <div className="mt-4 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
          <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
            <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
              {recurringOverrides.map((o) => (
                <tr key={o.id}>
                  <td className="px-4 py-2">
                    <div className="text-sm text-slate-800 dark:text-slate-200">{o.merchant_key}</div>
                    <div className="text-xs text-slate-500">
                      {o.action === "cancel" ? "Flagged to cancel" : "Marked not recurring"}
                    </div>
                  </td>
                  <td className="px-4 py-2 text-right">
                    <button
                      onClick={() => run(() => clearRecurringOverride(o.id))}
                      className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                      title={o.action === "cancel" ? "Un-flag" : "Show it in Recurring again"}
                    >
                      Remove
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

import { Fragment, useState } from "react";
import { deleteBudget, setBudget } from "../../api";
import type { BudgetPeriod, BudgetProgress, BudgetSummary, CategoryDetail, EscalationType } from "../../types";
import { currency } from "../../format";
import { dayMonth } from "../../dates";
import type { Run } from "./types";

function escalationSummary(b: BudgetProgress, currencyCode: string): string {
  const per =
    b.escalationFrequencyMonths === 1 ? "month"
    : b.escalationFrequencyMonths === 12 ? "year"
    : `${b.escalationFrequencyMonths} months`;
  const amount = b.escalationType === "percent"
    ? `${b.escalationValue}%`
    : currency(b.escalationValue ?? 0, 0, currencyCode);
  return `+${amount} every ${per}, since ${b.escalationStartMonth}`;
}

function rolloverSummary(b: BudgetProgress, currencyCode: string): string {
  const sign = b.rolloverCarryIn >= 0 ? "+" : "−";
  return `${sign}${currency(Math.abs(b.rolloverCarryIn), 0, currencyCode)} rolled over from last month`;
}

/** "this month" for a monthly budget; the actual dates for a weekly or quarterly one, since
 *  those don't line up with the single month the rest of the page is anchored to. */
function periodRangeLabel(b: BudgetProgress): string {
  if (b.period === "monthly") return "this month";
  const label = b.period === "weekly" ? "this week" : "this quarter";
  return `${label} (${dayMonth(b.periodStart)}–${dayMonth(b.periodEnd)})`;
}

/** `spendingCategories`: budgeting income or transfers is meaningless -- neither counts as spending. */
export function BudgetsSection({
  spendingCategories,
  budgets,
  run,
}: {
  spendingCategories: CategoryDetail[];
  budgets: BudgetSummary | null;
  run: Run;
}) {
  // Only holds categories the user has actually typed into. Everything else reads its value
  // straight from the saved budget, so there is no state to keep in step after a reload.
  const [budgetDrafts, setBudgetDrafts] = useState<Record<string, string>>({});

  // The auto-increase editor per category: which ones are expanded, and what's been typed into
  // each. A category not present here falls back to its saved schedule (or blank), the same
  // "no state to keep in step" approach the plain limit draft above uses.
  const [escalationOpen, setEscalationOpen] = useState<Record<string, boolean>>({});
  const [escalationDrafts, setEscalationDrafts] = useState<
    Record<string, { type: EscalationType | ""; value: string; frequencyMonths: string; startMonth: string }>
  >({});

  // Rollover is a plain on/off per category -- the server manages the actual start month (see
  // setBudget), so unlike escalation there's no value to draft, just which way the box is checked.
  const [rolloverDrafts, setRolloverDrafts] = useState<Record<string, boolean>>({});

  // Unlike rollover's start month, period has no server-managed value to fall back to -- the
  // client already shows and controls it directly, so a draft here works exactly like the plain
  // limit draft above: falls back to the saved budget's own period, or "monthly" for a new one.
  const [periodDrafts, setPeriodDrafts] = useState<Record<string, BudgetPeriod>>({});

  const budgetByCategory = new Map((budgets?.budgets ?? []).map((b) => [b.category, b]));
  // Null when accounts use different currencies — budgets is empty in that case too, so this
  // only matters for formatting the (necessarily present) saved rows below.
  const budgetCurrency = budgets?.currency ?? "USD";

  const escalationDraftFor = (category: string, saved: BudgetProgress | undefined) =>
    escalationDrafts[category] ?? {
      type: saved?.escalationType ?? "",
      value: saved?.escalationValue != null ? String(saved.escalationValue) : "",
      frequencyMonths: saved?.escalationFrequencyMonths != null ? String(saved.escalationFrequencyMonths) : "3",
      // Carried through so a Save that never opens this editor (only changing the plain limit)
      // keeps counting from whenever the schedule actually started, instead of quietly resetting
      // it to "now" and erasing every period it already elapsed.
      startMonth: saved?.escalationStartMonth ?? "",
    };

  return (
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
              const draft = budgetDrafts[c.name] ?? (saved ? String(saved.baseLimit) : "");
              const parsed = Number(draft);

              const isOpen = escalationOpen[c.name] ?? false;
              const eDraft = escalationDraftFor(c.name, saved);
              const eValueNum = Number(eDraft.value);
              const eFreqNum = Number(eDraft.frequencyMonths);
              const escalationValid =
                eDraft.type === "" ||
                (Number.isFinite(eValueNum) && eValueNum > 0 && Number.isFinite(eFreqNum) && eFreqNum > 0);
              const escalationForSave =
                eDraft.type === "" || !escalationValid
                  ? undefined
                  : {
                      type: eDraft.type,
                      value: eValueNum,
                      frequencyMonths: eFreqNum,
                      // Blank only for a schedule that never had one yet, in which case the
                      // server defaults it to the current month -- an existing schedule's start
                      // always carries forward unchanged.
                      startMonth: eDraft.startMonth || undefined,
                    };
              const savedEscalationKey = saved?.escalationType
                ? `${saved.escalationType}:${saved.escalationValue}:${saved.escalationFrequencyMonths}`
                : "";
              const draftEscalationKey = eDraft.type ? `${eDraft.type}:${eDraft.value}:${eDraft.frequencyMonths}` : "";

              const rolloverChecked = rolloverDrafts[c.name] ?? saved?.rolloverStartMonth != null;

              const periodDraft = periodDrafts[c.name] ?? saved?.period ?? "monthly";
              const isMonthly = periodDraft === "monthly";
              // Escalation and rollover only mean anything for a monthly budget -- forced off
              // here (rather than just hidden) so switching a budget that had either one to
              // weekly or quarterly actually clears them on save instead of silently keeping a
              // schedule the period picker no longer shows any control for.
              const monthlyOnlyEscalation = isMonthly ? escalationForSave : undefined;
              const rolloverForSave = isMonthly && rolloverChecked;

              const canSave =
                draft.trim() !== "" &&
                Number.isFinite(parsed) &&
                parsed > 0 &&
                (escalationValid || !isMonthly) &&
                (parsed !== saved?.baseLimit ||
                  draftEscalationKey !== savedEscalationKey ||
                  rolloverForSave !== (saved?.rolloverStartMonth != null) ||
                  periodDraft !== (saved?.period ?? "monthly"));

              const save = () =>
                run(
                  () => setBudget(c.name, parsed, monthlyOnlyEscalation, rolloverForSave, periodDraft),
                  `Budget set for ${c.name}.`
                );

              return (
                <Fragment key={c.id}>
                  <tr>
                    <td className="px-4 py-2">
                      <div className="text-sm text-slate-800 dark:text-slate-200">{c.name}</div>
                      {saved && (
                        <div className="text-xs text-slate-500">
                          {currency(saved.spent, 0, budgetCurrency)} spent of {currency(saved.monthlyLimit, 0, budgetCurrency)} {periodRangeLabel(saved)}
                        </div>
                      )}
                      {saved?.escalationType && !isOpen && (
                        <div className="text-xs text-slate-400">{escalationSummary(saved, budgetCurrency)}</div>
                      )}
                      {saved?.rolloverStartMonth && saved.rolloverCarryIn !== 0 && (
                        <div className="text-xs text-slate-400">{rolloverSummary(saved, budgetCurrency)}</div>
                      )}
                    </td>
                    <td className="px-4 py-2 text-right">
                      <div className="flex items-center justify-end gap-2">
                        <select
                          value={periodDraft}
                          onChange={(e) =>
                            setPeriodDrafts((d) => ({ ...d, [c.name]: e.target.value as BudgetPeriod }))
                          }
                          className="rounded border border-slate-300 bg-white px-1.5 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
                        >
                          <option value="weekly">per week</option>
                          <option value="monthly">per month</option>
                          <option value="quarterly">per quarter</option>
                        </select>
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
                            if (e.key === "Enter" && canSave) save();
                          }}
                          className="w-24 rounded border border-slate-300 bg-white px-2 py-1 text-right text-sm dark:border-slate-700 dark:bg-slate-900"
                        />
                        <button
                          disabled={!canSave}
                          onClick={save}
                          className="rounded px-2 py-1 text-xs text-indigo-600 hover:bg-indigo-50 disabled:opacity-40 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
                        >
                          Save
                        </button>
                        <button
                          disabled={!saved}
                          onClick={() =>
                            run(() => deleteBudget(saved!.id), `Budget cleared for ${c.name}.`).then(() => {
                              // Drop every draft too, or the inputs keep showing the old numbers.
                              setBudgetDrafts((d) => ({ ...d, [c.name]: "" }));
                              setEscalationDrafts((d) => ({
                                ...d,
                                [c.name]: { type: "", value: "", frequencyMonths: "3", startMonth: "" },
                              }));
                              setEscalationOpen((o) => ({ ...o, [c.name]: false }));
                              setRolloverDrafts((d) => ({ ...d, [c.name]: false }));
                              setPeriodDrafts((d) => ({ ...d, [c.name]: "monthly" }));
                            })
                          }
                          className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 disabled:opacity-40 dark:hover:bg-slate-800"
                        >
                          Clear
                        </button>
                      </div>
                      {/* Both are defined in whole months, so neither has a control once the
                          period above is weekly or quarterly -- matching the server, which
                          rejects the combination outright rather than silently ignoring it. */}
                      {isMonthly && (
                        <div className="mt-1 flex items-center justify-end gap-3">
                          <button
                            onClick={() => setEscalationOpen((o) => ({ ...o, [c.name]: !isOpen }))}
                            className="text-xs text-indigo-600 hover:underline dark:text-indigo-400"
                          >
                            {isOpen ? "Hide auto-increase" : saved?.escalationType ? "Edit auto-increase" : "+ Auto-increase"}
                          </button>
                          <label className="flex items-center gap-1 text-xs text-slate-500">
                            <input
                              type="checkbox"
                              checked={rolloverChecked}
                              onChange={(e) =>
                                setRolloverDrafts((d) => ({ ...d, [c.name]: e.target.checked }))
                              }
                            />
                            Roll over unused budget
                          </label>
                        </div>
                      )}
                    </td>
                  </tr>
                  {isOpen && isMonthly && (
                    <tr>
                      <td colSpan={2} className="bg-slate-50 px-4 py-3 dark:bg-slate-900/50">
                        <div className="flex flex-wrap items-center gap-2 text-sm">
                          <select
                            value={eDraft.type}
                            onChange={(e) =>
                              setEscalationDrafts((d) => ({
                                ...d,
                                [c.name]: { ...eDraft, type: e.target.value as EscalationType | "" },
                              }))
                            }
                            className="rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
                          >
                            <option value="">No auto-increase</option>
                            <option value="fixed">Add a fixed amount</option>
                            <option value="percent">Add a percentage</option>
                          </select>

                          {eDraft.type !== "" && (
                            <>
                              <input
                                type="number"
                                min="0"
                                step={eDraft.type === "percent" ? "1" : "10"}
                                inputMode="decimal"
                                value={eDraft.value}
                                placeholder={eDraft.type === "percent" ? "e.g. 5" : "e.g. 25"}
                                onChange={(e) =>
                                  setEscalationDrafts((d) => ({ ...d, [c.name]: { ...eDraft, value: e.target.value } }))
                                }
                                className="w-20 rounded border border-slate-300 bg-white px-2 py-1 text-right text-sm dark:border-slate-700 dark:bg-slate-900"
                              />
                              <span className="text-slate-500">{eDraft.type === "percent" ? "%" : budgetCurrency}</span>
                              <span className="text-slate-500">every</span>
                              <select
                                value={eDraft.frequencyMonths}
                                onChange={(e) =>
                                  setEscalationDrafts((d) => ({
                                    ...d,
                                    [c.name]: { ...eDraft, frequencyMonths: e.target.value },
                                  }))
                                }
                                className="rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
                              >
                                <option value="1">month</option>
                                <option value="3">quarter</option>
                                <option value="12">year</option>
                              </select>
                              <span className="text-xs text-slate-500">
                                — starting this month, and hit Save above to apply
                              </span>
                            </>
                          )}
                        </div>
                      </td>
                    </tr>
                  )}
                </Fragment>
              );
            })}
          </tbody>
        </table>
      </div>

      {budgets && budgets.budgets.length > 0 && (
        <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
          {currency(budgets.totalSpent, 0, budgetCurrency)} spent against {currency(budgets.totalLimit, 0, budgetCurrency)} budgeted.
        </p>
      )}
      {budgets?.mixedCurrencies && (
        <p className="mt-3 text-sm text-slate-500">
          Accounts use different currencies, so budget progress isn't shown here — targets can
          still be set below.
        </p>
      )}
    </section>
  );
}

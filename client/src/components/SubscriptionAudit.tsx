import { useEffect, useState } from "react";
import { fetchRecurringAudit, saveRecurringOverride } from "../api";
import type { DateRangeValue, SubscriptionFinding, SubscriptionAuditResponse } from "../types";
import { currency, currencyPrecise } from "../format";
import { dayMonthYear } from "../dates";

const CADENCE_WORD: Record<string, string> = {
  weekly: "a week",
  biweekly: "every 2 weeks",
  monthly: "a month",
  quarterly: "a quarter",
  yearly: "a year",
};

function describe(f: SubscriptionFinding, cur: string): { title: string; detail: string; stake: string } {
  const per = CADENCE_WORD[f.cadence] ?? f.cadence;
  switch (f.kind) {
    case "still_charging":
      return {
        title: `${f.names[0]} is still charging`,
        detail: `Charged ${currencyPrecise(f.toAmount!, cur)} on ${dayMonthYear(f.chargedOn!)}, after you flagged it to cancel on ${dayMonthYear(f.flaggedOn!)}. Worth checking the cancellation went through.`,
        stake: `${currency(f.yearlyAmount, 0, cur)} a year if it carries on`,
      };
    case "price_increase":
      return {
        title: `${f.names[0]} went up`,
        detail: `From ${currencyPrecise(f.fromAmount!, cur)} to ${currencyPrecise(f.toAmount!, cur)} ${per}.`,
        stake: `${currency(f.yearlyAmount, 0, cur)} more a year`,
      };
    default:
      return {
        title: `${f.merchants.length} ${f.category} charges ${per}`,
        detail: `${f.names.join(", ")} — worth checking you still use all of them.`,
        stake: `${currency(f.yearlyAmount, 0, cur)} a year together`,
      };
  }
}

/**
 * Recurring charges worth a second look: price rises, ones still charging after being flagged to
 * cancel, and several small plans in one category. Renders nothing when there's nothing to flag.
 * `refreshKey` follows the charges list below, so flagging one updates both.
 */
export function SubscriptionAudit({
  accountId,
  range,
  refreshKey,
  onChanged,
}: {
  accountId: number | null;
  range: DateRangeValue;
  refreshKey: unknown;
  onChanged: () => void;
}) {
  const [audit, setAudit] = useState<SubscriptionAuditResponse | null>(null);
  const [busy, setBusy] = useState<string | null>(null);

  useEffect(() => {
    fetchRecurringAudit(accountId, range).then(setAudit).catch(() => setAudit(null));
  }, [accountId, range, refreshKey]);

  if (!audit?.applicable || !audit.currency || audit.findings.length === 0) return null;
  const cur = audit.currency;

  const flag = async (merchant: string) => {
    setBusy(merchant);
    try {
      await saveRecurringOverride(merchant, "cancel");
      onChanged();
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="mt-6 rounded-lg border border-amber-200 bg-amber-50/60 p-4 dark:border-amber-900/60 dark:bg-amber-950/20">
      <h2 className="text-sm font-semibold text-amber-900 dark:text-amber-200">
        Worth a look · {audit.findings.length} {audit.findings.length === 1 ? "thing" : "things"}
      </h2>
      <ul className="mt-2 divide-y divide-amber-200/70 dark:divide-amber-900/50">
        {audit.findings.map((f) => {
          const d = describe(f, cur);
          const key = `${f.kind}|${f.merchants.join("+")}`;
          return (
            <li key={key} className="flex flex-wrap items-start justify-between gap-x-4 gap-y-2 py-2.5">
              <div className="min-w-0 flex-1">
                <p className="text-sm font-medium text-slate-900 dark:text-slate-100">{d.title}</p>
                <p className="text-xs text-slate-600 dark:text-slate-400">{d.detail}</p>
              </div>
              <div className="flex shrink-0 items-center gap-3">
                <span className="text-sm font-semibold tabular-nums text-slate-800 dark:text-slate-200">{d.stake}</span>
                {f.kind === "price_increase" && (
                  <button
                    disabled={busy !== null}
                    onClick={() => flag(f.merchants[0])}
                    title="Mark that you're cancelling this, as a reminder until the charges stop"
                    className="rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-white disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                  >
                    {busy === f.merchants[0] ? "Saving…" : "Flag to cancel"}
                  </button>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

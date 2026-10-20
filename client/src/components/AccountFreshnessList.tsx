import type { AccountFreshness } from "../types";
import { accountTypeLabel } from "../format";

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

function ago(days: number) {
  return days === 0 ? "today" : days === 1 ? "yesterday" : `${days} days ago`;
}

/**
 * How current each account is, statements due first. "Import into this" picks the account in the
 * form below, so catching up on one is a click and a file.
 */
export function AccountFreshnessList({
  accounts,
  dueAfterDays,
  targetAccountId,
  onPick,
  disabled,
}: {
  accounts: AccountFreshness[];
  dueAfterDays: number;
  targetAccountId: number;
  onPick: (accountId: number) => void;
  disabled: boolean;
}) {
  if (accounts.length === 0) return null;
  const ordered = [...accounts].sort((a, b) => Number(b.statementDue) - Number(a.statementDue));
  const dueCount = accounts.filter((a) => a.statementDue).length;

  return (
    <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Your accounts</h2>
        <span className="text-xs text-slate-500">
          {dueCount > 0
            ? `${dueCount} with nothing newer than ${dueAfterDays} days`
            : "Every account you import into is up to date"}
        </span>
      </div>
      <ul className="mt-2 divide-y divide-slate-100 dark:divide-slate-800">
        {ordered.map((a) => (
          <li key={a.accountId} className="flex flex-wrap items-center justify-between gap-x-4 gap-y-1 py-2 text-sm">
            <div className="min-w-0">
              <p className="text-slate-800 dark:text-slate-200">
                {a.accountName} <span className="text-slate-500">· {accountTypeLabel(a.accountType)}</span>
                {a.statementDue && (
                  <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[11px] font-medium text-amber-800 dark:bg-amber-950/60 dark:text-amber-300">
                    Statement due
                  </span>
                )}
              </p>
              <p className="text-xs text-slate-500">
                {a.latestTransactionDate && a.daysSinceTransaction !== null
                  ? `Newest transaction ${shortDate(a.latestTransactionDate)} (${ago(a.daysSinceTransaction)})`
                  : "Nothing imported yet"}
                {a.latestBalanceDate && a.daysSinceBalance !== null && (
                  <span className={a.balanceDue ? "text-amber-700 dark:text-amber-400" : undefined}>
                    {" · "}balance logged {shortDate(a.latestBalanceDate)}
                    {a.balanceDue && ` (${ago(a.daysSinceBalance)} — log a fresh one on Net Worth)`}
                  </span>
                )}
              </p>
            </div>
            {a.statementDue &&
              (a.accountId === targetAccountId ? (
                <span className="text-xs text-slate-500">Selected below</span>
              ) : (
                <button
                  disabled={disabled}
                  onClick={() => onPick(a.accountId)}
                  className="rounded-md border border-slate-300 px-2.5 py-1 text-xs font-medium text-slate-700 hover:bg-slate-100 disabled:opacity-40 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800"
                >
                  Import into this
                </button>
              ))}
          </li>
        ))}
      </ul>
    </div>
  );
}

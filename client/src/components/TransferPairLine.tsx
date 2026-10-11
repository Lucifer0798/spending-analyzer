import type { Transaction } from "../types";
import { currencyPrecise } from "../format";

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

function Side({ t, direction }: { t: Transaction; direction: "out" | "in" }) {
  return (
    <div className="min-w-0">
      <p className="text-xs text-slate-500">
        {direction === "out" ? "Out of" : "Into"}{" "}
        <span className="font-medium text-slate-700 dark:text-slate-300">{t.account_name ?? `Account ${t.account_id}`}</span>
        {" · "}
        {shortDate(t.date)}
        {t.category && <> · {t.category}</>}
      </p>
      <p className="truncate text-sm text-slate-900 dark:text-slate-100" title={t.description}>
        {t.description}
      </p>
    </div>
  );
}

/** One debit → credit pair: the amount once, then where it left and where it landed. */
export function TransferPairLine({ debit, credit }: { debit: Transaction; credit: Transaction }) {
  return (
    // Full width on a phone, so the actions beside it wrap underneath instead of squeezing it.
    <div className="w-full min-w-0 sm:w-auto sm:flex-1">
      <p className="text-sm font-semibold text-slate-900 tabular-nums dark:text-slate-100">
        {currencyPrecise(debit.amount, debit.account_currency ?? "USD")}
      </p>
      <div className="mt-1 grid gap-x-4 gap-y-1 sm:grid-cols-[1fr_auto_1fr] sm:items-center">
        <Side t={debit} direction="out" />
        <span aria-hidden className="hidden text-slate-400 sm:block">→</span>
        <Side t={credit} direction="in" />
      </div>
    </div>
  );
}

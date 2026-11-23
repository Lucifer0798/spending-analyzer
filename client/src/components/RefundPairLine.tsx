import type { Transaction } from "../types";
import { currencyPrecise } from "../format";
import { dayMonthYear } from "../dates";

function Side({ t, label, detail }: { t: Transaction; label: string; detail?: string }) {
  return (
    <div className="min-w-0">
      <p className="text-xs text-slate-500">
        {label} {dayMonthYear(t.date)}
        {detail && <> · {detail}</>}
      </p>
      <p className="truncate text-sm text-slate-900 dark:text-slate-100" title={t.description}>
        {t.description}
      </p>
    </div>
  );
}

/**
 * One purchase ← refund pair: the refund's amount (and the purchase's, when it's only part of it),
 * then the purchase and the refund side by side.
 */
export function RefundPairLine({ purchase, refund, daysAfter }: { purchase: Transaction; refund: Transaction; daysAfter?: number }) {
  const cur = purchase.account_currency ?? "USD";
  const partial = Math.round(refund.amount * 100) < Math.round(purchase.amount * 100);
  const days = daysAfter === undefined ? undefined : daysAfter === 0 ? "same day" : `${daysAfter} day${daysAfter === 1 ? "" : "s"} later`;
  return (
    // Full width on a phone, so the actions beside it wrap underneath instead of squeezing it.
    <div className="w-full min-w-0 sm:w-auto sm:flex-1">
      <p className="text-sm font-semibold text-slate-900 tabular-nums dark:text-slate-100">
        {currencyPrecise(refund.amount, cur)} back
        {partial && (
          <span className="font-normal text-slate-500"> of {currencyPrecise(purchase.amount, cur)}</span>
        )}
      </p>
      <div className="mt-1 grid gap-x-4 gap-y-1 sm:grid-cols-[1fr_auto_1fr] sm:items-center">
        <Side t={purchase} label="Bought" detail={[purchase.account_name, purchase.category].filter(Boolean).join(" · ")} />
        <span aria-hidden className="hidden text-slate-400 sm:block">←</span>
        <Side t={refund} label="Refunded" detail={days} />
      </div>
    </div>
  );
}

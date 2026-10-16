import { useEffect, useRef, useState } from "react";
import { CartesianGrid, Line, LineChart, ReferenceLine, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import { fetchCashFlow } from "../api";
import type { AccountCashFlow, CashFlowForecast } from "../types";
import { accountTypeLabel, currency, currencyPrecise } from "../format";

/** Events shown before "Show all". */
const PREVIEW = 6;
const AXIS = "#94a3b8";

function shortDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, { month: "short", day: "numeric", timeZone: "UTC" });
}

function longDate(iso: string) {
  return new Date(`${iso}T00:00:00Z`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

function BalanceTooltip({
  active,
  payload,
  label,
  currencyCode,
}: {
  active?: boolean;
  payload?: { value: number }[];
  label?: string;
  currencyCode: string;
}) {
  if (!active || !payload?.length || !label) return null;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900">
      <p className="font-medium text-slate-700 dark:text-slate-200">{longDate(label)}</p>
      <p className="tabular-nums text-slate-600 dark:text-slate-400">{currencyPrecise(payload[0].value, currencyCode)}</p>
    </div>
  );
}

function AccountForecastCard({ f, today }: { f: AccountCashFlow; today: string }) {
  const [showAll, setShowAll] = useState(false);
  const cur = f.currency;
  const events = showAll ? f.events : f.events.slice(0, PREVIEW);
  const crossesZero = f.lowestBalance <= 0;
  const todayInRange = f.daily.length > 0 && f.daily[0].date <= today && today <= f.daily[f.daily.length - 1].date;

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-sm font-semibold text-slate-800 dark:text-slate-200">
          {f.accountName} <span className="font-normal text-slate-500">· {accountTypeLabel(f.accountType)}</span>
        </h3>
        <span className="text-xs text-slate-500">
          Lowest {currency(f.lowestBalance, 0, cur)} on {shortDate(f.lowestDate)} · ends at{" "}
          {currency(f.endingBalance, 0, cur)}
        </span>
      </div>

      {f.warn && f.firstNegativeDate && (
        <p
          role="alert"
          className="mt-3 rounded-md bg-red-50 px-3 py-2 text-sm text-red-800 dark:bg-red-950/40 dark:text-red-300"
        >
          <span aria-hidden>⚠ </span>
          <strong>Projected to go below zero on {longDate(f.firstNegativeDate)}</strong>, reaching{" "}
          {currency(f.lowestBalance, 0, cur)} by {shortDate(f.lowestDate)}.
        </p>
      )}
      {f.stale && (
        <p className="mt-3 rounded-md bg-amber-50 px-3 py-2 text-sm text-amber-900 dark:bg-amber-950/40 dark:text-amber-200">
          The newest data for this account is from {longDate(f.asOf)} — import a newer statement or log a fresh
          balance for a forecast worth trusting.
        </p>
      )}

      <div className="mt-3 h-48">
        <ResponsiveContainer width="100%" height="100%">
          <LineChart data={f.daily} margin={{ top: 8, right: 8, bottom: 0, left: 0 }}>
            <CartesianGrid stroke={AXIS} strokeOpacity={0.25} vertical={false} />
            <XAxis
              dataKey="date"
              tickFormatter={shortDate}
              stroke={AXIS}
              fontSize={11}
              tickLine={false}
              minTickGap={28}
            />
            <YAxis
              stroke={AXIS}
              fontSize={11}
              tickLine={false}
              axisLine={false}
              width={64}
              tickFormatter={(v: number) => currency(v, 0, cur)}
            />
            <Tooltip content={<BalanceTooltip currencyCode={cur} />} cursor={{ stroke: AXIS, strokeDasharray: "3 3" }} />
            {/* Red only where below zero is a problem; a card is supposed to sit below it. */}
            {crossesZero && <ReferenceLine y={0} stroke={f.warn ? "#d03b3b" : AXIS} strokeDasharray="4 4" />}
            {todayInRange && (
              <ReferenceLine x={today} stroke={AXIS} strokeDasharray="2 4" label={{ value: "Today", position: "insideTopLeft", fontSize: 11, fill: AXIS }} />
            )}
            {/* A balance only changes when something lands, so it steps rather than sloping. */}
            <Line type="stepAfter" dataKey="balance" stroke="var(--series-1)" strokeWidth={2} dot={false} isAnimationActive={false} />
          </LineChart>
        </ResponsiveContainer>
      </div>

      {f.events.length === 0 ? (
        <p className="mt-2 text-xs text-slate-500">No recurring charges or deposits found for this account yet.</p>
      ) : (
        <>
          <ul className="mt-3 divide-y divide-slate-100 text-sm dark:divide-slate-800">
            {events.map((e, i) => (
              <li key={`${e.date}|${e.description}|${i}`} className="flex items-baseline justify-between gap-3 py-1.5">
                <span className="min-w-0 truncate">
                  <span className="mr-2 tabular-nums text-slate-500">{shortDate(e.date)}</span>
                  <span className="text-slate-800 dark:text-slate-200">{e.description}</span>
                  {e.overdue && (
                    <span className="ml-2 rounded bg-amber-100 px-1.5 py-0.5 text-[11px] text-amber-800 dark:bg-amber-950/60 dark:text-amber-300">
                      late — counted now
                    </span>
                  )}
                  {e.cancelling && (
                    <span className="ml-2 rounded bg-slate-100 px-1.5 py-0.5 text-[11px] text-slate-600 dark:bg-slate-800 dark:text-slate-400">
                      cancelling
                    </span>
                  )}
                </span>
                <span className="shrink-0 text-right tabular-nums">
                  <span className={e.amount > 0 ? "text-emerald-700 dark:text-emerald-400" : "text-slate-800 dark:text-slate-200"}>
                    {e.amount > 0 ? "+" : "−"}
                    {currencyPrecise(Math.abs(e.amount), cur)}
                  </span>
                  <span className="ml-3 hidden text-xs text-slate-500 sm:inline">→ {currency(e.balanceAfter, 0, cur)}</span>
                </span>
              </li>
            ))}
          </ul>
          {f.events.length > PREVIEW && (
            <button onClick={() => setShowAll((s) => !s)} className="mt-1 text-xs text-indigo-600 hover:underline dark:text-indigo-400">
              {showAll ? "Show fewer" : `Show all ${f.events.length}`}
            </button>
          )}
        </>
      )}

      <p className="mt-3 text-xs text-slate-500">
        Starts from the {currencyPrecise(f.loggedBalance, cur)} you logged on {longDate(f.balanceDate)}
        {f.importedSince > 0 &&
          `, plus ${f.importedSince} transaction${f.importedSince === 1 ? "" : "s"} imported since (through ${longDate(f.asOf)}): ${currencyPrecise(f.startingBalance, cur)}`}
        .
      </p>
    </div>
  );
}

/**
 * Where each account's balance is heading over the next two months, from its latest logged
 * balance plus the money that repeats on a schedule. `refreshKey` changes whenever a balance is
 * logged or removed on this page, so the forecast follows. `scrollIntoView` brings the section on
 * screen once it has loaded -- for arriving from the header's low-balance alert.
 */
export function CashFlowSection({ refreshKey, scrollIntoView = false }: { refreshKey: unknown; scrollIntoView?: boolean }) {
  const [forecast, setForecast] = useState<CashFlowForecast | null>(null);
  const [error, setError] = useState<string | null>(null);
  const sectionRef = useRef<HTMLElement>(null);
  // Once only: the forecast reloads whenever a balance is logged, and that mustn't yank the page.
  const scrolled = useRef(false);

  useEffect(() => {
    if (!scrollIntoView || !forecast || scrolled.current) return;
    scrolled.current = true;
    sectionRef.current?.scrollIntoView({ behavior: "smooth", block: "start" });
  }, [scrollIntoView, forecast]);

  useEffect(() => {
    fetchCashFlow()
      .then((f) => {
        setForecast(f);
        setError(null);
      })
      .catch((e) => setError(e instanceof Error ? e.message : "Couldn't load the cash-flow forecast."));
  }, [refreshKey]);

  if (error) {
    return <p className="mt-10 text-sm text-red-600 dark:text-red-400">{error}</p>;
  }
  if (!forecast) return null;

  return (
    <section ref={sectionRef} className="mt-10 scroll-mt-4">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Cash-flow forecast</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        The next {forecast.horizonDays} days for each account with a logged balance, from the charges and deposits
        that repeat on a schedule — rent, pay, subscriptions, regular transfers. Everyday spending isn't
        guessed at, so the real balance will run lower; treat a warning here as the optimistic case.
      </p>

      {forecast.accounts.length === 0 ? (
        <p className="mt-3 rounded-lg bg-slate-50 p-4 text-sm text-slate-500 dark:bg-slate-900">
          Log a balance for an account above to see where it's heading.
        </p>
      ) : (
        <div className="mt-4 space-y-4">
          {forecast.accounts.map((f) => (
            <AccountForecastCard key={f.accountId} f={f} today={forecast.today} />
          ))}
        </div>
      )}

      {forecast.accounts.length > 0 && forecast.withoutBalance.length > 0 && (
        <p className="mt-3 text-xs text-slate-500">
          Not forecast, with no balance logged: {forecast.withoutBalance.join(", ")}.
        </p>
      )}
    </section>
  );
}

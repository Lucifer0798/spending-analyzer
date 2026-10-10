import { useEffect, useState } from "react";
import { fetchDailySpend, fetchRecurring } from "../api";
import type { DailySpend, DateRangeValue, RecurringSeries } from "../types";
import { currency, currencyPrecise } from "../format";
import { upcomingBills, type UpcomingBill } from "../upcomingBills";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
  /** Opens the Transactions page filtered to this one day. */
  onOpenDay: (date: string) => void;
}

const WEEKDAYS = ["Mon", "Tue", "Wed", "Thu", "Fri", "Sat", "Sun"];
const STEPS = 5;
/** How far ahead recurring charges are projected onto the calendar. */
const BILL_HORIZON_DAYS = 60;
/** How far ahead the "Upcoming bills" list reaches -- the calendar itself shows the full horizon. */
const LIST_DAYS = 30;

function todayIso() {
  const d = new Date();
  return `${d.getFullYear()}-${String(d.getMonth() + 1).padStart(2, "0")}-${String(d.getDate()).padStart(2, "0")}`;
}

function addDaysIso(iso: string, days: number) {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

function monthKey(date: string) {
  return date.slice(0, 7);
}

function monthTitle(key: string) {
  const [y, m] = key.split("-").map(Number);
  return new Date(Date.UTC(y, m - 1, 1)).toLocaleDateString(undefined, { month: "long", year: "numeric", timeZone: "UTC" });
}

function dayLabel(date: string) {
  return new Date(`${date}T00:00:00Z`).toLocaleDateString(undefined, {
    weekday: "short",
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

/** Every month from the first to the last, inclusive -- so months with no spend can still be visited. */
function monthsBetween(first: string, last: string): string[] {
  const out: string[] = [];
  let [y, m] = first.split("-").map(Number);
  const [ly, lm] = last.split("-").map(Number);
  while (y < ly || (y === ly && m <= lm)) {
    out.push(`${y}-${String(m).padStart(2, "0")}`);
    m++;
    if (m === 13) {
      m = 1;
      y++;
    }
  }
  return out;
}

/**
 * Upper bounds of the five shade steps, from quantiles of every spending day in range -- one scale
 * for every month, so the same shade means the same amount as you page through, and one huge day
 * can't flatten every other day into the lightest step the way a max-based scale would.
 */
function stepBounds(totals: number[]): number[] {
  const sorted = [...totals].sort((a, b) => a - b);
  return Array.from({ length: STEPS - 1 }, (_, i) => sorted[Math.floor(((i + 1) / STEPS) * (sorted.length - 1))]);
}

function stepFor(total: number, bounds: number[]) {
  const i = bounds.findIndex((b) => total <= b);
  return i === -1 ? STEPS - 1 : i;
}

/**
 * Spend per day as a month grid, shaded by amount, Monday first. Click a day to see its
 * transactions. Renders nothing without spend in range, or across mixed currencies.
 */
export function SpendingCalendar({ accountId, range, onOpenDay }: Props) {
  const [data, setData] = useState<DailySpend | null>(null);
  // The month picked with the arrows; null means "the latest month with spend". Derived below
  // rather than reset in an effect, so a new range simply falls back to its own latest month.
  const [picked, setPicked] = useState<string | null>(null);
  // Recurring charges from full history, regardless of the date filter -- "what's due next"
  // belongs to now, not to whichever past range is being browsed.
  const [recurring, setRecurring] = useState<RecurringSeries[]>([]);

  useEffect(() => {
    fetchDailySpend(accountId, range).then(setData).catch(() => setData(null));
  }, [accountId, range]);

  useEffect(() => {
    fetchRecurring(accountId)
      .then((r) => setRecurring(r.mixedCurrencies ? [] : r.recurring))
      .catch(() => setRecurring([]));
  }, [accountId]);

  if (!data?.applicable || !data.currency) return null;

  const today = todayIso();
  const bills = upcomingBills(recurring, today, BILL_HORIZON_DAYS);
  if (data.days.length === 0 && bills.length === 0) return null;

  const cur = data.currency;
  const byDate = new Map(data.days.map((d) => [d.date, d]));
  const billsByDate = new Map<string, UpcomingBill[]>();
  for (const b of bills) billsByDate.set(b.date, [...(billsByDate.get(b.date) ?? []), b]);
  const soon = bills.filter((b) => b.overdue || b.date <= addDaysIso(today, LIST_DAYS));

  // From the first month with spend to the last month with spend or an expected bill, so the
  // arrows also reach the coming weeks.
  const edgeMonths = [...data.days.map((d) => monthKey(d.date)), ...bills.map((b) => monthKey(b.date))].sort();
  const months = monthsBetween(edgeMonths[0], edgeMonths[edgeMonths.length - 1]);
  // Default to the newest month with spend: what was spent comes first; upcoming months are one
  // click forward (or a click on a bill below).
  const newestSpendMonth = data.days.length ? monthKey(data.days[data.days.length - 1].date) : monthKey(today);
  const month = picked && months.includes(picked) ? picked : months.includes(newestSpendMonth) ? newestSpendMonth : months[months.length - 1];
  const index = months.indexOf(month);
  const bounds = data.days.length ? stepBounds(data.days.map((d) => d.total)) : [];

  const [y, m] = month.split("-").map(Number);
  const daysInMonth = new Date(Date.UTC(y, m, 0)).getUTCDate();
  const offset = (new Date(Date.UTC(y, m - 1, 1)).getUTCDay() + 6) % 7; // Monday = 0
  const dates = Array.from({ length: daysInMonth }, (_, i) => `${month}-${String(i + 1).padStart(2, "0")}`);
  const cells: (string | null)[] = [...Array(offset).fill(null), ...dates];
  while (cells.length % 7 !== 0) cells.push(null);
  const weeks = Array.from({ length: cells.length / 7 }, (_, w) => cells.slice(w * 7, w * 7 + 7));

  const monthDays = dates.map((d) => byDate.get(d)).filter((d) => d !== undefined);
  const monthTotal = monthDays.reduce((sum, d) => sum + d.total, 0);
  const busiest = monthDays.reduce<(typeof monthDays)[number] | null>((best, d) => (!best || d.total > best.total ? d : best), null);
  // Average per weekday across every such day in the month, zero-spend days included -- "Saturdays
  // average $X" should mean every Saturday, not just the ones something was bought on.
  const weekdayAverages = WEEKDAYS.map((_, wd) => {
    const ofWeekday = dates.filter((_, i) => (offset + i) % 7 === wd);
    const total = ofWeekday.reduce((sum, d) => sum + (byDate.get(d)?.total ?? 0), 0);
    return ofWeekday.length ? total / ofWeekday.length : 0;
  });

  const isFutureMonth = month > monthKey(today);
  const monthBills = bills.filter((b) => monthKey(b.date) === month && !b.overdue);

  const lowerBound = (step: number) => (step === 0 ? 0 : bounds[step - 1]);
  const legendTitle = (step: number) =>
    step === STEPS - 1
      ? `over ${currency(lowerBound(step), 0, cur)}`
      : `${currency(lowerBound(step), 0, cur)} – ${currency(bounds[step], 0, cur)}`;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-3 flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Spending calendar</h2>
        <div className="flex items-center gap-1">
          <button
            onClick={() => setPicked(months[index - 1])}
            disabled={index <= 0}
            aria-label="Previous month"
            className="rounded px-2 py-1 text-sm text-slate-600 hover:bg-slate-100 disabled:opacity-30 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            ‹
          </button>
          <span className="min-w-36 text-center text-sm font-medium text-slate-800 dark:text-slate-200">{monthTitle(month)}</span>
          <button
            onClick={() => setPicked(months[index + 1])}
            disabled={index >= months.length - 1}
            aria-label="Next month"
            className="rounded px-2 py-1 text-sm text-slate-600 hover:bg-slate-100 disabled:opacity-30 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            ›
          </button>
        </div>
      </div>

      <table className="w-full table-fixed border-separate" style={{ borderSpacing: "3px" }}>
        <caption className="sr-only">Spend per day in {monthTitle(month)}</caption>
        <thead>
          <tr>
            {WEEKDAYS.map((d) => (
              <th key={d} scope="col" className="pb-1 text-center text-[11px] font-medium text-slate-500">
                {d}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {weeks.map((week, w) => (
            <tr key={w}>
              {week.map((date, i) => {
                if (!date) return <td key={i} />;
                const day = byDate.get(date);
                const dayBills = billsByDate.get(date) ?? [];
                const billText = dayBills
                  .map((b) => `${b.overdue ? "overdue: " : "due: "}${b.merchant} ~${currencyPrecise(b.amount, cur)}${b.cancelling ? " (flagged to cancel)" : ""}`)
                  .join("; ");
                const label = [
                  day
                    ? `${dayLabel(date)}: ${currencyPrecise(day.total, cur)} across ${day.count} transaction${day.count === 1 ? "" : "s"}`
                    : `${dayLabel(date)}: no spending`,
                  billText,
                ]
                  .filter(Boolean)
                  .join(" · ");
                const step = day ? stepFor(day.total, bounds) : null;
                return (
                  <td key={date} className="p-0">
                    <button
                      onClick={() => onOpenDay(date)}
                      title={label}
                      aria-label={label}
                      className={`relative flex h-10 w-full items-start justify-end rounded-md p-1 text-[11px] font-medium transition hover:ring-2 hover:ring-indigo-400 focus:outline-none focus-visible:ring-2 focus-visible:ring-indigo-500 sm:h-12 ${
                        step === null ? "bg-slate-50 text-slate-400 dark:bg-slate-800/60 dark:text-slate-500" : ""
                      } ${date === today ? "outline outline-1 outline-offset-1 outline-slate-400" : ""}`}
                      style={step === null ? undefined : { background: `var(--seq-${step})`, color: `var(--seq-ink-${step})` }}
                    >
                      {Number(date.slice(8))}
                      {dayBills.length > 0 && (
                        // A second hue (orange) so a bill never reads as a shade of spend; the
                        // ring separates it from whatever fill is underneath.
                        <span
                          aria-hidden="true"
                          className="absolute bottom-1 left-1 h-2 w-2 rounded-full ring-2 ring-white dark:ring-slate-900"
                          style={{ background: "var(--series-2)" }}
                        />
                      )}
                    </button>
                  </td>
                );
              })}
            </tr>
          ))}
        </tbody>
        <tfoot className={isFutureMonth ? "hidden" : undefined}>
          <tr>
            {weekdayAverages.map((avg, i) => (
              <td key={WEEKDAYS[i]} className="pt-1 text-center text-[10px] text-slate-500" title={`Average per ${WEEKDAYS[i]} this month`}>
                {currency(avg, 0, cur)}
              </td>
            ))}
          </tr>
        </tfoot>
      </table>

      <div className="mt-3 flex flex-wrap items-center justify-between gap-2 text-xs text-slate-500">
        <span>
          {isFutureMonth ? (
            monthBills.length > 0 ? (
              <>
                Coming up: {monthBills.length} expected charge{monthBills.length === 1 ? "" : "s"}, about{" "}
                {currency(monthBills.reduce((sum, b) => sum + b.amount, 0), 0, cur)}
              </>
            ) : (
              "Nothing expected this month."
            )
          ) : monthDays.length > 0 ? (
            <>
              {currency(monthTotal, 0, cur)} over {monthDays.length} day{monthDays.length === 1 ? "" : "s"} with spending
              {busiest && <> · busiest {dayLabel(busiest.date)} ({currency(busiest.total, 0, cur)})</>}
              {" · bottom row: average per weekday"}
            </>
          ) : (
            "No spending this month."
          )}
        </span>
        <span className="flex items-center gap-1" aria-label="Shade scale, least to most spend">
          Less
          {Array.from({ length: STEPS }, (_, s) => (
            <span key={s} title={legendTitle(s)} className="inline-block h-3 w-3 rounded-sm" style={{ background: `var(--seq-${s})` }} />
          ))}
          More
          {bills.length > 0 && (
            <>
              <span className="ml-3 inline-block h-2 w-2 rounded-full" style={{ background: "var(--series-2)" }} />
              bill due
            </>
          )}
        </span>
      </div>

      {soon.length > 0 && (
        <div className="mt-4 border-t border-slate-100 pt-3 dark:border-slate-800">
          <h3 className="text-xs font-semibold uppercase tracking-wide text-slate-500">
            Upcoming bills · next {LIST_DAYS} days
          </h3>
          <ul className="mt-2 divide-y divide-slate-100 dark:divide-slate-800">
            {soon.map((b) => (
              <li key={`${b.merchant}|${b.date}`} className="flex flex-wrap items-baseline justify-between gap-x-3 py-1.5 text-sm">
                <button
                  onClick={() => setPicked(monthKey(b.date))}
                  title="Show this month on the calendar"
                  className="flex min-w-0 items-baseline gap-2 text-left hover:underline"
                >
                  <span className={`w-24 shrink-0 text-xs ${b.overdue ? "font-medium text-amber-700 dark:text-amber-400" : "text-slate-500"}`}>
                    {b.overdue ? "overdue" : dayLabel(b.date)}
                  </span>
                  <span className={`truncate text-slate-800 dark:text-slate-200 ${b.cancelling ? "line-through opacity-70" : ""}`}>
                    {b.merchant}
                  </span>
                </button>
                <span className="text-right text-slate-700 dark:text-slate-300">
                  ~{currencyPrecise(b.amount, cur)}
                  <span className="ml-2 text-xs text-slate-500">{b.cancelling ? "flagged to cancel" : b.cadence}</span>
                </span>
              </li>
            ))}
          </ul>
          <p className="mt-2 text-xs text-slate-500">
            About {currency(soon.filter((b) => !b.overdue && !b.cancelling).reduce((sum, b) => sum + b.amount, 0), 0, cur)}{" "}
            expected over the next {LIST_DAYS} days{soon.some((b) => b.overdue) ? " (not counting overdue ones)" : ""}, from
            charges the Recurring page has detected.
            {soon.some((b) => b.overdue) && " Overdue means its expected date passed with no newer charge imported yet."}
          </p>
        </div>
      )}
    </div>
  );
}

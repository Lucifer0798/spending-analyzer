import type { RecurringSeries } from "./types";

/** One expected charge on one date -- a recurring series projected forward. */
export interface UpcomingBill {
  date: string;
  merchant: string;
  /** What it usually costs: the latest charge if the price changed, else the average. */
  amount: number;
  cadence: RecurringSeries["cadence"];
  /** Its expected date has passed without a newer charge being imported. */
  overdue: boolean;
  /** You've flagged it to cancel on the Recurring page -- shown, but marked. */
  cancelling: boolean;
}

function addDays(iso: string, days: number) {
  const d = new Date(`${iso}T00:00:00Z`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/**
 * Every expected charge from {@code today} through {@code horizonDays} ahead, oldest first.
 * Each series starts at its next expected date and repeats every `median_interval_days`, so a
 * weekly charge appears each week rather than once. A next date already in the past stays as a
 * single overdue entry (the statement covering it may simply not be imported yet) and isn't
 * projected further -- guessing past a missed charge would only compound the guess.
 */
export function upcomingBills(series: RecurringSeries[], today: string, horizonDays: number): UpcomingBill[] {
  const end = addDays(today, horizonDays);
  const bills: UpcomingBill[] = [];
  for (const s of series) {
    const amount = s.price_changed ? s.last_amount : s.average_amount;
    const base = { merchant: s.merchant, amount, cadence: s.cadence, cancelling: s.flagged_for_cancellation };
    if (s.next_expected_date < today) {
      bills.push({ ...base, date: s.next_expected_date, overdue: true });
      continue;
    }
    const step = Math.max(1, s.median_interval_days);
    for (let d = s.next_expected_date; d <= end; d = addDays(d, step)) {
      bills.push({ ...base, date: d, overdue: false });
    }
  }
  return bills.sort((a, b) => (a.date === b.date ? a.merchant.localeCompare(b.merchant) : a.date.localeCompare(b.date)));
}

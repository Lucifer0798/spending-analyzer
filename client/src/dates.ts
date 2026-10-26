// Date strings in this app are ISO calendar dates ("2026-10-14") with no time or zone. Each is parsed
// and formatted in UTC, so a date can't slide to the day before or after in a timezone west or east
// of Greenwich. These were copied into a dozen components before living here.

const AT_MIDNIGHT_UTC = "T00:00:00Z";

/** "Oct 14". */
export function dayMonth(iso: string) {
  return new Date(`${iso}${AT_MIDNIGHT_UTC}`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    timeZone: "UTC",
  });
}

/** "Oct 14, 2026". */
export function dayMonthYear(iso: string) {
  return new Date(`${iso}${AT_MIDNIGHT_UTC}`).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
    timeZone: "UTC",
  });
}

/** "October 2026", from a "2026-10" month key. */
export function monthYear(month: string) {
  return new Date(`${month}-01${AT_MIDNIGHT_UTC}`).toLocaleDateString(undefined, {
    month: "long",
    year: "numeric",
    timeZone: "UTC",
  });
}

/** The ISO date `days` after (or, negative, before) `iso`. */
export function addDays(iso: string, days: number) {
  const d = new Date(`${iso}${AT_MIDNIGHT_UTC}`);
  d.setUTCDate(d.getUTCDate() + days);
  return d.toISOString().slice(0, 10);
}

/** A Date's calendar date in UTC, as ISO. */
export function isoDate(d: Date) {
  return d.toISOString().slice(0, 10);
}

/** Today, as ISO. */
export function todayIso() {
  return isoDate(new Date());
}

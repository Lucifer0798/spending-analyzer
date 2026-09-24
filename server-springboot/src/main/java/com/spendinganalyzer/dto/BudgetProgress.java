package com.spendinganalyzer.dto;

/**
 * One category's budget measured against what was actually spent in a month.
 *
 * @param monthlyLimit  the limit actually in effect for the measured month -- the base target,
 *                      escalated forward if a schedule is set, plus any rollover carried in from
 *                      prior months. This is what {@code spent} is compared against.
 * @param baseLimit     the raw target as originally set, before escalation or rollover
 * @param remaining     negative once the budget is blown, which is more useful than clamping to zero
 * @param percentUsed   uncapped, so 140% reads as 140 rather than a saturated 100
 * @param status        one of {@code under}, {@code near}, {@code over}
 * @param rolloverStartMonth the {@code YYYY-MM} rollover starts accumulating from, or null when
 *                            unused budget doesn't carry into the next month
 * @param rolloverCarryIn    unused budget (or overspend, negative) carried in from prior months
 *                           since {@code rolloverStartMonth}; zero when rollover is off or this
 *                           is the start month itself, with nothing yet to carry
 * @param period      {@code weekly}, {@code monthly}, or {@code quarterly}
 * @param periodStart the first day of the range {@code spent} was actually measured over
 * @param periodEnd   the last day of that range -- together with {@code periodStart}, lets the
 *                    frontend label a weekly or quarterly row with its own dates rather than the
 *                    single month name the page as a whole is anchored to
 */
public record BudgetProgress(
        long id,
        String category,
        double monthlyLimit,
        double baseLimit,
        double spent,
        double remaining,
        double percentUsed,
        String status,
        String escalationType,
        Double escalationValue,
        Integer escalationFrequencyMonths,
        String escalationStartMonth,
        String rolloverStartMonth,
        double rolloverCarryIn,
        String period,
        String periodStart,
        String periodEnd
) {}

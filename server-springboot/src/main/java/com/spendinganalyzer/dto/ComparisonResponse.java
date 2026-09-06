package com.spendinganalyzer.dto;

/**
 * A period-over-period comparison needs two well-defined, equal-length windows, which an
 * unbounded or half-open date filter does not have. Rather than silently comparing something
 * arbitrary, this says outright whether one was computed at all -- the same honesty the
 * "full history" forecast labelling already applies to predictions.
 *
 * <p>{@code currency} is null exactly when {@code applicable} is false -- either there was no
 * well-defined range to compare, or (for "all accounts") the accounts in scope don't share a
 * currency, in which case a comparison would mix them and {@link
 * com.spendinganalyzer.controller.InsightsController#comparison} refuses the same way it refuses
 * an unbounded range.
 */
public record ComparisonResponse(boolean applicable, PeriodComparison comparison, String currency) {

    public static ComparisonResponse of(PeriodComparison comparison, String currency) {
        return new ComparisonResponse(true, comparison, currency);
    }

    public static final ComparisonResponse NOT_APPLICABLE = new ComparisonResponse(false, null, null);
}

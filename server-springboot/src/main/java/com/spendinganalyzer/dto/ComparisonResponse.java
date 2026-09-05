package com.spendinganalyzer.dto;

/**
 * A period-over-period comparison needs two well-defined, equal-length windows, which an
 * unbounded or half-open date filter does not have. Rather than silently comparing something
 * arbitrary, this says outright whether one was computed at all -- the same honesty the
 * "full history" forecast labelling already applies to predictions.
 */
public record ComparisonResponse(boolean applicable, PeriodComparison comparison) {

    public static ComparisonResponse of(PeriodComparison comparison) {
        return new ComparisonResponse(true, comparison);
    }

    public static final ComparisonResponse NOT_APPLICABLE = new ComparisonResponse(false, null);
}

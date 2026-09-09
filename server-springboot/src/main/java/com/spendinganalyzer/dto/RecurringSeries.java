package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record RecurringSeries(
        String merchant,
        String category,
        String cadence,
        @JsonProperty("average_amount") double averageAmount,
        @JsonProperty("last_amount") double lastAmount,
        @JsonProperty("last_date") String lastDate,
        @JsonProperty("next_expected_date") String nextExpectedDate,
        int occurrences,
        @JsonProperty("median_interval_days") int medianIntervalDays,
        @JsonProperty("annualized_cost") double annualizedCost,
        String confidence,
        /** True when there's a "cancel" override for this merchant — see {@link com.spendinganalyzer.model.RecurringOverride}. */
        @JsonProperty("flagged_for_cancellation") boolean flaggedForCancellation,
        /** The override's id, so the frontend can clear it — null unless {@code flaggedForCancellation}. */
        @JsonProperty("override_id") Long overrideId
) {
    /**
     * Detection itself never looks at overrides — {@link
     * com.spendinganalyzer.service.RecurringDetectionService} always produces the "no override"
     * shape, and the controller layers this in afterward, the same separation {@code
     * MerchantCategory}'s catch-all bands keep from the categorization that consults them.
     */
    public RecurringSeries withOverride(boolean flaggedForCancellation, Long overrideId) {
        return new RecurringSeries(merchant, category, cadence, averageAmount, lastAmount, lastDate,
                nextExpectedDate, occurrences, medianIntervalDays, annualizedCost, confidence,
                flaggedForCancellation, overrideId);
    }
}

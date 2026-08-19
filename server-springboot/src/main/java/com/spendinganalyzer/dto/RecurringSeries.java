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
        String confidence
) {}

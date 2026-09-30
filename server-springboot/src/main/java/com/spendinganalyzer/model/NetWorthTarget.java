package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A single target for overall net worth -- at most one at a time, compared against the
 * forecast's trend line rather than funded by logged contributions the way a savings goal is.
 */
public record NetWorthTarget(
        @JsonProperty("target_amount") double targetAmount,
        @JsonProperty("target_date") String targetDate,
        String currency,
        @JsonProperty("updated_at") String updatedAt
) {}

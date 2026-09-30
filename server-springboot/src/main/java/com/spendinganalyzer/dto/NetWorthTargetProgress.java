package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A net worth target compared against the forecast's trend line. {@code projectedDate} is when
 * the line is expected to cross {@code targetAmount} -- left null once already achieved, or when
 * the trend isn't heading toward it at all (flat or declining while still short), the same "no
 * projected date beats a nonsensical one" honesty a savings goal's own pace projection applies.
 */
public record NetWorthTargetProgress(
        @JsonProperty("target_amount") double targetAmount,
        @JsonProperty("target_date") String targetDate,
        boolean achieved,
        @JsonProperty("projected_date") String projectedDate
) {}

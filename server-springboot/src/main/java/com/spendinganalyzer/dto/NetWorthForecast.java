package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Net worth projected forward from the logged history's linear trend, plus a steadier 3-point
 * moving average of the most recently logged values as a less reactive alternative. Present only
 * once there are at least two distinct logged dates -- a single balance has no trend to fit a
 * line through, the same "no projected date at all beats a nonsensical one" honesty a savings
 * goal's pace projection already applies.
 *
 * @param in1Month      the trend line evaluated one calendar month from today
 * @param in3Months     the trend line evaluated three calendar months from today
 * @param in6Months     the trend line evaluated six calendar months from today
 * @param movingAverage the average of the most recent (up to three) logged totals -- a steadier
 *                      reference point, not itself a projection to a future date
 * @param trend         {@code increasing}, {@code decreasing}, or {@code stable}, based on how
 *                      the 6-month projection compares to the current total
 */
public record NetWorthForecast(
        @JsonProperty("in_1_month") double in1Month,
        @JsonProperty("in_3_months") double in3Months,
        @JsonProperty("in_6_months") double in6Months,
        @JsonProperty("moving_average") double movingAverage,
        String trend
) {}

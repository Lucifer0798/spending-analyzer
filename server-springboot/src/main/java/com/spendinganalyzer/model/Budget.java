package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A monthly spending target for one category, with an optional auto-increase schedule.
 *
 * @param monthlyLimit               the base target the user set, before any escalation
 * @param escalationType             {@code fixed}, {@code percent}, or null for no schedule
 * @param escalationValue            dollars per period (fixed) or percent per period (percent)
 * @param escalationFrequencyMonths  how many months between increases
 * @param escalationStartMonth       the {@code YYYY-MM} the schedule starts counting periods from
 */
public record Budget(
        long id,
        String category,
        @JsonProperty("monthly_limit") double monthlyLimit,
        @JsonProperty("escalation_type") String escalationType,
        @JsonProperty("escalation_value") Double escalationValue,
        @JsonProperty("escalation_frequency_months") Integer escalationFrequencyMonths,
        @JsonProperty("escalation_start_month") String escalationStartMonth,
        @JsonProperty("updated_at") String updatedAt
) {}

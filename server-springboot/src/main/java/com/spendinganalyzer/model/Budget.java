package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * A monthly spending target for one category, with an optional auto-increase schedule and an
 * optional envelope/rollover.
 *
 * @param monthlyLimit               the base target the user set, before escalation or rollover
 * @param escalationType             {@code fixed}, {@code percent}, or null for no schedule
 * @param escalationValue            dollars per period (fixed) or percent per period (percent)
 * @param escalationFrequencyMonths  how many months between increases
 * @param escalationStartMonth       the {@code YYYY-MM} the schedule starts counting periods from
 * @param rolloverStartMonth         the {@code YYYY-MM} rollover starts accumulating from, or
 *                                   null when unused budget doesn't carry into the next month
 * @param period                     {@code weekly}, {@code monthly}, or {@code quarterly} -- how
 *                                   often the target renews and what range spend is measured
 *                                   over. Escalation and rollover only ever apply when this is
 *                                   {@code monthly}; both are defined in whole months.
 */
public record Budget(
        long id,
        String category,
        @JsonProperty("monthly_limit") double monthlyLimit,
        @JsonProperty("escalation_type") String escalationType,
        @JsonProperty("escalation_value") Double escalationValue,
        @JsonProperty("escalation_frequency_months") Integer escalationFrequencyMonths,
        @JsonProperty("escalation_start_month") String escalationStartMonth,
        @JsonProperty("rollover_start_month") String rolloverStartMonth,
        String period,
        @JsonProperty("updated_at") String updatedAt
) {
    public static final String MONTHLY = "monthly";
    public static final String WEEKLY = "weekly";
    public static final String QUARTERLY = "quarterly";
}

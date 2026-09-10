package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * One manually-logged amount toward a goal. Positive is money added, negative is money taken
 * back out -- both are contributions to the running total, just signed.
 */
public record GoalContribution(
        long id,
        @JsonProperty("goal_id") long goalId,
        double amount,
        String date,
        String note,
        @JsonProperty("created_at") String createdAt
) {}

package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Recommendation(
        String category,
        String insight,
        @JsonProperty("suggested_action") String suggestedAction,
        @JsonProperty("potential_monthly_savings") double potentialMonthlySavings
) {}

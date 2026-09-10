package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

/** A savings target with a name, an amount, and optionally a date to reach it by. */
public record Goal(
        long id,
        String name,
        @JsonProperty("target_amount") double targetAmount,
        @JsonProperty("target_date") String targetDate,
        String currency,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {}

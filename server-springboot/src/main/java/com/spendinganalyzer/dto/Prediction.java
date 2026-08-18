package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonProperty;

public record Prediction(
        String category,
        @JsonProperty("predicted_next_month") double predictedNextMonth,
        String trend,
        String confidence,
        String rationale
) {}

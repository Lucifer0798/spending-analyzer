package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategorizeResponse(int categorized, Integer total, String message) {

    public static CategorizeResponse of(int categorized, int total) {
        return new CategorizeResponse(categorized, total, null);
    }

    public static CategorizeResponse noneFound() {
        return new CategorizeResponse(0, null, "No uncategorized transactions.");
    }
}

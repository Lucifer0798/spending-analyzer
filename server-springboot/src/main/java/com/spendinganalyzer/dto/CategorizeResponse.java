package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategorizeResponse(
        int categorized,
        Integer total,
        String message,
        /** Categorised from merchant memory -- what the user has taught. */
        int fromMemory,
        /** Categorised by the built-in keyword rules. */
        int fromRules,
        /** Left uncategorized: neither memory nor any rule recognised them. Set these by hand. */
        int unmatched
) {
    public static CategorizeResponse of(int fromMemory, int fromRules, int total) {
        return new CategorizeResponse(fromMemory + fromRules, total, null, fromMemory, fromRules,
                total - fromMemory - fromRules);
    }

    public static CategorizeResponse noneFound() {
        return new CategorizeResponse(0, null, "No uncategorized transactions.", 0, 0, 0);
    }
}

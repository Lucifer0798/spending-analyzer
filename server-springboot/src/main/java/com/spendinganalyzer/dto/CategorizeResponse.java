package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategorizeResponse(
        int categorized,
        Integer total,
        String message,
        /** Categorised from merchant memory -- what the user has taught. */
        int fromMemory,
        /** Categorised by the user's own keyword rules. */
        int fromKeywordRules,
        /** Categorised by the built-in keyword rules. */
        int fromRules,
        /** Left uncategorized: neither memory nor any rule recognised them. Set these by hand. */
        int unmatched,
        /**
         * The same counts for just one upload's rows, when the request named its batch -- so an
         * import summary describes the file it's about. Null otherwise.
         */
        Batch batch,
        /** With a batch named: rows from earlier uploads that got categorized in passing. */
        Integer earlier
) {
    /** One upload's share of a run. {@code total} is how many of its rows were still uncategorized. */
    public record Batch(int total, int fromMemory, int fromKeywordRules, int fromRules, int unmatched) {}

    public static CategorizeResponse of(int fromMemory, int fromKeywordRules, int fromRules, int total) {
        return of(fromMemory, fromKeywordRules, fromRules, total, null, null);
    }

    public static CategorizeResponse of(int fromMemory, int fromKeywordRules, int fromRules, int total,
                                        Batch batch, Integer earlier) {
        int categorized = fromMemory + fromKeywordRules + fromRules;
        return new CategorizeResponse(categorized, total, null, fromMemory, fromKeywordRules, fromRules,
                total - categorized, batch, earlier);
    }

    public static CategorizeResponse noneFound(boolean withBatch) {
        return new CategorizeResponse(0, null, "No uncategorized transactions.", 0, 0, 0, 0,
                withBatch ? new Batch(0, 0, 0, 0, 0) : null, withBatch ? 0 : null);
    }
}

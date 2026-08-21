package com.spendinganalyzer.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record CategorizeResponse(
        int categorized,
        Integer total,
        String message,
        /** Categorised from merchant memory without asking the model. */
        int fromMemory,
        /** Categorised by the model. */
        int fromModel,
        /** Distinct merchants actually sent to the model, which is what drives cost. */
        int merchantsQueried
) {

    public static CategorizeResponse of(int fromMemory, int fromModel, int merchantsQueried, int total) {
        return new CategorizeResponse(fromMemory + fromModel, total, null, fromMemory, fromModel, merchantsQueried);
    }

    public static CategorizeResponse noneFound() {
        return new CategorizeResponse(0, null, "No uncategorized transactions.", 0, 0, 0);
    }
}

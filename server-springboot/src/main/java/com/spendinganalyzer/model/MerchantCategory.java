package com.spendinganalyzer.model;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.Comparator;
import java.util.List;
import java.util.Optional;

public record MerchantCategory(
        long id,
        @JsonProperty("merchant_key") String merchantKey,
        String category,
        /** Inclusive lower bound on the transaction amount. */
        @JsonProperty("min_amount") double minAmount,
        /** Exclusive upper bound, so adjacent bands meet without overlapping. */
        @JsonProperty("max_amount") double maxAmount,
        /** 'user' entries are corrections and outrank 'ai' guesses. */
        String source,
        @JsonProperty("hit_count") int hitCount,
        @JsonProperty("created_at") String createdAt,
        @JsonProperty("updated_at") String updatedAt
) {
    public static final String SOURCE_AI = "ai";
    public static final String SOURCE_USER = "user";

    /**
     * Stands in for "no upper bound". A stored number rather than NULL because SQLite treats
     * NULLs as distinct in a UNIQUE index, which would let duplicate catch-all rows through.
     */
    public static final double UNBOUNDED = 1_000_000_000_000d;

    /** True for a rule covering every amount — what every entry was before bands existed. */
    @JsonProperty("is_catch_all")
    public boolean isCatchAll() {
        return minAmount <= 0 && maxAmount >= UNBOUNDED;
    }

    public boolean matches(double amount) {
        return amount >= minAmount && amount < maxAmount;
    }

    /**
     * Picks the rule to apply for a given amount.
     *
     * <p>Narrowest band first: a rule written for £0–15 is a more specific statement about this
     * transaction than the merchant's catch-all, so it should win. Source breaks ties, keeping
     * the existing promise that a correction outranks a guess. In practice only the user creates
     * banded rules — the model only ever writes catch-alls — so the two rarely compete.
     */
    public static Optional<MerchantCategory> bestMatch(List<MerchantCategory> rules, double amount) {
        return rules.stream()
                .filter(r -> r.matches(amount))
                .min(Comparator
                        .comparingDouble((MerchantCategory r) -> r.maxAmount() - r.minAmount())
                        .thenComparing(r -> SOURCE_USER.equals(r.source()) ? 0 : 1)
                        .thenComparingLong(MerchantCategory::id));
    }
}

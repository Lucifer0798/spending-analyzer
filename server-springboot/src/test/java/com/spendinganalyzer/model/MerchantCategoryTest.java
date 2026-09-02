package com.spendinganalyzer.model;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Which rule wins for a given amount — the whole point of bands. */
class MerchantCategoryTest {

    private static MerchantCategory rule(
            long id, String category, double min, double max, String source) {
        return new MerchantCategory(
                id, "AMAZON.COM", category, min, max, source, 0, "2026-01-01", "2026-01-01");
    }

    private static MerchantCategory catchAll(long id, String category, String source) {
        return rule(id, category, 0, MerchantCategory.UNBOUNDED, source);
    }

    @Test
    @DisplayName("a band beats the merchant's catch-all, because it says more about this amount")
    void narrowerBandWins() {
        List<MerchantCategory> rules = List.of(
                catchAll(1, "Shopping", MerchantCategory.SOURCE_USER),
                rule(2, "Subscriptions", 0, 15, MerchantCategory.SOURCE_USER));

        assertThat(MerchantCategory.bestMatch(rules, 9.99)).get()
                .extracting(MerchantCategory::category).isEqualTo("Subscriptions");
        assertThat(MerchantCategory.bestMatch(rules, 60.00)).get()
                .extracting(MerchantCategory::category).isEqualTo("Shopping");
    }

    @Test
    @DisplayName("the upper bound is exclusive, so adjacent bands meet without overlapping")
    void upperBoundIsExclusive() {
        List<MerchantCategory> rules = List.of(
                rule(1, "Subscriptions", 0, 15, MerchantCategory.SOURCE_USER),
                rule(2, "Shopping", 15, 100, MerchantCategory.SOURCE_USER));

        assertThat(MerchantCategory.bestMatch(rules, 14.99)).get()
                .extracting(MerchantCategory::category).isEqualTo("Subscriptions");
        // Exactly on the boundary belongs to the band above, with no ambiguity about which.
        assertThat(MerchantCategory.bestMatch(rules, 15.00)).get()
                .extracting(MerchantCategory::category).isEqualTo("Shopping");
    }

    @Test
    @DisplayName("an amount outside every band matches nothing, so the model is asked")
    void noMatchWhenOutsideEveryBand() {
        List<MerchantCategory> rules = List.of(rule(1, "Subscriptions", 0, 15, MerchantCategory.SOURCE_USER));

        assertThat(MerchantCategory.bestMatch(rules, 60.00)).isEmpty();
    }

    @Test
    @DisplayName("a correction still outranks a guess when the bands are the same width")
    void userBeatsAiOnEqualBands() {
        List<MerchantCategory> rules = List.of(
                catchAll(1, "Shopping", MerchantCategory.SOURCE_AI),
                catchAll(2, "Groceries", MerchantCategory.SOURCE_USER));

        assertThat(MerchantCategory.bestMatch(rules, 42.00)).get()
                .extracting(MerchantCategory::category).isEqualTo("Groceries");
    }

    @Test
    @DisplayName("no rules at all matches nothing rather than throwing")
    void handlesNoRules() {
        assertThat(MerchantCategory.bestMatch(List.of(), 10.00)).isEmpty();
    }

    @Test
    @DisplayName("knows which rules cover every amount")
    void reportsCatchAll() {
        assertThat(catchAll(1, "Shopping", MerchantCategory.SOURCE_AI).isCatchAll()).isTrue();
        assertThat(rule(2, "Subscriptions", 0, 15, MerchantCategory.SOURCE_USER).isCatchAll()).isFalse();
    }
}

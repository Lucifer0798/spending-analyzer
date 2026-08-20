package com.spendinganalyzer.service;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Normalisation is shared by recurring detection and merchant memory, so a change here
 * moves both what counts as one recurring series and what counts as one cache entry.
 */
class MerchantNormalizerTest {

    @Test
    @DisplayName("strips store numbers and order references")
    void stripsPerVisitNoise() {
        assertThat(MerchantNormalizer.normalize("WHOLE FOODS MARKET #123")).isEqualTo("WHOLE FOODS MARKET");
        assertThat(MerchantNormalizer.normalize("AMAZON.COM*AB123")).isEqualTo("AMAZON.COM");
        assertThat(MerchantNormalizer.normalize("STARBUCKS STORE 4521")).isEqualTo("STARBUCKS STORE");
    }

    @Test
    @DisplayName("is case-insensitive and trims surrounding whitespace")
    void normalisesCaseAndWhitespace() {
        assertThat(MerchantNormalizer.normalize("netflix.com")).isEqualTo("NETFLIX.COM");
        assertThat(MerchantNormalizer.normalize("  Spotify  ")).isEqualTo("SPOTIFY");
    }

    @Test
    @DisplayName("different branches of one merchant collapse to the same key")
    void differentBranchesShareOneKey() {
        // This is what lets one cache entry answer for every branch of a chain.
        assertThat(MerchantNormalizer.normalize("WHOLE FOODS MARKET #123"))
                .isEqualTo(MerchantNormalizer.normalize("WHOLE FOODS MARKET #987"));
        assertThat(MerchantNormalizer.normalize("AMAZON.COM*AB123"))
                .isEqualTo(MerchantNormalizer.normalize("AMAZON.COM*ZZ999"));
    }

    @Test
    @DisplayName("genuinely different merchants stay distinct")
    void distinctMerchantsDoNotCollide() {
        assertThat(MerchantNormalizer.normalize("SHELL OIL 12345"))
                .isNotEqualTo(MerchantNormalizer.normalize("TRADER JOES #88"));
    }

    @Test
    @DisplayName("never reduces a description to an empty key")
    void neverProducesAnEmptyKey() {
        // An all-numeric merchant would otherwise normalise to "" and collide with
        // every other all-numeric merchant, pooling unrelated transactions.
        assertThat(MerchantNormalizer.normalize("12345")).isNotEmpty();
        assertThat(MerchantNormalizer.normalize("#999")).isNotEmpty();
        assertThat(MerchantNormalizer.normalize("12345"))
                .isNotEqualTo(MerchantNormalizer.normalize("67890"));
    }
}

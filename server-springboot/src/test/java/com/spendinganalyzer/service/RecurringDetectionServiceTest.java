package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class RecurringDetectionServiceTest {

    private final RecurringDetectionService service = new RecurringDetectionService();

    private static Transaction tx(String date, String description, double amount, String category) {
        return new Transaction(0, date, description, amount, "debit", category,
                "ai", "batch", "2026-01-01", 1L, "Default");
    }

    private static List<Transaction> monthly(String description, double amount, String category, String... dates) {
        List<Transaction> out = new ArrayList<>();
        for (String d : dates) out.add(tx(d, description, amount, category));
        return out;
    }

    // Merchant normalisation now lives in MerchantNormalizer, shared with merchant
    // memory, and is covered by MerchantNormalizerTest.

    // --- positive detection -----------------------------------------------------

    @Test
    @DisplayName("detects a monthly subscription charged the same amount")
    void detectsMonthlySubscription() {
        List<RecurringSeries> found = service.detect(
                monthly("NETFLIX.COM", 15.49, "Subscriptions",
                        "2026-05-04", "2026-06-04", "2026-07-04"));

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.merchant()).isEqualTo("NETFLIX.COM");
            assertThat(s.cadence()).isEqualTo("monthly");
            assertThat(s.occurrences()).isEqualTo(3);
            assertThat(s.averageAmount()).isEqualTo(15.49);
            assertThat(s.category()).isEqualTo("Subscriptions");
            // 15.49 charged roughly 12 times a year.
            assertThat(s.annualizedCost()).isBetween(180.0, 195.0);
        });
    }

    @Test
    @DisplayName("predicts the next charge date from the observed interval")
    void predictsNextExpectedDate() {
        List<RecurringSeries> found = service.detect(
                monthly("SPOTIFY", 10.99, "Subscriptions",
                        "2026-05-12", "2026-06-12", "2026-07-12"));

        assertThat(found).singleElement().satisfies(s -> {
            assertThat(s.lastDate()).isEqualTo("2026-07-12");
            // Median gap is 31 days, so the next one is expected in mid-August.
            assertThat(s.nextExpectedDate()).startsWith("2026-08-1");
        });
    }

    @Test
    @DisplayName("detects weekly and yearly cadences, not just monthly")
    void detectsOtherCadences() {
        var weekly = service.detect(monthly("GYM", 12.00, "Personal Care",
                "2026-05-01", "2026-05-08", "2026-05-15", "2026-05-22"));
        assertThat(weekly).singleElement()
                .extracting(RecurringSeries::cadence).isEqualTo("weekly");

        var yearly = service.detect(monthly("DOMAIN RENEWAL", 14.00, "Subscriptions",
                "2024-03-01", "2025-03-02", "2026-03-01"));
        assertThat(yearly).singleElement()
                .extracting(RecurringSeries::cadence).isEqualTo("yearly");
    }

    @Test
    @DisplayName("tolerates small billing-date drift")
    void toleratesBillingDateDrift() {
        List<RecurringSeries> found = service.detect(
                monthly("COMCAST CABLE", 89.99, "Utilities",
                        "2026-05-18", "2026-06-20", "2026-07-17"));

        assertThat(found).singleElement()
                .extracting(RecurringSeries::cadence).isEqualTo("monthly");
    }

    // --- negative detection (the part that keeps the view useful) ----------------

    @Test
    @DisplayName("regular visits with varying amounts are not a subscription")
    void ignoresRegularButVariableSpending() {
        // Fortnightly grocery runs: consistent timing, very different totals each time.
        List<Transaction> groceries = List.of(
                tx("2026-05-01", "WHOLE FOODS MARKET #123", 84.32, "Groceries"),
                tx("2026-05-15", "WHOLE FOODS MARKET #123", 132.10, "Groceries"),
                tx("2026-05-29", "WHOLE FOODS MARKET #123", 61.75, "Groceries"),
                tx("2026-06-12", "WHOLE FOODS MARKET #123", 148.90, "Groceries"));

        assertThat(service.detect(groceries)).isEmpty();
    }

    @Test
    @DisplayName("a consistent amount at irregular intervals is not a subscription")
    void ignoresConsistentAmountAtIrregularIntervals() {
        List<Transaction> coffee = List.of(
                tx("2026-05-01", "STARBUCKS", 5.50, "Dining & Coffee"),
                tx("2026-05-03", "STARBUCKS", 5.50, "Dining & Coffee"),
                tx("2026-06-14", "STARBUCKS", 5.50, "Dining & Coffee"),
                tx("2026-07-30", "STARBUCKS", 5.50, "Dining & Coffee"));

        assertThat(service.detect(coffee)).isEmpty();
    }

    @Test
    @DisplayName("same-day charges on two accounts still register as one monthly subscription")
    void collapsesSameDayChargesIntoOneBillingEvent() {
        // Viewing all accounts at once puts two copies of each charge on the same date.
        // Left uncollapsed these produce zero-day gaps, dragging the median interval to
        // zero and hiding the subscription entirely.
        List<Transaction> both = new ArrayList<>();
        for (String date : List.of("2026-05-04", "2026-06-04", "2026-07-04")) {
            both.add(tx(date, "NETFLIX.COM", 15.49, "Subscriptions"));
            both.add(tx(date, "NETFLIX.COM", 15.49, "Subscriptions"));
        }

        assertThat(service.detect(both)).singleElement().satisfies(s -> {
            assertThat(s.cadence()).isEqualTo("monthly");
            assertThat(s.occurrences()).isEqualTo(3);          // billing events, not rows
            assertThat(s.averageAmount()).isEqualTo(30.98);    // both cards charged that day
        });
    }

    @Test
    @DisplayName("two charges are not enough to call something recurring")
    void requiresAtLeastThreeOccurrences() {
        assertThat(service.detect(monthly("NETFLIX.COM", 15.49, "Subscriptions",
                "2026-05-04", "2026-06-04"))).isEmpty();
    }

    @Test
    @DisplayName("handles an empty ledger without failing")
    void handlesEmptyInput() {
        assertThat(service.detect(List.of())).isEmpty();
    }

    // --- ordering and confidence ------------------------------------------------

    @Test
    @DisplayName("ranks the most expensive subscription first")
    void sortsByAnnualisedCostDescending() {
        List<Transaction> all = new ArrayList<>();
        all.addAll(monthly("NETFLIX.COM", 15.49, "Subscriptions", "2026-05-04", "2026-06-04", "2026-07-04"));
        all.addAll(monthly("COMCAST CABLE", 89.99, "Utilities", "2026-05-18", "2026-06-18", "2026-07-18"));

        List<RecurringSeries> found = service.detect(all);

        assertThat(found).hasSize(2);
        assertThat(found.get(0).merchant()).isEqualTo("COMCAST CABLE");
        assertThat(found.get(0).annualizedCost()).isGreaterThan(found.get(1).annualizedCost());
    }

    @Test
    @DisplayName("more occurrences at an identical amount raises confidence")
    void reportsHigherConfidenceForLongerIdenticalRuns() {
        List<RecurringSeries> found = service.detect(
                monthly("NETFLIX.COM", 15.49, "Subscriptions",
                        "2026-01-04", "2026-02-04", "2026-03-04",
                        "2026-04-04", "2026-05-04", "2026-06-04"));

        assertThat(found).singleElement()
                .extracting(RecurringSeries::confidence).isEqualTo("high");
    }
}

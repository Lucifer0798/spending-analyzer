package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.SpendingAnomaly;
import com.spendinganalyzer.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AnomalyDetectionServiceTest {

    private final AnomalyDetectionService service = new AnomalyDetectionService();

    private static Transaction tx(String date, String description, double amount, String category) {
        return new Transaction(0, date, description, amount, "debit", category,
                "ai", "batch", "2026-01-01", 1L, "Default", "USD");
    }

    private static List<Transaction> groceries(double... amounts) {
        List<Transaction> out = new ArrayList<>();
        for (int i = 0; i < amounts.length; i++) {
            out.add(tx("2026-06-" + String.format("%02d", i + 1), "GROCERY STORE", amounts[i], "Groceries"));
        }
        return out;
    }

    @Test
    @DisplayName("flags a transaction well above its category's typical amount")
    void flagsAnAnomaly() {
        List<SpendingAnomaly> found = service.detect(groceries(80, 75, 82, 78, 400));

        assertThat(found).singleElement().satisfies(a -> {
            assertThat(a.amount()).isEqualTo(400.0);
            assertThat(a.typicalAmount()).isEqualTo(80.0);
            assertThat(a.multiplier()).isEqualTo(5.0);
            assertThat(a.category()).isEqualTo("Groceries");
        });
    }

    @Test
    @DisplayName("a consistent category has nothing to flag")
    void noAnomalyWhenConsistent() {
        List<SpendingAnomaly> found = service.detect(groceries(80, 75, 82, 78, 85));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("too few transactions in a category means no reliable typical amount to compare against")
    void tooFewSamplesIsIgnored() {
        // Only 4 transactions -- one short of the minimum -- even though 400 is clearly an outlier.
        List<SpendingAnomaly> found = service.detect(groceries(80, 75, 82, 400));

        assertThat(found).isEmpty();
    }

    @Test
    @DisplayName("an uncategorized transaction can't be compared to anything, and is skipped")
    void uncategorizedTransactionIsSkipped() {
        List<Transaction> transactions = new ArrayList<>(groceries(80, 75, 82, 78, 79));
        transactions.add(tx("2026-06-05", "MYSTERY CHARGE", 500.0, null));

        assertThat(service.detect(transactions)).isEmpty();
    }

    @Test
    @DisplayName("categories are judged independently of each other")
    void categoriesAreIndependent() {
        // Groceries stay within normal range; only the Dining group's outlier should be flagged.
        List<Transaction> transactions = new ArrayList<>(groceries(80, 75, 82, 78, 79));
        transactions.add(tx("2026-06-06", "COFFEE", 5, "Dining & Coffee"));
        transactions.add(tx("2026-06-07", "COFFEE", 4, "Dining & Coffee"));
        transactions.add(tx("2026-06-08", "COFFEE", 6, "Dining & Coffee"));
        transactions.add(tx("2026-06-09", "COFFEE", 5, "Dining & Coffee"));
        transactions.add(tx("2026-06-10", "HUGE TAB", 100, "Dining & Coffee"));

        List<SpendingAnomaly> found = service.detect(transactions);

        assertThat(found).extracting("description").containsExactly("HUGE TAB");
    }

    @Test
    @DisplayName("results are sorted by multiplier, largest first")
    void sortedByMultiplierDescending() {
        // Groceries: median 80, 300 is 3.75x. Dining: median 5, 100 is 20x -- Dining should sort first.
        List<Transaction> transactions = new ArrayList<>(groceries(80, 75, 82, 78, 300));
        transactions.add(tx("2026-06-06", "COFFEE", 5, "Dining & Coffee"));
        transactions.add(tx("2026-06-07", "COFFEE", 4, "Dining & Coffee"));
        transactions.add(tx("2026-06-08", "COFFEE", 6, "Dining & Coffee"));
        transactions.add(tx("2026-06-09", "COFFEE", 5, "Dining & Coffee"));
        transactions.add(tx("2026-06-10", "HUGE TAB", 100, "Dining & Coffee"));

        List<SpendingAnomaly> found = service.detect(transactions);

        assertThat(found).extracting("description").containsExactly("HUGE TAB", "GROCERY STORE");
    }
}

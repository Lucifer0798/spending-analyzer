package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.PeriodComparison;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Rolled back after each test so the shared test database is left as it was found -- see
 * StatsServiceRangeTest for why this needs a real database rather than a mock.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceComparisonTest {

    @Autowired
    private StatsService statsService;

    @Autowired
    private TransactionRepository transactions;

    private static ParsedTransaction spend(String date, String category, double amount) {
        return new ParsedTransaction(date, category + " purchase", amount, "debit", category);
    }

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                // Previous period: February.
                spend("2026-02-10", "Groceries", 100.00),
                spend("2026-02-20", "Dining", 50.00),
                spend("2026-02-25", "Transport", 40.00),
                // Current period: March. Groceries went up, Dining vanished, Entertainment is new.
                spend("2026-03-05", "Groceries", 150.00),
                spend("2026-03-15", "Entertainment", 30.00)
        ), "comparison-test-batch", 1L);
    }

    private Optional<PeriodComparison> compare(String from, String to) {
        return statsService.computeComparison(null, DateRange.of(from, to));
    }

    @Test
    @DisplayName("compares a month against the immediately preceding period of the same length")
    void comparesAgainstThePrecedingPeriodOfEqualLength() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        // March is 31 days; the preceding 31-day window ends the day before March starts.
        assertThat(comparison.previousRange().from()).isEqualTo("2026-01-29");
        assertThat(comparison.previousRange().to()).isEqualTo("2026-02-28");
    }

    @Test
    @DisplayName("totals and the change between them are correct")
    void totalsAndChangeAreCorrect() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        assertThat(comparison.currentTotal()).isEqualTo(180.00);
        assertThat(comparison.previousTotal()).isEqualTo(190.00);
        assertThat(comparison.changeAmount()).isEqualTo(-10.00);
    }

    @Test
    @DisplayName("a category present in both periods reports its own change")
    void categoryPresentInBothPeriods() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        CategoryComparison groceries = comparison.categories().stream()
                .filter(c -> c.category().equals("Groceries")).findFirst().orElseThrow();

        assertThat(groceries.currentTotal()).isEqualTo(150.00);
        assertThat(groceries.previousTotal()).isEqualTo(100.00);
        assertThat(groceries.changeAmount()).isEqualTo(50.00);
        assertThat(groceries.changePercent()).isEqualTo(50.00);
    }

    @Test
    @DisplayName("a category only in the current period has no percentage to report")
    void categoryOnlyInCurrentPeriod() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        CategoryComparison entertainment = comparison.categories().stream()
                .filter(c -> c.category().equals("Entertainment")).findFirst().orElseThrow();

        assertThat(entertainment.previousTotal()).isZero();
        assertThat(entertainment.changeAmount()).isEqualTo(30.00);
        // There is no meaningful percentage change from zero -- not "infinite", not "0%".
        assertThat(entertainment.changePercent()).isNull();
    }

    @Test
    @DisplayName("a category only in the previous period shows as a full drop to zero")
    void categoryOnlyInPreviousPeriod() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        CategoryComparison dining = comparison.categories().stream()
                .filter(c -> c.category().equals("Dining")).findFirst().orElseThrow();

        assertThat(dining.currentTotal()).isZero();
        assertThat(dining.changeAmount()).isEqualTo(-50.00);
        assertThat(dining.changePercent()).isEqualTo(-100.00);
    }

    @Test
    @DisplayName("categories are ordered biggest increase first, biggest decrease last")
    void categoriesAreOrderedByChange() {
        PeriodComparison comparison = compare("2026-03-01", "2026-03-31").orElseThrow();

        // Groceries +50, Entertainment +30 (new), Transport -40 (gone), Dining -50 (gone).
        assertThat(comparison.categories()).extracting("category")
                .containsExactly("Groceries", "Entertainment", "Transport", "Dining");
    }

    @Test
    @DisplayName("an unbounded range has no defined length to compare against")
    void unboundedRangeIsNotApplicable() {
        assertThat(statsService.computeComparison(null, DateRange.ALL)).isEmpty();
    }

    @Test
    @DisplayName("a half-open range has no defined length to compare against either")
    void halfOpenRangeIsNotApplicable() {
        assertThat(statsService.computeComparison(null, DateRange.of("2026-03-01", null))).isEmpty();
        assertThat(statsService.computeComparison(null, DateRange.of(null, "2026-03-31"))).isEmpty();
    }
}

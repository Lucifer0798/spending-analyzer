package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
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

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Exercises date filtering against a real database. The pure {@link com.spendinganalyzer.dto.DateRange}
 * tests only prove the parameters are validated; these prove the SQL actually narrows the
 * result, which is where an off-by-one boundary would hide.
 *
 * <p>Rolled back after each test so the shared test database is left as it was found.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceRangeTest {

    @Autowired
    private StatsService statsService;

    @Autowired
    private TransactionRepository transactions;

    private static ParsedTransaction spend(String date, String description, double amount) {
        return new ParsedTransaction(date, description, amount, "debit", "Groceries");
    }

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                spend("2026-01-15", "JANUARY SHOP", 100.00),
                spend("2026-02-15", "FEBRUARY SHOP", 200.00),
                spend("2026-03-15", "MARCH SHOP", 300.00),
                spend("2026-04-15", "APRIL SHOP", 400.00)
        ), "range-test-batch", 1L);
    }

    @Test
    @DisplayName("an unbounded range includes every month")
    void unboundedRangeIncludesEverything() {
        var totals = statsService.computeMonthlyTotals(null, DateRange.ALL);

        assertThat(totals).extracting("month")
                .contains("2026-01", "2026-02", "2026-03", "2026-04");
    }

    @Test
    @DisplayName("a range narrows the months returned")
    void rangeNarrowsResults() {
        var totals = statsService.computeMonthlyTotals(null, DateRange.of("2026-02-01", "2026-03-31"));

        assertThat(totals).extracting("month").containsExactly("2026-02", "2026-03");
        assertThat(totals).extracting("total").containsExactly(200.00, 300.00);
    }

    @Test
    @DisplayName("both bounds are inclusive")
    void boundsAreInclusive() {
        // A transaction sitting exactly on each boundary must be included, not dropped.
        var totals = statsService.computeMonthlyTotals(null, DateRange.of("2026-02-15", "2026-03-15"));

        assertThat(totals).extracting("month").containsExactly("2026-02", "2026-03");
    }

    @Test
    @DisplayName("an open-ended range filters only the side that is set")
    void openEndedRangesFilterOneSide() {
        var since = statsService.computeMonthlyTotals(null, DateRange.of("2026-03-01", null));
        assertThat(since).extracting("month").containsExactly("2026-03", "2026-04");

        var until = statsService.computeMonthlyTotals(null, DateRange.of(null, "2026-02-28"));
        assertThat(until).extracting("month").containsExactly("2026-01", "2026-02");
    }

    @Test
    @DisplayName("category totals respect the range")
    void categoryTotalsRespectRange() {
        var all = statsService.computeCategoryTotals(null, DateRange.ALL);
        var narrowed = statsService.computeCategoryTotals(null, DateRange.of("2026-04-01", "2026-04-30"));

        double allGroceries = all.stream().filter(c -> c.category().equals("Groceries"))
                .mapToDouble(c -> c.total()).sum();
        double aprilGroceries = narrowed.stream().filter(c -> c.category().equals("Groceries"))
                .mapToDouble(c -> c.total()).sum();

        assertThat(aprilGroceries).isEqualTo(400.00);
        assertThat(allGroceries).isGreaterThan(aprilGroceries);
    }

    @Test
    @DisplayName("the transaction list and its count agree under the same range")
    void listAndCountAgree() {
        DateRange range = DateRange.of("2026-02-01", "2026-03-31");

        var rows = transactions.find(null, null, null, range, 100, 0);
        int count = transactions.count(null, null, null, range);

        // The two build their predicates from the same helper; if they ever diverge,
        // pagination silently reports the wrong total.
        assertThat(rows).hasSize(count);
        assertThat(rows).extracting("description")
                .containsExactlyInAnyOrder("FEBRUARY SHOP", "MARCH SHOP");
    }

    @Test
    @DisplayName("a range covering nothing returns empty rather than failing")
    void emptyRangeReturnsNothing() {
        var totals = statsService.computeMonthlyTotals(null, DateRange.of("2030-01-01", "2030-12-31"));
        assertThat(totals).isEmpty();
    }

    @Test
    @DisplayName("available bounds span the earliest and latest transaction")
    void reportsAvailableBounds() {
        DateRange bounds = statsService.availableRange(null);

        assertThat(bounds.from()).isNotNull();
        assertThat(bounds.to()).isNotNull();
        assertThat(bounds.from()).isLessThanOrEqualTo("2026-01-15");
        assertThat(bounds.to()).isGreaterThanOrEqualTo("2026-04-15");
    }
}

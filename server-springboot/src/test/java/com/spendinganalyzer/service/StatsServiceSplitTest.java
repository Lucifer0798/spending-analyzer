package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryMonthlySeries;
import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Proves a split share is what every StatsService total actually measures, not the full charged
 * amount -- the one behavior this feature exists for. Rolled back after each test so the shared
 * test database is left as it was found.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceSplitTest {

    @Autowired
    private StatsService statsService;

    @Autowired
    private TransactionRepository transactions;

    private long seedSplitTransaction(String date, String category, double amount, Double splitShare) {
        transactions.insertBatch(
                List.of(new ParsedTransaction(date, "GROUP DINNER", amount, "debit", category)),
                "split-test-batch", 1L);
        Transaction seeded = transactions.find(category, null, 1L, DateRange.ALL, 200, 0).stream()
                .filter(t -> t.description().equals("GROUP DINNER"))
                .findFirst().orElseThrow();
        if (splitShare != null) {
            transactions.updateSplit(seeded.id(), splitShare, "Split 3 ways");
        }
        return seeded.id();
    }

    @Test
    @DisplayName("category totals count the split share, not the full charge")
    void categoryTotalsUseTheSplitShare() {
        seedSplitTransaction("2026-06-10", "Dining & Coffee", 90.00, 30.00);

        List<CategoryTotal> totals = statsService.computeCategoryTotals(1L, DateRange.ALL);

        assertThat(totals).singleElement().extracting(CategoryTotal::total).isEqualTo(30.00);
    }

    @Test
    @DisplayName("an unsplit transaction still counts its full amount")
    void unsplitTransactionCountsFullAmount() {
        seedSplitTransaction("2026-06-10", "Dining & Coffee", 90.00, null);

        List<CategoryTotal> totals = statsService.computeCategoryTotals(1L, DateRange.ALL);

        assertThat(totals).singleElement().extracting(CategoryTotal::total).isEqualTo(90.00);
    }

    @Test
    @DisplayName("a zero share counts as nothing spent, not the full charge")
    void zeroShareCountsAsNothing() {
        seedSplitTransaction("2026-06-10", "Dining & Coffee", 90.00, 0.00);

        List<CategoryTotal> totals = statsService.computeCategoryTotals(1L, DateRange.ALL);

        assertThat(totals).singleElement().extracting(CategoryTotal::total).isEqualTo(0.00);
    }

    @Test
    @DisplayName("monthly totals count the split share too")
    void monthlyTotalsUseTheSplitShare() {
        seedSplitTransaction("2026-06-10", "Dining & Coffee", 90.00, 30.00);

        List<MonthlyTotal> totals = statsService.computeMonthlyTotals(1L, DateRange.ALL);

        assertThat(totals).singleElement().extracting(MonthlyTotal::total).isEqualTo(30.00);
    }

    @Test
    @DisplayName("the per-category monthly series behind predictions counts the split share")
    void monthlyCategorySeriesUsesTheSplitShare() {
        seedSplitTransaction("2026-06-10", "Dining & Coffee", 90.00, 30.00);

        List<CategoryMonthlySeries> series = statsService.computeMonthlyCategorySeries(1L, DateRange.ALL);

        assertThat(series).singleElement().extracting(CategoryMonthlySeries::overallTotal).isEqualTo(30.00);
    }
}

package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.YearReview;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class YearReviewServiceTest {

    @Autowired
    private YearReviewService service;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private void seed(ParsedTransaction... rows) {
        transactions.insertBatch(List.of(rows), "year-review-test", 1L);
    }

    private static ParsedTransaction income(String date, double amount) {
        return new ParsedTransaction(date, "PAYROLL", amount, "credit", "Income");
    }

    private static ParsedTransaction spend(String date, String description, double amount, String category) {
        return new ParsedTransaction(date, description, amount, "debit", category);
    }

    @Test
    @DisplayName("totals the year's income, spend and savings, month by month, with the biggest month called out")
    void totalsTheYear() {
        seed(
                income("2026-01-01", 3000), spend("2026-01-10", "SUPERMARKET", 1000, "Groceries"),
                income("2026-02-01", 3000), spend("2026-02-10", "SUPERMARKET", 2500, "Groceries"),
                spend("2026-02-20", "AIRLINE", 500, "Travel"),
                income("2026-03-01", 3000), spend("2026-03-10", "SUPERMARKET", 1500, "Groceries"),
                spend("2025-12-31", "SUPERMARKET", 9999, "Groceries"));   // the year before -- not counted

        YearReview review = service.review(1L, 2026);

        assertThat(review.applicable()).isTrue();
        assertThat(review.year()).isEqualTo(2026);
        assertThat(review.currency()).isEqualTo("USD");
        assertThat(review.income()).isEqualTo(9000.0);
        assertThat(review.spend()).isEqualTo(5500.0);
        assertThat(review.saved()).isEqualTo(3500.0);
        assertThat(review.savingsRate()).isEqualTo(38.89);
        assertThat(review.months()).hasSize(12);
        assertThat(review.months().get(1)).isEqualTo(new YearReview.Month("2026-02", 3000.0, 3000.0));
        assertThat(review.months().get(11).spend()).isZero();
        assertThat(review.monthsWithData()).isEqualTo(3);
        assertThat(review.biggestMonth().month()).isEqualTo("2026-02");
        assertThat(review.topCategories()).first().satisfies(c -> {
            assertThat(c.category()).isEqualTo("Groceries");
            assertThat(c.total()).isEqualTo(5000.0);
            assertThat(c.sharePercent()).isEqualTo(90.91);
        });
        assertThat(review.topMerchants()).extracting(MerchantTotal::merchant).containsExactly("SUPERMARKET", "AIRLINE");
    }

    @Test
    @DisplayName("with no year asked for, it shows the newest year that has data, and lists every year available")
    void defaultsToNewestYearWithData() {
        seed(spend("2024-06-01", "SHOP", 10, "Shopping"), spend("2025-06-01", "SHOP", 10, "Shopping"));

        YearReview review = service.review(1L, null);

        assertThat(review.year()).isEqualTo(2025);
        assertThat(review.availableYears()).containsExactly(2024, 2025);
    }

    @Test
    @DisplayName("compares each category with the year before, biggest increase first")
    void comparesWithPreviousYear() {
        seed(
                spend("2025-05-01", "SHOP", 100, "Shopping"), spend("2025-05-02", "AIRLINE", 800, "Travel"),
                spend("2026-05-01", "SHOP", 400, "Shopping"), spend("2026-05-02", "AIRLINE", 200, "Travel"));

        YearReview review = service.review(1L, 2026);

        assertThat(review.previousYear()).isEqualTo(2025);
        assertThat(review.previousYearMonthsWithData()).isEqualTo(1);
        assertThat(review.categoryChanges())
                .extracting(CategoryComparison::category, CategoryComparison::changeAmount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("Shopping", 300.0),
                        org.assertj.core.groups.Tuple.tuple("Travel", -600.0));
    }

    @Test
    @DisplayName("a first year has nothing to compare against")
    void noPreviousYear() {
        seed(spend("2026-05-01", "SHOP", 100, "Shopping"));

        YearReview review = service.review(1L, 2026);

        assertThat(review.previousYear()).isNull();
        assertThat(review.categoryChanges()).isEmpty();
    }

    @Test
    @DisplayName("no income means no savings rate, not a misleading zero or infinity")
    void noIncomeNoSavingsRate() {
        seed(spend("2026-05-01", "SHOP", 100, "Shopping"));

        YearReview review = service.review(1L, 2026);

        assertThat(review.savingsRate()).isNull();
        assertThat(review.saved()).isEqualTo(-100.0);
    }

    @Test
    @DisplayName("with no transactions at all there's no year to review")
    void noTransactions() {
        YearReview review = service.review(1L, null);

        assertThat(review.applicable()).isTrue();
        assertThat(review.year()).isNull();
        assertThat(review.availableYears()).isEmpty();
    }

    @Test
    @DisplayName("all accounts across two currencies isn't summed, but the years are still listed")
    void mixedCurrenciesNotApplicable() {
        accounts.create("Euro account", "checking", "EUR");
        seed(spend("2026-05-01", "SHOP", 100, "Shopping"));

        YearReview review = service.review(null, 2026);

        assertThat(review.applicable()).isFalse();
        assertThat(review.availableYears()).containsExactly(2026);
    }
}

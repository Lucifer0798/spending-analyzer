package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryComparison;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
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
                spend("2026-05-01", "SHOP", 400, "Shopping"), spend("2026-05-02", "AIRLINE", 200, "Travel"),
                income("2026-05-31", 1));   // the record runs to the month's end, so May is whole

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

    @Test
    @DisplayName("a part year is compared with the same months of the year before, categories and totals alike")
    void likeForLike() {
        // 2025: all twelve months. 2026: January to March only.
        for (int m = 1; m <= 12; m++) {
            seed(income("2025-%02d-01".formatted(m), 3000), spend("2025-%02d-10".formatted(m), "SUPERMARKET", 400, "Groceries"));
        }
        seed(spend("2025-06-15", "AIRLINE", 900, "Travel"));   // outside the shared months: not compared
        for (int m = 1; m <= 3; m++) {
            seed(income("2026-%02d-01".formatted(m), 3200), spend("2026-%02d-10".formatted(m), "SUPERMARKET", 500, "Groceries"));
        }

        YearReview review = service.review(1L, 2026);

        YearReview.YearComparison c = review.comparison();
        // 2026's record ends on 10 March, so March is only partly in: January and February are compared.
        assertThat(c.months()).containsExactly(1, 2);
        assertThat(c.likeForLike()).isTrue();
        assertThat(c.spend()).isEqualTo(1000);
        assertThat(c.previousSpend()).isEqualTo(800);   // January-February 2025, not the whole 5,700
        assertThat(c.income()).isEqualTo(6400);
        assertThat(c.previousIncome()).isEqualTo(6000);
        assertThat(c.saved()).isEqualTo(5400);
        assertThat(c.previousSaved()).isEqualTo(5200);
        assertThat(c.savingsRate()).isEqualTo(84.38);
        assertThat(c.previousSavingsRate()).isEqualTo(86.67);
        assertThat(review.categoryChanges()).extracting(CategoryComparison::category).containsExactly("Groceries");
        assertThat(review.categoryChanges().get(0)).isEqualTo(new CategoryComparison("Groceries", 1000, 800, 200, 25.0));
    }

    @Test
    @DisplayName("a month only partly on record isn't compared: ten days of this March aren't set against all of last March")
    void leavesOutPartialMonth() {
        for (int m = 1; m <= 12; m++) {
            seed(spend("2025-%02d-02".formatted(m), "SUPERMARKET", 400, "Groceries"),
                    spend("2025-%02d-28".formatted(m), "LANDLORD", 1000, "Rent/Mortgage"));
        }
        seed(spend("2026-01-10", "SUPERMARKET", 500, "Groceries"), spend("2026-01-28", "LANDLORD", 1000, "Rent/Mortgage"),
                spend("2026-02-10", "SUPERMARKET", 500, "Groceries"), spend("2026-02-28", "LANDLORD", 1000, "Rent/Mortgage"),
                spend("2026-03-10", "SUPERMARKET", 500, "Groceries"));   // the record ends 10 March: rent not in yet

        YearReview review = service.review(1L, 2026);

        assertThat(review.comparison().months()).containsExactly(1, 2);
        assertThat(review.comparison().likeForLike()).isTrue();
        assertThat(review.comparison().spend()).isEqualTo(3000);
        assertThat(review.comparison().previousSpend()).isEqualTo(2800);
        // Rent didn't "drop": March isn't in the comparison at all.
        assertThat(review.categoryChanges()).extracting(CategoryComparison::category, CategoryComparison::changeAmount)
                .containsExactly(org.assertj.core.groups.Tuple.tuple("Groceries", 200.0),
                        org.assertj.core.groups.Tuple.tuple("Rent/Mortgage", 0.0));
    }

    @Test
    @DisplayName("two years with the same months compare whole; with no month in common there's nothing to compare")
    void coverage() {
        for (int m = 1; m <= 3; m++) {
            seed(spend("2025-%02d-01".formatted(m), "SUPERMARKET", 100, "Groceries"),
                    spend("2026-%02d-01".formatted(m), "SUPERMARKET", 150, "Groceries"));
        }
        seed(income("2026-03-31", 1));   // the record runs to March's end
        YearReview.YearComparison same = service.review(1L, 2026).comparison();
        assertThat(same.months()).containsExactly(1, 2, 3);
        assertThat(same.likeForLike()).isFalse();

        transactions.resetAll();
        seed(spend("2025-01-10", "SUPERMARKET", 100, "Groceries"), spend("2026-06-10", "SUPERMARKET", 150, "Groceries"));
        YearReview apart = service.review(1L, 2026);
        assertThat(apart.previousYear()).isEqualTo(2025);
        assertThat(apart.comparison()).isNull();
        assertThat(apart.categoryChanges()).isEmpty();
    }

    @Test
    @DisplayName("the quietest month leaves out one only partly on record, at either end")
    void quietestMonth() {
        seed(spend("2026-01-20", "SUPERMARKET", 50, "Groceries"),    // the record starts on the 20th: partial
                spend("2026-02-10", "SUPERMARKET", 300, "Groceries"),
                spend("2026-03-10", "SUPERMARKET", 200, "Groceries"),
                spend("2026-04-10", "SUPERMARKET", 400, "Groceries"),
                spend("2026-05-03", "SUPERMARKET", 20, "Groceries"));   // ends on 3 May: partial

        assertThat(service.review(1L, 2026).quietestMonth()).isEqualTo(new MonthlyTotal("2026-03", 200));
        assertThat(service.review(1L, 2026).biggestMonth()).isEqualTo(new MonthlyTotal("2026-04", 400));

        // A record that ends on the month's last day covers it.
        seed(spend("2026-05-31", "SUPERMARKET", 1, "Groceries"));
        assertThat(service.review(1L, 2026).quietestMonth()).isEqualTo(new MonthlyTotal("2026-05", 21));
    }
}

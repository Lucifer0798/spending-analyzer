package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.InsightsController;
import com.spendinganalyzer.dto.CategoryDetail;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
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
import static org.assertj.core.groups.Tuple.tuple;

/** One category's drill-down -- built from the same stats as the dashboard bar it's opened from. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CategoryDetailServiceTest {

    @Autowired
    private CategoryDetailService service;

    @Autowired
    private InsightsController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private static ParsedTransaction spend(String date, String description, double amount, String category) {
        return new ParsedTransaction(date, description, amount, "debit", category);
    }

    @BeforeEach
    void seed() {
        transactions.insertBatch(List.of(
                // June: no Dining at all -- it must still appear in the trend as zero.
                spend("2026-06-10", "SUPERMARKET", 300, "Groceries"),
                spend("2026-07-03", "CORNER CAFE #1", 20, "Dining & Coffee"),
                spend("2026-07-18", "CORNER CAFE #2", 30, "Dining & Coffee"),
                spend("2026-07-20", "PIZZA PLACE", 50, "Dining & Coffee"),
                spend("2026-07-21", "SUPERMARKET", 300, "Groceries"),
                spend("2026-08-05", "CORNER CAFE #1", 100, "Dining & Coffee"),
                spend("2026-08-09", "SUPERMARKET", 400, "Groceries"),
                new ParsedTransaction("2026-08-01", "PAYROLL", 3000, "credit", "Income")
        ), "detail-test", 1L);
    }

    @Test
    @DisplayName("totals, share of spend, a zero-filled monthly trend, and the average over those months")
    void totalsAndTrend() {
        CategoryDetail d = service.detail("Dining & Coffee", 1L, DateRange.ALL);

        assertThat(d.applicable()).isTrue();
        assertThat(d.currency()).isEqualTo("USD");
        assertThat(d.total()).isEqualTo(200.0);
        assertThat(d.count()).isEqualTo(4);
        assertThat(d.sharePercent()).isEqualTo(16.67);           // 200 of 1,200 spend
        assertThat(d.months()).extracting(MonthlyTotal::month, MonthlyTotal::total)
                .containsExactly(tuple("2026-06", 0.0), tuple("2026-07", 100.0), tuple("2026-08", 100.0));
        assertThat(d.averagePerMonth()).isEqualTo(66.67);         // 200 over three months, June included
    }

    @Test
    @DisplayName("top merchants are this category's only, branches counted once")
    void merchantsWithinCategory() {
        CategoryDetail d = service.detail("Dining & Coffee", 1L, DateRange.ALL);

        assertThat(d.topMerchants()).extracting(MerchantTotal::merchant, MerchantTotal::total)
                .containsExactly(tuple("CORNER CAFE", 150.0), tuple("PIZZA PLACE", 50.0));
        assertThat(d.merchantCount()).isEqualTo(2);
    }

    @Test
    @DisplayName("change against the previous period when the range has both ends; recent transactions newest first")
    void changeAndRecent() {
        CategoryDetail d = service.detail("Dining & Coffee", 1L, new DateRange("2026-08-01", "2026-08-31"));

        assertThat(d.total()).isEqualTo(100.0);
        assertThat(d.change()).isNotNull();
        assertThat(d.change().previousTotal()).isEqualTo(100.0);   // July 2 - 31, the 31 days before
        assertThat(d.recent()).extracting(Transaction::description).containsExactly("CORNER CAFE #1");

        assertThat(service.detail("Dining & Coffee", 1L, DateRange.ALL).change()).isNull();   // no defined period
    }

    @Test
    @DisplayName("an unknown category is a 404; mixed currencies are not applicable")
    void unknownAndMixed() {
        assertThat(controller.categoryDetail("No Such Thing", 1L, null, null).getStatusCode().value()).isEqualTo(404);

        accounts.create("Euro account", "checking", "EUR");
        CategoryDetail mixed = (CategoryDetail) controller.categoryDetail("Dining & Coffee", null, null, null).getBody();
        assertThat(mixed.applicable()).isFalse();
    }
}

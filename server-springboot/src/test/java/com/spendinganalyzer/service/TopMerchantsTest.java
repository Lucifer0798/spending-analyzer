package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.InsightsController;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.MerchantTotal;
import com.spendinganalyzer.dto.TopMerchantsResponse;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Spend ranked by merchant, with a merchant's branches and order references counted as one place. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class TopMerchantsTest {

    @Autowired
    private StatsService stats;

    @Autowired
    private InsightsController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    private void seed(ParsedTransaction... rows) {
        transactions.insertBatch(List.of(rows), "top-merchants-test", 1L);
    }

    private static ParsedTransaction debit(String date, String description, double amount, String category) {
        return new ParsedTransaction(date, description, amount, "debit", category);
    }

    @Test
    @DisplayName("every branch of a chain counts as one merchant, with count, average, dates and main category")
    void groupsBranchesTogether() {
        seed(
                debit("2026-03-02", "WHOLE FOODS MARKET #123", 60.00, "Groceries"),
                debit("2026-03-20", "WHOLE FOODS MARKET #456", 40.00, "Groceries"),
                debit("2026-04-05", "WHOLE FOODS MARKET #123", 20.00, "Dining & Coffee"),
                debit("2026-03-10", "CORNER CAFE", 200.00, "Dining & Coffee"));

        List<MerchantTotal> totals = stats.computeMerchantTotals(1L, DateRange.ALL, null);

        assertThat(totals).extracting(MerchantTotal::merchant).containsExactly("CORNER CAFE", "WHOLE FOODS MARKET");
        MerchantTotal wholeFoods = totals.get(1);
        assertThat(wholeFoods.total()).isEqualTo(120.00);
        assertThat(wholeFoods.count()).isEqualTo(3);
        assertThat(wholeFoods.averageAmount()).isEqualTo(40.00);
        assertThat(wholeFoods.firstDate()).isEqualTo("2026-03-02");
        assertThat(wholeFoods.lastDate()).isEqualTo("2026-04-05");
        assertThat(wholeFoods.topCategory()).isEqualTo("Groceries");   // $100 of the $120
    }

    @Test
    @DisplayName("counts only spend -- your split share of it -- and nothing outside the date range")
    void onlySpendInRangeAndSplitShare() {
        seed(
                debit("2026-03-02", "GROUP DINNER", 90.00, "Dining & Coffee"),
                debit("2026-01-15", "GROUP DINNER", 500.00, "Dining & Coffee"),      // out of range
                new ParsedTransaction("2026-03-01", "PAYROLL", 3000.00, "credit", "Income"),
                debit("2026-03-05", "TO SAVINGS", 400.00, "Transfer"));
        Transaction dinner = transactions.find(null, null, 1L, new DateRange("2026-03-01", "2026-03-31"), 50, 0)
                .stream().filter(t -> t.description().equals("GROUP DINNER")).findFirst().orElseThrow();
        transactions.updateSplit(dinner.id(), 30.00, "Split 3 ways");

        List<MerchantTotal> totals = stats.computeMerchantTotals(1L, new DateRange("2026-03-01", "2026-03-31"), null);

        assertThat(totals).singleElement().satisfies(m -> {
            assertThat(m.merchant()).isEqualTo("GROUP DINNER");
            assertThat(m.total()).isEqualTo(30.00);
        });
    }

    @Test
    @DisplayName("the endpoint caps the list but still reports how many merchants there were")
    void endpointLimitsButCounts() {
        seed(
                debit("2026-03-01", "A SHOP", 30.00, "Shopping"),
                debit("2026-03-01", "B SHOP", 20.00, "Shopping"),
                debit("2026-03-01", "C SHOP", 10.00, "Shopping"));

        var body = (TopMerchantsResponse) controller.topMerchants(1L, null, null, 2).getBody();

        assertThat(body.applicable()).isTrue();
        assertThat(body.currency()).isEqualTo("USD");
        assertThat(body.merchantCount()).isEqualTo(3);
        assertThat(body.merchants()).extracting(MerchantTotal::merchant).containsExactly("A SHOP", "B SHOP");
    }

    @Test
    @DisplayName("rejects a limit outside 1-100")
    void rejectsBadLimit() {
        ResponseEntity<?> response = controller.topMerchants(1L, null, null, 0);
        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("all accounts across two currencies isn't ranked at all, rather than mixing them")
    void mixedCurrenciesNotApplicable() {
        accounts.create("Euro account", "checking", "EUR");
        seed(debit("2026-03-01", "A SHOP", 30.00, "Shopping"));

        var body = (TopMerchantsResponse) controller.topMerchants(null, null, null, 10).getBody();

        assertThat(body.applicable()).isFalse();
        assertThat(body.merchants()).isEmpty();
    }
}

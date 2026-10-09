package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.InsightsController;
import com.spendinganalyzer.dto.DailyTotal;
import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.groups.Tuple.tuple;

/** Spend per day for the calendar heatmap -- the same spend definition as every other total. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class DailySpendTest {

    @Autowired
    private StatsService stats;

    @Autowired
    private InsightsController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("totals spend by day -- split shares counted, income and transfers left out, only days with spend")
    void totalsByDay() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-05-01", "SUPERMARKET", 40, "debit", "Groceries"),
                new ParsedTransaction("2026-05-01", "CAFE", 5, "debit", "Dining & Coffee"),
                new ParsedTransaction("2026-05-03", "GROUP DINNER", 90, "debit", "Dining & Coffee"),
                new ParsedTransaction("2026-05-02", "PAYROLL", 3000, "credit", "Income"),
                new ParsedTransaction("2026-05-02", "TO SAVINGS", 500, "debit", "Transfer")
        ), "daily-test", 1L);
        Transaction dinner = transactions.find(null, null, 1L, DateRange.ALL, 50, 0).stream()
                .filter(t -> t.description().equals("GROUP DINNER")).findFirst().orElseThrow();
        transactions.updateSplit(dinner.id(), 30.0, "Split 3 ways");

        List<DailyTotal> days = stats.computeDailyTotals(1L, DateRange.ALL, null);

        // May 2 had only income and a transfer, so no row at all.
        assertThat(days).extracting(DailyTotal::date, DailyTotal::total, DailyTotal::count)
                .containsExactly(tuple("2026-05-01", 45.0, 2), tuple("2026-05-03", 30.0, 1));
    }

    @Test
    @DisplayName("respects the date range")
    void respectsRange() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-04-30", "SHOP", 10, "debit", "Shopping"),
                new ParsedTransaction("2026-05-15", "SHOP", 20, "debit", "Shopping")
        ), "daily-test", 1L);

        assertThat(stats.computeDailyTotals(1L, new DateRange("2026-05-01", "2026-05-31"), null))
                .extracting(DailyTotal::date).containsExactly("2026-05-15");
    }

    @Test
    @DisplayName("all accounts across two currencies is not applicable rather than mixing them")
    void mixedCurrencies() {
        accounts.create("Euro account", "checking", "EUR");
        transactions.insertBatch(List.of(new ParsedTransaction("2026-05-15", "SHOP", 20, "debit", "Shopping")), "daily-test", 1L);

        Map<String, Object> body = controller.dailySpend(null, null, null);

        assertThat(body.get("applicable")).isEqualTo(false);
        assertThat((List<?>) body.get("days")).isEmpty();
        assertThat(controller.dailySpend(1L, null, null).get("currency")).isEqualTo("USD");
    }
}

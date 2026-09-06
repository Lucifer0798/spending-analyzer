package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.SummaryResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
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
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * The dashboard-facing side of the account-mismatch fix: "all accounts" refuses to add
 * currencies together, offering a per-currency breakdown instead. {@link
 * com.spendinganalyzer.service.StatsServiceCurrencyTest} proves the underlying detection; this
 * proves the controller actually wires it into what the frontend receives.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class InsightsControllerCurrencyTest {

    @Autowired
    private InsightsController controller;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    private Account seedEuroAccountWithSpend() {
        Account euro = accounts.create("Reisekonto", "checking", "EUR");
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-15", "USD SHOP", 100.00, "debit", "Groceries")), "usd-batch", 1L);
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-16", "EUR SHOP", 40.00, "debit", "Groceries")),
                "eur-batch", euro.id());
        return euro;
    }

    // --- summary ------------------------------------------------------------------

    @Test
    @DisplayName("a single account (or accounts that agree) gets one combined currency")
    void singleCurrencySummaryIsCombined() {
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-15", "SHOP", 100.00, "debit", "Groceries")), "batch", 1L);

        SummaryResponse summary = controller.summary(null, null, null);

        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.perCurrency()).isNull();
        assertThat(summary.categoryTotals()).isNotEmpty();
    }

    @Test
    @DisplayName("\"all accounts\" spanning two currencies splits into a breakdown instead of summing")
    void mixedCurrencySummarySplitsByBreakdown() {
        seedEuroAccountWithSpend();

        SummaryResponse summary = controller.summary(null, null, null);

        assertThat(summary.currency()).isNull();
        assertThat(summary.categoryTotals()).isEmpty();
        assertThat(summary.perCurrency()).extracting("currency").containsExactlyInAnyOrder("USD", "EUR");

        var usd = summary.perCurrency().stream().filter(b -> b.currency().equals("USD")).findFirst().orElseThrow();
        var eur = summary.perCurrency().stream().filter(b -> b.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(usd.categoryTotals()).extracting("total").containsExactly(100.00);
        assertThat(eur.categoryTotals()).extracting("total").containsExactly(40.00);
    }

    @Test
    @DisplayName("a single account is unaffected by other accounts using a different currency")
    void singleAccountUnaffectedByOthersMixing() {
        seedEuroAccountWithSpend();

        SummaryResponse summary = controller.summary(1L, null, null);

        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.perCurrency()).isNull();
    }

    // --- recurring ------------------------------------------------------------------

    @Test
    @DisplayName("recurring asks for one account once currencies mix, rather than guessing a unit")
    void recurringRefusesWhenMixed() {
        seedEuroAccountWithSpend();

        Map<String, Object> response = controller.recurring(null, null, null);

        assertThat(response.get("mixedCurrencies")).isEqualTo(true);
        assertThat(response.get("recurring")).isEqualTo(List.of());
    }

    @Test
    @DisplayName("recurring reports its currency when accounts agree")
    void recurringReportsCurrencyWhenNotMixed() {
        Map<String, Object> response = controller.recurring(null, null, null);

        assertThat(response.get("mixedCurrencies")).isEqualTo(false);
        assertThat(response.get("currency")).isEqualTo("USD");
    }

    // --- predictions refresh -----------------------------------------------------

    @Test
    @DisplayName("refuses to generate a forecast for \"all accounts\" once currencies mix")
    void refreshRefusesWhenMixed() {
        seedEuroAccountWithSpend();

        ResponseEntity<?> response = controller.refreshPredictions(null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }
}

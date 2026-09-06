package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.model.Account;
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

/**
 * The account-mismatch problem multi-currency support exists to avoid: a combined total across
 * accounts in different currencies would silently add unrelated units together. These prove the
 * detection {@link com.spendinganalyzer.controller.InsightsController} and friends rely on to
 * refuse that, and that a currency-scoped query genuinely excludes the other currency's rows.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class StatsServiceCurrencyTest {

    @Autowired
    private StatsService stats;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Test
    @DisplayName("a specific account resolves to that account's own currency")
    void resolvesToTheAccountsOwnCurrency() {
        Account euro = accounts.create("Reisekonto", "checking", "EUR");

        assertThat(stats.resolveCurrency(euro.id())).isEqualTo("EUR");
        // The seeded default account (id 1) is USD.
        assertThat(stats.resolveCurrency(1L)).isEqualTo("USD");
    }

    @Test
    @DisplayName("\"all accounts\" resolves to the shared currency when every account agrees")
    void resolvesToTheSharedCurrency() {
        accounts.create("Savings", "savings", "USD");

        assertThat(stats.resolveCurrency(null)).isEqualTo("USD");
    }

    @Test
    @DisplayName("\"all accounts\" resolves to null once accounts disagree on currency")
    void nullWhenCurrenciesMix() {
        accounts.create("Reisekonto", "checking", "EUR");

        assertThat(stats.resolveCurrency(null)).isNull();
        assertThat(stats.currenciesInUse()).containsExactlyInAnyOrder("USD", "EUR");
    }

    @Test
    @DisplayName("a currency filter excludes every other currency's transactions")
    void currencyFilterExcludesOtherCurrencies() {
        Account euro = accounts.create("Reisekonto", "checking", "EUR");
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-01", "USD SHOP", 100.00, "debit", "Groceries")), "usd-batch", 1L);
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-02", "EUR SHOP", 40.00, "debit", "Groceries")),
                "eur-batch", euro.id());

        var usdOnly = stats.computeCategoryTotals(null, DateRange.ALL, "USD");
        var eurOnly = stats.computeCategoryTotals(null, DateRange.ALL, "EUR");

        assertThat(usdOnly).extracting("total").containsExactly(100.00);
        assertThat(eurOnly).extracting("total").containsExactly(40.00);

        var usdMonthly = stats.computeMonthlyTotals(null, DateRange.ALL, "USD");
        assertThat(usdMonthly).extracting("total").containsExactly(100.00);
    }
}

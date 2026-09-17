package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.AccountBalance;
import com.spendinganalyzer.model.FilterPreset;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.FilterPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountControllerTest {

    @Autowired
    private AccountController controller;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountBalanceRepository accountBalances;

    @Autowired
    private FilterPresetRepository filterPresets;

    private static Map<String, String> body(String... keyValues) {
        Map<String, String> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put(keyValues[i], keyValues[i + 1]);
        return map;
    }

    // --- creating -----------------------------------------------------------

    @Test
    @DisplayName("a new account defaults to USD when no currency is given")
    void defaultsToUsd() {
        ResponseEntity<?> response = controller.create(body("name", "Checking"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Account) response.getBody()).currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("accepts any valid ISO 4217 code, not just the curated dropdown list")
    void acceptsAnyValidIsoCode() {
        // CHF is in the curated list; this only proves the currency itself is honored, not that
        // the list is exhaustive — Currency.getInstance backs the real validation.
        ResponseEntity<?> response = controller.create(body("name", "Swiss account", "currency", "CHF"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Account) response.getBody()).currency()).isEqualTo("CHF");
    }

    @Test
    @DisplayName("normalizes a lowercase currency code")
    void normalizesLowercaseCurrency() {
        ResponseEntity<?> response = controller.create(body("name", "Checking", "currency", "eur"));

        assertThat(((Account) response.getBody()).currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("rejects a currency code that isn't real")
    void rejectsInvalidCurrency() {
        ResponseEntity<?> response = controller.create(body("name", "Checking", "currency", "NOTACODE"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(accounts.nameExists("Checking", null)).isFalse();
    }

    // --- updating -------------------------------------------------------------

    @Test
    @DisplayName("changes an existing account's currency")
    void updatesCurrency() {
        Account created = accounts.create("Checking", "checking", "USD");

        ResponseEntity<?> response = controller.update(created.id(), Map.of("currency", "GBP"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((Account) response.getBody()).currency()).isEqualTo("GBP");
    }

    @Test
    @DisplayName("rejects an invalid currency on update, leaving the account unchanged")
    void rejectsInvalidCurrencyOnUpdate() {
        Account created = accounts.create("Checking", "checking", "USD");

        ResponseEntity<?> response = controller.update(created.id(), Map.of("currency", "NOTACODE"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(accounts.findById(created.id())).get().extracting(Account::currency).isEqualTo("USD");
    }

    // --- listing ----------------------------------------------------------------

    @Test
    @DisplayName("offers a curated currency list alongside the account types")
    void listsCurrencyOptions() {
        Map<String, Object> response = controller.list(false);

        @SuppressWarnings("unchecked")
        var currencies = (java.util.List<String>) response.get("currencies");
        assertThat(currencies).contains("USD", "EUR", "GBP");
    }

    // --- balances (net worth) ----------------------------------------------------

    @Test
    @DisplayName("logs a balance, and reports a missing account as 404")
    void logsBalance() {
        Account created = accounts.create("Checking", "checking", "USD");

        ResponseEntity<?> response = controller.logBalance(created.id(), Map.of("date", "2026-06-01", "balance", 5230.0));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((AccountBalance) response.getBody()).balance()).isEqualTo(5230.0);
        assertThat(controller.logBalance(999_999L, Map.of("date", "2026-06-01", "balance", 1.0))
                .getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("a second balance the same day replaces the first, rather than adding a duplicate")
    void loggingTwiceSameDayReplaces() {
        Account created = accounts.create("Checking", "checking", "USD");

        controller.logBalance(created.id(), Map.of("date", "2026-06-01", "balance", 100.0));
        controller.logBalance(created.id(), Map.of("date", "2026-06-01", "balance", 150.0));

        assertThat(accountBalances.findByAccountId(created.id())).singleElement()
                .extracting("balance").isEqualTo(150.0);
    }

    @Test
    @DisplayName("a balance can be negative, for a credit card's outstanding bill")
    void balanceCanBeNegative() {
        Account created = accounts.create("Credit Card", "credit_card", "USD");

        ResponseEntity<?> response = controller.logBalance(created.id(), Map.of("date", "2026-06-01", "balance", -450.0));

        assertThat(((AccountBalance) response.getBody()).balance()).isEqualTo(-450.0);
    }

    @Test
    @DisplayName("rejects a malformed date and a missing balance")
    void rejectsInvalidBalanceInput() {
        Account created = accounts.create("Checking", "checking", "USD");

        assertThat(controller.logBalance(created.id(), Map.of("date", "not-a-date", "balance", 1.0))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.logBalance(created.id(), Map.of("date", "2026-06-01"))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("lists an account's balance history, and reports a missing account as 404")
    void listsBalances() {
        Account created = accounts.create("Checking", "checking", "USD");
        controller.logBalance(created.id(), Map.of("date", "2026-06-01", "balance", 100.0));

        ResponseEntity<?> response = controller.listBalances(created.id());

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat((java.util.List<?>) response.getBody()).hasSize(1);
        assertThat(controller.listBalances(999_999L).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deletes a balance entry, and reports a missing one as 404")
    void deletesBalance() {
        Account created = accounts.create("Checking", "checking", "USD");
        AccountBalance saved = accountBalances.upsert(created.id(), "2026-06-01", 100.0);

        assertThat(controller.deleteBalance(created.id(), saved.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(accountBalances.findByAccountId(created.id())).isEmpty();
        assertThat(controller.deleteBalance(created.id(), saved.id()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    @DisplayName("deleting an account deletes its logged balances too")
    void deletingAccountDeletesItsBalances() {
        Account created = accounts.create("Old Checking", "checking", "USD");
        accountBalances.upsert(created.id(), "2026-06-01", 100.0);

        controller.delete(created.id());

        assertThat(accountBalances.findByAccountId(created.id())).isEmpty();
    }

    @Test
    @DisplayName("deleting an account clears the reference on any filter preset that used it, rather than leaving it dangling")
    void deletingAccountClearsFilterPresetReferences() {
        Account created = accounts.create("Old Checking", "checking", "USD");
        FilterPreset saved = filterPresets.upsert("Old checking spend", null, null, null, created.id(), null, null);

        controller.delete(created.id());

        FilterPreset reloaded = filterPresets.findById(saved.id()).orElseThrow();
        assertThat(reloaded.accountId()).isNull();
    }
}

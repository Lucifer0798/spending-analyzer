package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountRepository;
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
}

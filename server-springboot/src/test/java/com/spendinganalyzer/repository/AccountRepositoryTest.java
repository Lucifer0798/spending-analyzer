package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Account;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.DisplayName;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountRepositoryTest {

    @Autowired
    private AccountRepository repository;

    @Test
    @DisplayName("a new account defaults to USD unless another currency is given")
    void createsWithGivenCurrency() {
        Account usd = repository.create("Checking", "checking", "USD");
        Account eur = repository.create("Reisekonto", "checking", "EUR");

        assertThat(usd.currency()).isEqualTo("USD");
        assertThat(eur.currency()).isEqualTo("EUR");
    }

    @Test
    @DisplayName("updating just the currency leaves the rest of the account untouched")
    void updateChangesOnlyCurrency() {
        Account created = repository.create("Checking", "checking", "USD");

        repository.update(created.id(), null, null, null, "GBP");

        Account updated = repository.findById(created.id()).orElseThrow();
        assertThat(updated.currency()).isEqualTo("GBP");
        assertThat(updated.name()).isEqualTo("Checking");
        assertThat(updated.type()).isEqualTo("checking");
    }

    @Test
    @DisplayName("one currency across every account reports that single currency")
    void oneCurrencyWhenAllAgree() {
        repository.create("Checking", "checking", "USD");
        repository.create("Savings", "savings", "USD");

        // The seeded default account (id 1) is also USD, so every account here agrees.
        assertThat(repository.distinctCurrencies()).containsExactly("USD");
    }

    @Test
    @DisplayName("more than one currency in use is reported as more than one entry")
    void reportsEveryCurrencyInUse() {
        repository.create("Reisekonto", "checking", "EUR");
        repository.create("Reisekonto 2", "checking", "EUR");

        // The seeded default account (id 1) is USD, so this account set spans two currencies.
        assertThat(repository.distinctCurrencies()).containsExactlyInAnyOrder("USD", "EUR");
    }
}

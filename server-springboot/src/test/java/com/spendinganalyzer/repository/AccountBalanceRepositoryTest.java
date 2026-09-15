package com.spendinganalyzer.repository;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.AccountBalance;
import com.spendinganalyzer.repository.AccountBalanceRepository.BalanceRow;
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
class AccountBalanceRepositoryTest {

    @Autowired
    private AccountBalanceRepository repository;

    @Autowired
    private AccountRepository accounts;

    @Test
    @DisplayName("logs a balance for an account")
    void logsABalance() {
        AccountBalance saved = repository.upsert(Account.DEFAULT_ID, "2026-06-01", 5230.0);

        assertThat(saved.accountId()).isEqualTo(Account.DEFAULT_ID);
        assertThat(saved.balance()).isEqualTo(5230.0);
        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).hasSize(1);
    }

    @Test
    @DisplayName("upserting the same account and date twice replaces rather than duplicates")
    void upsertReplacesRatherThanDuplicates() {
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);
        AccountBalance updated = repository.upsert(Account.DEFAULT_ID, "2026-06-01", 150.0);

        assertThat(updated.balance()).isEqualTo(150.0);
        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).hasSize(1);
    }

    @Test
    @DisplayName("the same account can have different balances on different dates")
    void sameAccountDifferentDates() {
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);
        repository.upsert(Account.DEFAULT_ID, "2026-06-02", 120.0);

        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).hasSize(2);
    }

    @Test
    @DisplayName("findByAccountId is newest first")
    void findByAccountIdIsNewestFirst() {
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);
        repository.upsert(Account.DEFAULT_ID, "2026-06-15", 200.0);

        assertThat(repository.findByAccountId(Account.DEFAULT_ID))
                .extracting("date").containsExactly("2026-06-15", "2026-06-01");
    }

    @Test
    @DisplayName("findAllForActiveAccounts excludes archived accounts")
    void excludesArchivedAccounts() {
        Account archived = accounts.create("Closed Account", "checking", "USD");
        accounts.update(archived.id(), null, null, true, null);
        repository.upsert(archived.id(), "2026-06-01", 999.0);
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);

        List<BalanceRow> rows = repository.findAllForActiveAccounts();

        assertThat(rows).extracting("accountId").containsExactly(Account.DEFAULT_ID);
    }

    @Test
    @DisplayName("findAllForActiveAccounts joins in the account's name and currency")
    void joinsAccountNameAndCurrency() {
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);

        BalanceRow row = repository.findAllForActiveAccounts().get(0);

        assertThat(row.accountName()).isEqualTo("Default");
        assertThat(row.currency()).isEqualTo("USD");
    }

    @Test
    @DisplayName("deletes a balance entry, and reports a missing one as false")
    void deletesBalance() {
        AccountBalance saved = repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);

        assertThat(repository.delete(saved.id())).isTrue();
        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).isEmpty();
        assertThat(repository.delete(saved.id())).isFalse();
    }

    @Test
    @DisplayName("deleteByAccountId removes every balance for that account only")
    void deleteByAccountIdRemovesOnlyThatAccountsBalances() {
        Account other = accounts.create("Savings", "savings", "USD");
        repository.upsert(Account.DEFAULT_ID, "2026-06-01", 100.0);
        repository.upsert(other.id(), "2026-06-01", 200.0);

        repository.deleteByAccountId(Account.DEFAULT_ID);

        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).isEmpty();
        assertThat(repository.findByAccountId(other.id())).hasSize(1);
    }

    @Test
    @DisplayName("restoreAll replaces every balance, ids included")
    void restoreAllReplacesEverything() {
        repository.upsert(Account.DEFAULT_ID, "2026-05-01", 100.0);

        List<AccountBalance> toRestore = List.of(
                new AccountBalance(1, Account.DEFAULT_ID, "2026-01-01", 999.0, "2026-01-01 00:00:00"));

        repository.restoreAll(toRestore);

        assertThat(repository.findByAccountId(Account.DEFAULT_ID)).singleElement()
                .extracting("date", "balance").containsExactly("2026-01-01", 999.0);
    }
}

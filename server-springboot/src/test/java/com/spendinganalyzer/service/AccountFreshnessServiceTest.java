package com.spendinganalyzer.service;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.AccountFreshnessService.AccountFreshness;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class AccountFreshnessServiceTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountBalanceRepository balances;

    @Autowired
    private NamedParameterJdbcTemplate jdbc;

    private AccountFreshnessService service;

    /** Every test runs on 20 October 2026. */
    @BeforeEach
    void fixToday() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 10, 20).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new AccountFreshnessService(accounts, jdbc, clock);
    }

    private AccountFreshness of(String name) {
        return service.freshness().accounts().stream().filter(a -> a.accountName().equals(name)).findFirst().orElseThrow();
    }

    private void add(long accountId, String... dates) {
        for (String date : dates) {
            transactions.insertBatch(List.of(new ParsedTransaction(date, "ROW " + date, 10, "debit", null)), "fresh-test", accountId);
        }
    }

    @Test
    @DisplayName("a statement is due once the newest transaction is more than 35 days old")
    void statementDueAfter35Days() {
        long recent = accounts.create("Recent", "checking", "USD").id();
        long boundary = accounts.create("Boundary", "checking", "USD").id();
        long old = accounts.create("Old", "credit_card", "USD").id();
        add(recent, "2026-09-01", "2026-10-12");
        add(boundary, "2026-09-15");   // exactly 35 days: not yet
        add(old, "2026-08-01", "2026-09-14");

        assertThat(of("Recent").daysSinceTransaction()).isEqualTo(8);
        assertThat(of("Recent").transactionCount()).isEqualTo(2);
        assertThat(of("Recent").statementDue()).isFalse();
        assertThat(of("Boundary").statementDue()).isFalse();
        assertThat(of("Old").latestTransactionDate()).isEqualTo("2026-09-14");
        assertThat(of("Old").statementDue()).isTrue();
    }

    @Test
    @DisplayName("an account with nothing imported isn't nagged about statements; balances are due only where one was logged")
    void nothingImportedIsNotDue() {
        long brokerage = accounts.create("Brokerage", "investment", "USD").id();
        balances.upsert(brokerage, "2026-08-31", 12000);
        accounts.create("Untouched", "savings", "USD");

        AccountFreshness b = of("Brokerage");
        assertThat(b.transactionCount()).isZero();
        assertThat(b.daysSinceTransaction()).isNull();
        assertThat(b.statementDue()).isFalse();
        assertThat(b.daysSinceBalance()).isEqualTo(50);
        assertThat(b.balanceDue()).isTrue();
        assertThat(of("Untouched").balanceDue()).isFalse();
    }

    @Test
    @DisplayName("archived accounts are left out, and a future-dated row counts as today")
    void archivedAndFutureDates() {
        long archived = accounts.create("Closed card", "credit_card", "USD").id();
        add(archived, "2026-01-05");
        accounts.update(archived, null, null, true, null);
        add(Account.DEFAULT_ID, "2026-10-25");

        assertThat(service.freshness().accounts()).extracting(AccountFreshness::accountName).doesNotContain("Closed card");
        assertThat(of("Default").daysSinceTransaction()).isZero();
    }
}

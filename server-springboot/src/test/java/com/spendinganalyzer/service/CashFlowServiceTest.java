package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CashFlowForecast;
import com.spendinganalyzer.dto.CashFlowForecast.AccountForecast;
import com.spendinganalyzer.dto.CashFlowForecast.Event;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CashFlowServiceTest {

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private AccountBalanceRepository balances;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private RecurringDetectionService recurring;

    @Autowired
    private RecurringOverrideRepository overrides;

    private CashFlowService service;

    /** Every test runs on 14 October 2026. */
    @BeforeEach
    void fixToday() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 10, 14).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new CashFlowService(accounts, balances, transactions, recurring, overrides, clock);
    }

    private void add(long accountId, String type, String description, double amount, String... dates) {
        List<ParsedTransaction> rows = new ArrayList<>();
        for (String date : dates) rows.add(new ParsedTransaction(date, description, amount, type, null));
        transactions.insertBatch(rows, "cash-flow-test", accountId);
    }

    private AccountForecast forecastFor(CashFlowForecast forecast, String name) {
        return forecast.accounts().stream().filter(a -> a.accountName().equals(name)).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("starts from the logged balance plus everything imported since, then adds the account's recurring money in and out")
    void projectsFromLoggedBalance() {
        long checking = Account.DEFAULT_ID;
        balances.upsert(checking, "2026-09-30", 1000);
        add(checking, "credit", "ACME CORP PAYROLL", 3000, "2026-07-01", "2026-08-01", "2026-09-01", "2026-10-01");
        add(checking, "debit", "CITY APARTMENTS RENT", 1500, "2026-07-03", "2026-08-03", "2026-09-03", "2026-10-03");
        add(checking, "debit", "NETFLIX", 15.49, "2026-07-12", "2026-08-12", "2026-09-12");
        add(checking, "debit", "CORNER CAFE", 5, "2026-10-05");

        AccountForecast f = forecastFor(service.forecast(60), "Default");

        assertThat(f.asOf()).isEqualTo("2026-10-05");
        assertThat(f.importedSince()).isEqualTo(3);
        assertThat(f.startingBalance()).isEqualTo(2495);   // 1000 + 3000 - 1500 - 5
        assertThat(f.events()).extracting(Event::date, Event::description, Event::amount).containsExactly(
                tuple("2026-10-12", "NETFLIX", -15.49),
                tuple("2026-11-01", "ACME CORP PAYROLL", 3000.0),   // the 1st stays the 1st
                tuple("2026-11-03", "CITY APARTMENTS RENT", -1500.0),
                tuple("2026-11-12", "NETFLIX", -15.49),
                tuple("2026-12-01", "ACME CORP PAYROLL", 3000.0),
                tuple("2026-12-03", "CITY APARTMENTS RENT", -1500.0),
                tuple("2026-12-12", "NETFLIX", -15.49));
        assertThat(f.endingBalance()).isEqualTo(5448.53);
        assertThat(f.events().get(0).balanceAfter()).isEqualTo(2479.51);
        assertThat(f.daily().get(0).date()).isEqualTo("2026-10-05");
        assertThat(f.daily().get(f.daily().size() - 1).date()).isEqualTo("2026-12-13");
        assertThat(f.firstNegativeDate()).isNull();
        assertThat(f.warn()).isFalse();
        assertThat(f.stale()).isFalse();
    }

    @Test
    @DisplayName("a charge one cycle late is counted once straight away; two cycles late it's taken to have stopped")
    void lateAndStoppedCharges() {
        long checking = Account.DEFAULT_ID;
        balances.upsert(checking, "2026-10-05", 500);
        add(checking, "debit", "GYM MEMBERSHIP", 30, "2026-06-20", "2026-07-20", "2026-08-20");   // Sep 20 missed
        add(checking, "debit", "OLD STREAMING", 9.99, "2026-03-01", "2026-04-01", "2026-05-01"); // long gone

        AccountForecast f = forecastFor(service.forecast(60), "Default");

        assertThat(f.events()).extracting(Event::date, Event::description, Event::overdue).containsExactly(
                tuple("2026-10-06", "GYM MEMBERSHIP", true),
                tuple("2026-10-20", "GYM MEMBERSHIP", false),
                tuple("2026-11-20", "GYM MEMBERSHIP", false));
    }

    @Test
    @DisplayName("a late deposit isn't counted on, and an excluded merchant isn't projected")
    void lateIncomeAndExcludedMerchants() {
        long checking = Account.DEFAULT_ID;
        balances.upsert(checking, "2026-10-05", 500);
        add(checking, "credit", "SIDE GIG", 400, "2026-06-15", "2026-07-15", "2026-08-15");   // Sep 15 missed
        add(checking, "debit", "STORAGE UNIT", 80, "2026-07-10", "2026-08-10", "2026-09-10");
        overrides.upsert(MerchantNormalizer.normalize("STORAGE UNIT"), RecurringOverride.ACTION_EXCLUDE);

        AccountForecast f = forecastFor(service.forecast(60), "Default");

        assertThat(f.events()).extracting(Event::date, Event::amount).containsExactly(
                tuple("2026-10-15", 400.0), tuple("2026-11-15", 400.0));
    }

    @Test
    @DisplayName("warns when a savings account heads below zero, but not a credit card; accounts with no balance are listed")
    void warnsOnlyWhereItMatters() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        balances.upsert(savings, "2026-10-02", 300);
        add(savings, "debit", "TRANSFER TO BROKERAGE", 200, "2026-08-02", "2026-09-02", "2026-10-02");

        long card = accounts.create("Card", "credit_card", "USD").id();
        balances.upsert(card, "2026-10-01", -500);

        accounts.create("Cash jar", "cash", "USD");

        CashFlowForecast forecast = service.forecast(60);
        AccountForecast s = forecastFor(forecast, "Savings");
        AccountForecast c = forecastFor(forecast, "Card");

        assertThat(s.firstNegativeDate()).isEqualTo("2026-12-02");
        assertThat(s.lowestBalance()).isEqualTo(-100);
        assertThat(s.warn()).isTrue();
        assertThat(c.firstNegativeDate()).isEqualTo("2026-10-01");
        assertThat(c.warn()).isFalse();
        assertThat(forecast.withoutBalance()).contains("Cash jar", "Default");
    }

    @Test
    @DisplayName("same-day money in is applied before money out, and old data is flagged stale")
    void sameDayOrderAndStaleness() {
        long checking = Account.DEFAULT_ID;
        balances.upsert(checking, "2026-07-20", 100);
        add(checking, "debit", "RENT", 1000, "2026-05-01", "2026-06-01", "2026-07-01");
        add(checking, "credit", "PAYROLL", 1200, "2026-05-01", "2026-06-01", "2026-07-01");

        AccountForecast f = forecastFor(service.forecast(30), "Default");

        assertThat(f.stale()).isTrue();   // newest data is from July
        assertThat(f.events().subList(0, 2)).extracting(Event::description, Event::balanceAfter)
                .containsExactly(tuple("PAYROLL", 1300.0), tuple("RENT", 300.0));
        assertThat(f.firstNegativeDate()).isNull();
    }
}

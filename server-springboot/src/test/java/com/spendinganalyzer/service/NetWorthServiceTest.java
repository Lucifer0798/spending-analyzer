package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.NetWorthResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.AccountRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NetWorthServiceTest {

    @Autowired
    private NetWorthService service;

    @Autowired
    private AccountBalanceRepository balances;

    @Autowired
    private AccountRepository accounts;

    private NetWorthService serviceOn(String isoToday) {
        LocalDate today = LocalDate.parse(isoToday);
        Clock clock = Clock.fixed(today.atStartOfDay(ZoneId.systemDefault()).toInstant(), ZoneId.systemDefault());
        return new NetWorthService(balances, clock);
    }

    @Test
    @DisplayName("with nothing logged, reports zero with an empty history rather than erroring")
    void nothingLoggedYet() {
        NetWorthResponse response = service.compute();

        assertThat(response.total()).isEqualTo(0);
        assertThat(response.accounts()).isEmpty();
        assertThat(response.history()).isEmpty();
        assertThat(response.currency()).isNotNull();
        assertThat(response.perCurrency()).isNull();
        assertThat(response.forecast()).isNull();
    }

    @Test
    @DisplayName("sums the latest balance across accounts that agree on a currency")
    void sumsLatestBalancePerAccount() {
        Account savings = accounts.create("Savings", "savings", "USD");
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        balances.upsert(savings.id(), "2026-06-01", 5000.0);

        NetWorthResponse response = service.compute();

        assertThat(response.currency()).isEqualTo("USD");
        assertThat(response.total()).isEqualTo(6000.0);
        assertThat(response.accounts()).hasSize(2);
        assertThat(response.perCurrency()).isNull();
    }

    @Test
    @DisplayName("a later balance replaces the earlier one in the total, not adds to it")
    void laterBalanceReplacesEarlier() {
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        balances.upsert(Account.DEFAULT_ID, "2026-07-01", 1200.0);

        NetWorthResponse response = service.compute();

        assertThat(response.total()).isEqualTo(1200.0);
        assertThat(response.accounts()).singleElement().extracting("asOfDate").isEqualTo("2026-07-01");
    }

    @Test
    @DisplayName("a negative balance (a credit card's bill) subtracts from the total")
    void negativeBalanceSubtracts() {
        Account creditCard = accounts.create("Credit Card", "credit_card", "USD");
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        balances.upsert(creditCard.id(), "2026-06-01", -300.0);

        NetWorthResponse response = service.compute();

        assertThat(response.total()).isEqualTo(700.0);
    }

    @Test
    @DisplayName("history carries each account's balance forward and sums per distinct date")
    void historyCarriesForwardAndSumsPerDate() {
        Account savings = accounts.create("Savings", "savings", "USD");
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        balances.upsert(savings.id(), "2026-06-10", 500.0);
        balances.upsert(Account.DEFAULT_ID, "2026-07-01", 1100.0);

        NetWorthResponse response = service.compute();

        // 06-01: only Default known => 1000. 06-10: Default carried forward (1000) + Savings (500) => 1500.
        // 07-01: Default updated (1100) + Savings carried forward (500) => 1600.
        assertThat(response.history()).extracting("date", "total").containsExactly(
                org.assertj.core.groups.Tuple.tuple("2026-06-01", 1000.0),
                org.assertj.core.groups.Tuple.tuple("2026-06-10", 1500.0),
                org.assertj.core.groups.Tuple.tuple("2026-07-01", 1600.0)
        );
    }

    @Test
    @DisplayName("an archived account is left out of the total entirely")
    void archivedAccountIsExcluded() {
        Account closed = accounts.create("Old Account", "checking", "USD");
        balances.upsert(closed.id(), "2026-06-01", 5000.0);
        accounts.update(closed.id(), null, null, true, null);
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);

        NetWorthResponse response = service.compute();

        assertThat(response.total()).isEqualTo(1000.0);
        assertThat(response.accounts()).extracting("accountId").containsExactly(Account.DEFAULT_ID);
    }

    @Test
    @DisplayName("accounts that disagree on currency are split into a per-currency breakdown")
    void mixedCurrenciesSplitIntoBreakdown() {
        Account euroAccount = accounts.create("Reisekonto", "checking", "EUR");
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        balances.upsert(euroAccount.id(), "2026-06-01", 500.0);

        NetWorthResponse response = service.compute();

        assertThat(response.currency()).isNull();
        assertThat(response.total()).isEqualTo(0);
        assertThat(response.perCurrency()).extracting("currency").containsExactlyInAnyOrder("USD", "EUR");

        var usd = response.perCurrency().stream().filter(c -> c.currency().equals("USD")).findFirst().orElseThrow();
        var eur = response.perCurrency().stream().filter(c -> c.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(usd.total()).isEqualTo(1000.0);
        assertThat(eur.total()).isEqualTo(500.0);
    }

    // --- forecast ------------------------------------------------------------------

    @Test
    @DisplayName("a single logged balance has no forecast -- one point has no trend to fit")
    void singlePointHasNoForecast() {
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);

        NetWorthResponse response = service.compute();

        assertThat(response.forecast()).isNull();
    }

    @Test
    @DisplayName("an upward trend projects further upward the further out it looks")
    void upwardTrendProjectsFurtherOut() {
        balances.upsert(Account.DEFAULT_ID, "2026-01-01", 1000.0);
        balances.upsert(Account.DEFAULT_ID, "2026-02-01", 1500.0);
        balances.upsert(Account.DEFAULT_ID, "2026-03-01", 2000.0);

        NetWorthResponse response = serviceOn("2026-03-15").compute();

        var forecast = response.forecast();
        assertThat(forecast).isNotNull();
        assertThat(forecast.in1Month()).isGreaterThan(2000.0);
        assertThat(forecast.in3Months()).isGreaterThan(forecast.in1Month());
        assertThat(forecast.in6Months()).isGreaterThan(forecast.in3Months());
        assertThat(forecast.trend()).isEqualTo("increasing");
    }

    @Test
    @DisplayName("a downward trend can project below zero -- unlike spend, net worth isn't clamped at zero")
    void downwardTrendCanGoNegative() {
        balances.upsert(Account.DEFAULT_ID, "2026-01-01", 1000.0);
        balances.upsert(Account.DEFAULT_ID, "2026-02-01", 0.0);
        balances.upsert(Account.DEFAULT_ID, "2026-03-01", -1000.0);

        NetWorthResponse response = serviceOn("2026-03-15").compute();

        var forecast = response.forecast();
        assertThat(forecast.in6Months()).isLessThan(-1000.0);
        assertThat(forecast.trend()).isEqualTo("decreasing");
    }

    @Test
    @DisplayName("a flat balance is a stable trend, not increasing or decreasing")
    void flatBalanceIsStable() {
        balances.upsert(Account.DEFAULT_ID, "2026-01-01", 1000.0);
        balances.upsert(Account.DEFAULT_ID, "2026-02-01", 1000.0);

        NetWorthResponse response = serviceOn("2026-02-15").compute();

        assertThat(response.forecast().trend()).isEqualTo("stable");
    }

    @Test
    @DisplayName("the moving average reflects only the most recent three logged totals")
    void movingAverageUsesLastThree() {
        balances.upsert(Account.DEFAULT_ID, "2026-01-01", 100.0);
        balances.upsert(Account.DEFAULT_ID, "2026-02-01", 200.0);
        balances.upsert(Account.DEFAULT_ID, "2026-03-01", 300.0);
        balances.upsert(Account.DEFAULT_ID, "2026-04-01", 400.0);

        NetWorthResponse response = serviceOn("2026-04-15").compute();

        // Averages the last three (200, 300, 400), not all four.
        assertThat(response.forecast().movingAverage()).isEqualTo(300.0);
    }

    @Test
    @DisplayName("mixed currencies each get their own independent forecast")
    void mixedCurrenciesForecastIndependently() {
        Account euroAccount = accounts.create("Reisekonto", "checking", "EUR");
        balances.upsert(Account.DEFAULT_ID, "2026-01-01", 1000.0);
        balances.upsert(Account.DEFAULT_ID, "2026-02-01", 1200.0);
        balances.upsert(euroAccount.id(), "2026-01-01", 500.0);
        balances.upsert(euroAccount.id(), "2026-02-01", 400.0);

        NetWorthResponse response = serviceOn("2026-02-15").compute();

        var usd = response.perCurrency().stream().filter(c -> c.currency().equals("USD")).findFirst().orElseThrow();
        var eur = response.perCurrency().stream().filter(c -> c.currency().equals("EUR")).findFirst().orElseThrow();
        assertThat(usd.forecast().trend()).isEqualTo("increasing");
        assertThat(eur.forecast().trend()).isEqualTo("decreasing");
    }
}

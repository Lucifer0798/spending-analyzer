package com.spendinganalyzer.service;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.ChallengeRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.MonthlySummaryService.CategoryChange;
import com.spendinganalyzer.service.MonthlySummaryService.Summary;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** "How October went", on 6 November 2026. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MonthlySummaryServiceTest {

    @Autowired private StatsService stats;
    @Autowired private BudgetService budgetService;
    @Autowired private BudgetRepository budgets;
    @Autowired private ChallengeRepository challengeRepository;
    @Autowired private TransactionRepository transactions;
    @Autowired private AccountRepository accounts;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private MonthlySummaryService service;

    @BeforeEach
    void fixToday() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 11, 6).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new MonthlySummaryService(stats, budgetService, budgets,
                new ChallengeService(challengeRepository, stats, clock), clock);
    }

    private void spend(String date, double amount, String category) {
        spend(date, amount, category, Account.DEFAULT_ID);
    }

    private void spend(String date, double amount, String category, long accountId) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, "SHOP " + date + " " + amount, amount, "debit", category)),
                "monthly-test", accountId);
    }

    private Summary october() {
        return service.lastMonth(Account.DEFAULT_ID);
    }

    /** Budgets default to created "now", which isn't when these tests pretend it is. */
    private void budget(String category, double limit, String period, String createdAt) {
        budgets.upsert(category, limit, null, null, null, null, null, period);
        jdbc.update("UPDATE budgets SET created_at = :at WHERE category = :category",
                new MapSqlParameterSource("at", createdAt).addValue("category", category));
    }

    @Test
    @DisplayName("the total against the month before, with the biggest rise and fall by category")
    void comparesWithMonthBefore() {
        spend("2026-09-10", 100, "Groceries");
        spend("2026-09-12", 200, "Shopping");
        spend("2026-09-14", 40, "Travel");       // gone in October: a fall of 40
        spend("2026-10-10", 90, "Groceries");
        spend("2026-10-12", 410, "Shopping");
        spend("2026-10-14", 25, "Healthcare");   // new in October: a rise of 25
        spend("2026-11-02", 999, "Shopping");    // this month doesn't count

        Summary s = october();

        assertThat(s.available()).isTrue();
        assertThat(s.month()).isEqualTo("2026-10");
        assertThat(s.previousMonth()).isEqualTo("2026-09");
        assertThat(s.monthStart()).isEqualTo("2026-10-01");
        assertThat(s.monthEnd()).isEqualTo("2026-10-31");
        assertThat(s.total()).isEqualTo(525);
        assertThat(s.previousTotal()).isEqualTo(340);
        assertThat(s.change()).isEqualTo(185);
        assertThat(s.changePercent()).isEqualTo(54.41);
        assertThat(s.biggestRise()).isEqualTo(new CategoryChange("Shopping", 410, 200, 210));
        assertThat(s.biggestFall()).isEqualTo(new CategoryChange("Travel", 0, 40, -40));
    }

    @Test
    @DisplayName("nothing to say for a month with no spend; no comparison against a month before with none")
    void emptyMonths() {
        spend("2026-09-10", 100, "Groceries");
        assertThat(october().available()).isFalse();

        spend("2026-10-10", 50, "Groceries");
        spend("2026-07-10", 70, "Groceries");   // two months back doesn't count as the month before

        jdbc.update("DELETE FROM transactions WHERE date LIKE '2026-09%'", new MapSqlParameterSource());
        Summary s = october();
        assertThat(s.available()).isTrue();
        assertThat(s.previousTotal()).isNull();
        assertThat(s.change()).isNull();
        assertThat(s.changePercent()).isNull();
        assertThat(s.biggestRise()).isNull();
        assertThat(s.biggestFall()).isNull();
    }

    @Test
    @DisplayName("counts monthly budgets that existed by the month's end, and names the ones overspent")
    void budgets() {
        spend("2026-10-10", 150, "Groceries");
        spend("2026-10-11", 100, "Shopping");
        spend("2026-10-12", 500, "Travel");
        spend("2026-10-13", 50, "Dining & Coffee");

        budget("Groceries", 100, Budget.MONTHLY, "2026-01-01 09:00:00");        // over
        budget("Shopping", 500, Budget.MONTHLY, "2026-10-31 23:00:00");         // kept; set on the last day still counts
        budget("Travel", 100, Budget.MONTHLY, "2026-11-01 09:00:00");           // over, but set after the month ended
        budget("Dining & Coffee", 10, Budget.WEEKLY, "2026-01-01 09:00:00");    // doesn't measure the month

        Summary s = october();
        assertThat(s.budgetsTotal()).isEqualTo(2);
        assertThat(s.budgetsKept()).isEqualTo(1);
        assertThat(s.budgetsOver()).containsExactly("Groceries");
    }

    @Test
    @DisplayName("counts challenges that ended in the month, kept or broken -- not ones ending before or after it")
    void challenges() {
        spend("2026-10-10", 20, "Dining & Coffee");
        challengeRepository.create("Kept", "Travel", 0, "2026-10-05", "2026-10-11", Account.DEFAULT_ID);
        challengeRepository.create("Kept too", "Travel", 0, "2026-09-25", "2026-10-31", Account.DEFAULT_ID);
        challengeRepository.create("Broken", "Dining & Coffee", 0, "2026-10-05", "2026-10-20", Account.DEFAULT_ID);
        challengeRepository.create("September", "Travel", 0, "2026-09-01", "2026-09-30", Account.DEFAULT_ID);
        challengeRepository.create("Still going", "Travel", 0, "2026-10-20", "2026-11-20", Account.DEFAULT_ID);

        Summary s = october();
        assertThat(s.challengesKept()).isEqualTo(2);
        assertThat(s.challengesBroken()).isEqualTo(1);
    }

    @Test
    @DisplayName("says when the month may not be fully imported, and when the month before may read low")
    void flagsThinData() {
        spend("2026-09-12", 100, "Groceries");
        spend("2026-10-27", 100, "Groceries");
        Summary thin = october();
        assertThat(thin.newestTransaction()).isEqualTo("2026-10-27");
        assertThat(thin.dataStarts()).isEqualTo("2026-09-12");

        // Spend after the month means it was imported past its end; spend from early in the month
        // before means that one is covered.
        spend("2026-11-01", 5, "Groceries");
        spend("2026-09-07", 5, "Groceries");
        Summary covered = october();
        assertThat(covered.newestTransaction()).isNull();
        assertThat(covered.dataStarts()).isNull();
    }

    @Test
    @DisplayName("follows the account it's asked about, and all accounts when in one currency")
    void followsAccount() {
        long other = accounts.create("Card", "credit_card", accounts.findById(Account.DEFAULT_ID).orElseThrow().currency()).id();
        spend("2026-10-10", 100, "Groceries");
        spend("2026-10-11", 30, "Groceries", other);

        assertThat(service.lastMonth(Account.DEFAULT_ID).total()).isEqualTo(100);
        assertThat(service.lastMonth(other).total()).isEqualTo(30);
    }

    @Test
    @DisplayName("not available across accounts in different currencies")
    void mixedCurrencies() {
        String home = accounts.findById(Account.DEFAULT_ID).orElseThrow().currency();
        accounts.create("Abroad", "checking", "USD".equals(home) ? "EUR" : "USD");
        spend("2026-10-10", 100, "Groceries");

        assertThat(service.lastMonth(null).available()).isFalse();
        assertThat(service.lastMonth(Account.DEFAULT_ID).available()).isTrue();
    }
}

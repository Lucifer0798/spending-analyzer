package com.spendinganalyzer.service;

import com.spendinganalyzer.controller.CategoryController;
import com.spendinganalyzer.controller.ChallengeController;
import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.Challenge;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.ChallengeRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChallengeService.Day;
import com.spendinganalyzer.service.ChallengeService.Progress;
import com.spendinganalyzer.service.ChallengeService.Status;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Spending challenges: how one is going, worked out from transactions, and what creating or deleting one records. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ChallengeServiceTest {

    @Autowired private ChallengeRepository challenges;
    @Autowired private StatsService stats;
    @Autowired private TransactionRepository transactions;
    @Autowired private ChallengeController api;
    @Autowired private CategoryController categoriesApi;
    @Autowired private ChangeLogService changeLog;
    @Autowired private BackupService backups;
    @Autowired private NamedParameterJdbcTemplate jdbc;

    private ChallengeService service;

    /** Every test runs on 14 October 2026. */
    @BeforeEach
    void fixToday() {
        Clock clock = Clock.fixed(LocalDate.of(2026, 10, 14).atStartOfDay().toInstant(ZoneOffset.UTC), ZoneOffset.UTC);
        service = new ChallengeService(challenges, stats, clock);
    }

    private void spend(String date, String description, double amount, String category) {
        transactions.insertBatch(List.of(new ParsedTransaction(date, description, amount, "debit", category)),
                "challenge-test", Account.DEFAULT_ID);
    }

    private Progress challenge(String category, double limit, String start, String end) {
        return service.progress(challenges.create("test", category, limit, start, end, Account.DEFAULT_ID));
    }

    @Test
    @DisplayName("a no-spend challenge kept so far: nothing spent, a streak of every day so far, later days still to come")
    void noSpendKept() {
        spend("2026-10-05", "CORNER CAFE", 4.5, "Dining & Coffee");   // before it started
        spend("2026-10-10", "BOOK SHOP", 20, "Shopping");             // another category

        Progress p = challenge("Dining & Coffee", 0, "2026-10-08", "2026-10-21");

        assertThat(p.status()).isEqualTo(Status.ACTIVE);
        assertThat(p.spent()).isZero();
        assertThat(p.daysTotal()).isEqualTo(14);
        assertThat(p.daysElapsed()).isEqualTo(7);
        assertThat(p.streak()).isEqualTo(7);
        assertThat(p.onPace()).isNull();
        assertThat(p.days()).hasSize(14);
        assertThat(p.days().get(6)).isEqualTo(new Day("2026-10-14", 0.0));
        assertThat(p.days().get(7)).isEqualTo(new Day("2026-10-15", null));
    }

    @Test
    @DisplayName("any spend breaks a no-spend challenge; the streak counts the clear days since")
    void noSpendBroken() {
        spend("2026-10-11", "CORNER CAFE", 4.5, "Dining & Coffee");

        Progress p = challenge("Dining & Coffee", 0, "2026-10-08", "2026-10-21");

        assertThat(p.status()).isEqualTo(Status.BROKEN);
        assertThat(p.spent()).isEqualTo(4.5);
        assertThat(p.streak()).isEqualTo(3);
        assertThat(p.days().get(3)).isEqualTo(new Day("2026-10-11", 4.5));
    }

    @Test
    @DisplayName("a cap: what's left, and whether spend so far is within the limit's share of the days so far")
    void capOnPace() {
        spend("2026-10-02", "DEPARTMENT STORE", 60, "Shopping");
        spend("2026-10-10", "BOOK SHOP", 50, "Shopping");

        Progress p = challenge("Shopping", 200, "2026-10-01", "2026-10-31");

        assertThat(p.status()).isEqualTo(Status.ACTIVE);
        assertThat(p.spent()).isEqualTo(110);
        assertThat(p.remaining()).isEqualTo(90);
        assertThat(p.onPace()).isFalse();   // 14 of 31 days allows 90.32
        assertThat(challenge("Shopping", 300, "2026-10-01", "2026-10-31").onPace()).isTrue();
        assertThat(challenge("Shopping", 100, "2026-10-01", "2026-10-31").status()).isEqualTo(Status.BROKEN);
    }

    @Test
    @DisplayName("finished and kept is completed; not started yet is upcoming with nothing counted")
    void completedAndUpcoming() {
        Progress done = challenge("Dining & Coffee", 0, "2026-09-01", "2026-09-07");
        assertThat(done.status()).isEqualTo(Status.COMPLETED);
        assertThat(done.daysElapsed()).isEqualTo(7);
        assertThat(done.streak()).isEqualTo(7);

        Progress later = challenge("Dining & Coffee", 0, "2026-10-20", "2026-10-27");
        assertThat(later.status()).isEqualTo(Status.UPCOMING);
        assertThat(later.daysElapsed()).isZero();
        assertThat(later.saved()).isNull();
        assertThat(later.days()).extracting(Day::spent).containsOnlyNulls();
    }

    @Test
    @DisplayName("saved so far is the usual daily spend over the 90 days before, times the days so far, minus what was spent")
    void savedAgainstHabit() {
        assertThat(challenge("Dining & Coffee", 0, "2026-10-08", "2026-10-21").saved()).isNull();   // nothing to compare

        spend("2026-08-01", "CORNER CAFE", 100, "Dining & Coffee");
        spend("2026-09-15", "CORNER CAFE", 80, "Dining & Coffee");   // 180 over 90 days: 2 a day
        assertThat(challenge("Dining & Coffee", 0, "2026-10-08", "2026-10-21").saved()).isEqualTo(14);

        spend("2026-10-09", "CORNER CAFE", 5, "Dining & Coffee");
        assertThat(challenge("Dining & Coffee", 0, "2026-10-08", "2026-10-21").saved()).isEqualTo(9);
    }

    @Test
    @DisplayName("all spending counts every spending category but not income or transfers, and a split counts your share")
    void allSpending() {
        spend("2026-10-09", "CORNER CAFE", 10, "Dining & Coffee");
        spend("2026-10-10", "MOVE TO SAVINGS", 500, "Transfer");
        transactions.insertBatch(List.of(new ParsedTransaction("2026-10-10", "ACME PAYROLL", 2000, "credit", "Income")),
                "challenge-test", Account.DEFAULT_ID);
        spend("2026-10-11", "DINNER FOR FOUR", 120, "Dining & Coffee");
        long dinner = jdbc.queryForObject("SELECT MAX(id) FROM transactions", new MapSqlParameterSource(), Long.class);
        transactions.updateSplit(dinner, 30.0, "four ways");

        assertThat(challenge(null, 100, "2026-10-08", "2026-10-21").spent()).isEqualTo(40);
    }

    @Test
    @DisplayName("creating checks its input and names it if no name is given; deleting it -- and creating it -- can be undone")
    void createDeleteAndHistory() {
        Map<String, Object> body = new HashMap<>(Map.of("category", "Dining & Coffee", "limitAmount", 0,
                "startDate", "2026-10-08", "endDate", "2026-10-21", "accountId", Account.DEFAULT_ID));
        var response = api.create(body);
        var created = (Progress) response.getBody();
        assertThat(created.challenge().name()).isEqualTo("No spending on Dining & Coffee");
        assertThat(changeLog.recent(1).get(0).summary()).isEqualTo("Started the challenge \"No spending on Dining & Coffee\"");
        assertThat(response.getHeaders().getFirst("X-Change-Id")).isEqualTo(String.valueOf(changeLog.recent(1).get(0).id()));

        long id = created.challenge().id();
        api.delete(id);
        assertThat(changeLog.recent(1).get(0).summary()).isEqualTo("Deleted the challenge \"No spending on Dining & Coffee\"");
        changeLog.undo(changeLog.recent(1).get(0).id());
        assertThat(challenges.findById(id)).isPresent();

        long createEntry = changeLog.recent(2).get(1).id();
        changeLog.undo(createEntry);
        assertThat(challenges.findById(id)).isEmpty();

        assertThat(ChallengeController.defaultName(null, 0)).isEqualTo("No spending at all");
        assertThat(ChallengeController.defaultName("Shopping", 200)).isEqualTo("Under 200 on Shopping");
        assertThat(ChallengeController.defaultName(null, 49.5)).isEqualTo("Under 49.50 in total");
    }

    @Test
    @DisplayName("rejects backwards or overlong dates, a negative limit, and a category that isn't spending")
    void validates() {
        Map<String, Object> ok = Map.of("category", "Shopping", "limitAmount", 50, "startDate", "2026-10-01", "endDate", "2026-10-07");
        assertThat(api.create(with(ok, "endDate", "2026-09-30")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(with(ok, "endDate", "2027-10-07")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(with(ok, "startDate", "October")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(with(ok, "limitAmount", -1)).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(with(ok, "category", "Nope")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(with(ok, "category", "Income")).getStatusCode().value()).isEqualTo(400);
        assertThat(api.create(ok).getStatusCode().value()).isEqualTo(200);
    }

    @Test
    @DisplayName("renaming a category carries its challenges along; deleting it removes them, and undo brings them back")
    void followsCategory() {
        Category pets = (Category) categoriesApi.create(Map.of("name", "Pet care")).getBody();
        Challenge c = challenges.create("Vet-free month", "Pet care", 0, "2026-10-01", "2026-10-31", null);

        categoriesApi.update(pets.id(), Map.of("name", "Animals"));
        assertThat(challenges.findById(c.id()).orElseThrow().category()).isEqualTo("Animals");

        categoriesApi.delete(pets.id());
        assertThat(challenges.findById(c.id())).isEmpty();
        changeLog.undo(changeLog.recent(1).get(0).id());
        assertThat(challenges.findById(c.id())).get().extracting(Challenge::category).isEqualTo("Animals");
    }

    @Test
    @DisplayName("challenges are backed up; a file from before them restores none")
    void backup() {
        challenges.create("Under 200 on Shopping", "Shopping", 200, "2026-10-01", "2026-10-31", null);
        BackupData exported = backups.export();
        backups.restore(exported);
        assertThat(challenges.findAll()).extracting(Challenge::name).containsExactly("Under 200 on Shopping");

        backups.restore(new BackupData(exported.version(), exported.exportedAt(), exported.accounts(), exported.categories(),
                exported.transactions(), exported.merchantCategories(), exported.budgets(), exported.recurringOverrides(),
                exported.goals(), exported.goalContributions(), exported.tags(), exported.transactionTags(),
                exported.accountBalances(), exported.filterPresets(), exported.transactionReceipts(),
                exported.netWorthTarget(), exported.keywordRules(), exported.transferPairs(),
                exported.duplicateDismissals(), exported.merchantNames(), exported.dismissedMerchantSuggestions(), null));
        assertThat(challenges.findAll()).isEmpty();
    }

    private static Map<String, Object> with(Map<String, Object> base, String key, Object value) {
        Map<String, Object> copy = new HashMap<>(base);
        copy.put(key, value);
        return copy;
    }
}

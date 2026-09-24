package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.BudgetService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class BudgetControllerTest {

    @Autowired
    private BudgetController controller;

    @Autowired
    private BudgetRepository budgets;

    @Autowired
    private CategoryRepository categories;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @BeforeEach
    void seed() {
        // June: 400 on groceries. May: 100, so a month filter has something to exclude.
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-05-04", "OLD MONTH SHOP", 100.00, "debit", "Groceries"),
                new ParsedTransaction("2026-06-03", "SUPERMARKET A", 250.00, "debit", "Groceries"),
                new ParsedTransaction("2026-06-17", "SUPERMARKET B", 150.00, "debit", "Groceries"),
                // Income must not count against a budget; it is excluded by the category flags.
                new ParsedTransaction("2026-06-25", "PAYDAY", 3000.00, "credit", "Income")
        ), "budget-test-batch", 1L);
    }

    private static Map<String, Object> body(String category, Object limit) {
        Map<String, Object> map = new HashMap<>();
        map.put("category", category);
        map.put("monthly_limit", limit);
        return map;
    }

    private static Map<String, Object> bodyWithEscalation(
            String category, Object limit, Object type, Object value, Object frequencyMonths, Object startMonth
    ) {
        Map<String, Object> map = body(category, limit);
        map.put("escalation_type", type);
        map.put("escalation_value", value);
        map.put("escalation_frequency_months", frequencyMonths);
        map.put("escalation_start_month", startMonth);
        return map;
    }

    private static Map<String, Object> bodyWithRollover(String category, Object limit, boolean rollover) {
        Map<String, Object> map = body(category, limit);
        map.put("rollover", rollover);
        return map;
    }

    private static Map<String, Object> bodyWithPeriod(String category, Object limit, String period) {
        Map<String, Object> map = body(category, limit);
        map.put("period", period);
        return map;
    }

    private BudgetProgress progressFor(String category, String month) {
        return controller.list(null, month).budgets().stream()
                .filter(b -> b.category().equals(category))
                .findFirst().orElseThrow();
    }

    // --- setting a budget -------------------------------------------------------

    @Test
    @DisplayName("stores a target for a category")
    void setsABudget() {
        ResponseEntity<?> response = controller.set(body("Groceries", 500));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::monthlyLimit).isEqualTo(500.0);
    }

    @Test
    @DisplayName("setting the same category twice replaces the target instead of duplicating it")
    void upsertsRatherThanDuplicating() {
        controller.set(body("Groceries", 500));
        controller.set(body("Groceries", 650));

        assertThat(budgets.findAll().stream().filter(b -> b.category().equals("Groceries"))).hasSize(1);
        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::monthlyLimit).isEqualTo(650.0);
    }

    @Test
    @DisplayName("rejects a category that does not exist")
    void rejectsUnknownCategory() {
        ResponseEntity<?> response = controller.set(body("Not A Category", 100));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(budgets.findByCategory("Not A Category")).isEmpty();
    }

    @Test
    @DisplayName("rejects a zero or negative target, which no percentage could describe")
    void rejectsNonPositiveLimit() {
        assertThat(controller.set(body("Groceries", 0)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.set(body("Groceries", -50)).getStatusCode().value()).isEqualTo(400);
        assertThat(budgets.findByCategory("Groceries")).isEmpty();
    }

    @Test
    @DisplayName("rejects a missing target")
    void rejectsMissingLimit() {
        assertThat(controller.set(body("Groceries", null)).getStatusCode().value()).isEqualTo(400);
    }

    // --- progress ---------------------------------------------------------------

    @Test
    @DisplayName("counts only the month asked for")
    void countsOnlyTheRequestedMonth() {
        controller.set(body("Groceries", 500));

        assertThat(progressFor("Groceries", "2026-06").spent()).isEqualTo(400.0);
        assertThat(progressFor("Groceries", "2026-05").spent()).isEqualTo(100.0);
    }

    @Test
    @DisplayName("defaults to the newest month on record, not the current calendar month")
    void defaultsToNewestMonthWithData() {
        controller.set(body("Groceries", 500));

        BudgetService.BudgetSummary summary = controller.list(null, null);

        assertThat(summary.month()).isEqualTo("2026-06");
        assertThat(summary.budgets().get(0).spent()).isEqualTo(400.0);
    }

    @Test
    @DisplayName("reports how much is left, and how much of the target is used")
    void reportsRemainingAndPercent() {
        controller.set(body("Groceries", 500));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.remaining()).isEqualTo(100.0);
        assertThat(p.percentUsed()).isEqualTo(80.0);
    }

    @Test
    @DisplayName("goes negative once the target is passed rather than clamping at zero")
    void reportsOverspendAsNegativeRemaining() {
        controller.set(body("Groceries", 250));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.remaining()).isEqualTo(-150.0);
        assertThat(p.percentUsed()).isEqualTo(160.0);
        assertThat(p.status()).isEqualTo("over");
    }

    @Test
    @DisplayName("labels comfortable, close and blown budgets differently")
    void classifiesStatus() {
        controller.set(body("Groceries", 1000));                 // 40% used
        assertThat(progressFor("Groceries", "2026-06").status()).isEqualTo("under");

        controller.set(body("Groceries", 500));                  // exactly 80%
        assertThat(progressFor("Groceries", "2026-06").status()).isEqualTo("near");

        controller.set(body("Groceries", 400));                  // exactly on target, not over
        assertThat(progressFor("Groceries", "2026-06").status()).isEqualTo("near");

        controller.set(body("Groceries", 399));                  // a hair over
        assertThat(progressFor("Groceries", "2026-06").status()).isEqualTo("over");
    }

    @Test
    @DisplayName("a budgeted category with no spending that month reports zero, not an absent row")
    void includesUntouchedBudgets() {
        controller.set(body("Travel", 300));

        BudgetProgress p = progressFor("Travel", "2026-06");
        assertThat(p.spent()).isEqualTo(0.0);
        assertThat(p.remaining()).isEqualTo(300.0);
        assertThat(p.status()).isEqualTo("under");
    }

    @Test
    @DisplayName("income does not count against a budget")
    void ignoresIncome() {
        controller.set(body("Income", 100));

        assertThat(progressFor("Income", "2026-06").spent()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("totals the targets and the spend across every budget")
    void totalsAcrossBudgets() {
        controller.set(body("Groceries", 500));
        controller.set(body("Travel", 300));

        BudgetService.BudgetSummary summary = controller.list(null, "2026-06");
        assertThat(summary.totalLimit()).isEqualTo(800.0);
        assertThat(summary.totalSpent()).isEqualTo(400.0);
        assertThat(summary.currency()).isEqualTo("USD");
        assertThat(summary.mixedCurrencies()).isFalse();
    }

    @Test
    @DisplayName("shows nothing for \"all accounts\" once they use different currencies, rather than mixing them")
    void hidesProgressWhenCurrenciesMix() {
        controller.set(body("Groceries", 500));
        var euroAccount = accounts.create("Euro account", "checking", "EUR");
        transactions.insertBatch(
                List.of(new ParsedTransaction("2026-06-10", "EURO SHOP", 50.00, "debit", "Groceries")),
                "budget-test-eur-batch", euroAccount.id());

        BudgetService.BudgetSummary summary = controller.list(null, "2026-06");
        assertThat(summary.budgets()).isEmpty();
        assertThat(summary.currency()).isNull();
        assertThat(summary.mixedCurrencies()).isTrue();

        // A single account is unaffected — the mismatch only exists across "all accounts".
        assertThat(controller.list(1L, "2026-06").mixedCurrencies()).isFalse();
    }

    @Test
    @DisplayName("rejects a malformed month rather than guessing")
    void rejectsMalformedMonth() {
        assertThat(catchThrowable(() -> controller.list(null, "June 2026")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // --- escalation schedule -----------------------------------------------------

    @Test
    @DisplayName("a fixed escalation adds a flat amount per elapsed period, on top of the base")
    void appliesFixedEscalation() {
        // 2025-12 to 2026-06 is 6 months; a 3-month frequency means 2 periods have elapsed.
        controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 3, "2025-12"));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.baseLimit()).isEqualTo(400.0);
        assertThat(p.monthlyLimit()).isEqualTo(500.0); // 400 + 50*2
    }

    @Test
    @DisplayName("a percent escalation compounds per elapsed period, not a flat multiple")
    void appliesPercentEscalation() {
        controller.set(bodyWithEscalation("Groceries", 400, "percent", 10, 3, "2025-12"));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.baseLimit()).isEqualTo(400.0);
        assertThat(p.monthlyLimit()).isCloseTo(484.0, org.assertj.core.data.Offset.offset(0.01)); // 400 * 1.1^2
    }

    @Test
    @DisplayName("measuring before the schedule starts reports the base target, unchanged")
    void ignoresEscalationBeforeItStarts() {
        controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 3, "2026-07"));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.monthlyLimit()).isEqualTo(400.0);
    }

    @Test
    @DisplayName("omitting escalation on a later save clears whatever schedule was there")
    void savingWithoutEscalationClearsExistingSchedule() {
        controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 3, "2025-12"));
        controller.set(body("Groceries", 400));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.monthlyLimit()).isEqualTo(400.0);
        assertThat(p.escalationType()).isNull();
    }

    @Test
    @DisplayName("an unspecified start month defaults to the current calendar month, not the latest imported one")
    void escalationStartDefaultsToNow() {
        controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 1, null));

        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::escalationStartMonth)
                .isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    @DisplayName("rejects an unknown escalation type")
    void rejectsUnknownEscalationType() {
        ResponseEntity<?> response =
                controller.set(bodyWithEscalation("Groceries", 400, "doubling", 50, 3, "2025-12"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an escalation type with no value or frequency to go with it")
    void rejectsIncompleteEscalation() {
        assertThat(controller.set(bodyWithEscalation("Groceries", 400, "fixed", null, 3, null))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, null, null))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a zero or negative escalation value or frequency")
    void rejectsNonPositiveEscalationFields() {
        assertThat(controller.set(bodyWithEscalation("Groceries", 400, "fixed", 0, 3, null))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 0, null))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a malformed escalation start month")
    void rejectsMalformedEscalationStartMonth() {
        ResponseEntity<?> response =
                controller.set(bodyWithEscalation("Groceries", 400, "fixed", 50, 3, "not-a-month"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    // --- rollover ------------------------------------------------------------------

    @Test
    @DisplayName("underspending a month carries the leftover into the next month's limit")
    void rolloverCarriesLeftoverForward() {
        budgets.upsert("Groceries", 500.0, null, null, null, null, "2026-05");

        // May: 500 budgeted, 100 spent (from the shared seed) -- 400 left over into June.
        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.rolloverCarryIn()).isEqualTo(400.0);
        assertThat(p.monthlyLimit()).isEqualTo(900.0); // 500 base + 400 carried in
    }

    @Test
    @DisplayName("overspending a month eats into the next month's limit")
    void rolloverLetsOverspendEatIntoNextMonth() {
        budgets.upsert("Groceries", 50.0, null, null, null, null, "2026-05");

        // May: 50 budgeted, 100 spent -- 50 over, which comes straight out of June's limit.
        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.rolloverCarryIn()).isEqualTo(-50.0);
        assertThat(p.monthlyLimit()).isEqualTo(0.0); // 50 base - 50 carried-in deficit
    }

    @Test
    @DisplayName("measuring the rollover start month itself carries in nothing yet")
    void startMonthItselfCarriesInNothing() {
        budgets.upsert("Groceries", 500.0, null, null, null, null, "2026-06");

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.rolloverCarryIn()).isZero();
        assertThat(p.monthlyLimit()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("rollover accumulates additively across more than one prior month")
    void rolloverAccumulatesAcrossMultipleMonths() {
        transactions.insertBatch(List.of(
                new ParsedTransaction("2026-04-10", "APRIL SHOP", 50.00, "debit", "Groceries")
        ), "rollover-multi-month-batch", 1L);
        budgets.upsert("Groceries", 500.0, null, null, null, null, "2026-04");

        // April: 500 - 50 = 450 leftover. May: 500 - 100 = 400 leftover. Both carry into June.
        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.rolloverCarryIn()).isEqualTo(850.0);
        assertThat(p.monthlyLimit()).isEqualTo(1350.0);
    }

    @Test
    @DisplayName("rollover and escalation compose: each historical month carries its own escalated leftover")
    void rolloverComposesWithEscalation() {
        // Escalation: +50 every 3 months from 2025-12. Rollover: starts 2026-05.
        budgets.upsert("Groceries", 400.0, "fixed", 50.0, 3, "2025-12", "2026-05");

        // May (5 months after 2025-12, 1 period elapsed): escalated limit 450, spent 100 -> 350 leftover.
        // June's own escalated limit (2 periods elapsed): 500, plus May's 350 carried in.
        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.monthlyLimit()).isEqualTo(850.0);
    }

    @Test
    @DisplayName("omitting rollover on a later save disables it and drops any carry-in")
    void disablingRolloverStopsCarryingIn() {
        budgets.upsert("Groceries", 500.0, null, null, null, null, "2026-05");
        controller.set(body("Groceries", 500));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.rolloverStartMonth()).isNull();
        assertThat(p.rolloverCarryIn()).isZero();
        assertThat(p.monthlyLimit()).isEqualTo(500.0);
    }

    @Test
    @DisplayName("an unspecified start defaults to the current calendar month, not the latest imported one")
    void rolloverStartDefaultsToNow() {
        controller.set(bodyWithRollover("Groceries", 400, true));

        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::rolloverStartMonth)
                .isEqualTo(java.time.YearMonth.now().toString());
    }

    @Test
    @DisplayName("re-enabling an already-enabled rollover preserves its original start month")
    void reenablingRolloverPreservesOriginalStartMonth() {
        budgets.upsert("Groceries", 500.0, null, null, null, null, "2025-01");

        // A resave that changes the limit but still asks for rollover must not reset progress.
        controller.set(bodyWithRollover("Groceries", 600, true));

        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::rolloverStartMonth)
                .isEqualTo("2025-01");
    }

    // --- period --------------------------------------------------------------------

    @Test
    @DisplayName("a budget with no period specified defaults to monthly")
    void defaultsToMonthlyPeriod() {
        controller.set(body("Groceries", 500));

        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::period).isEqualTo("monthly");
    }

    @Test
    @DisplayName("rejects an unknown period")
    void rejectsUnknownPeriod() {
        ResponseEntity<?> response = controller.set(bodyWithPeriod("Groceries", 500, "biweekly"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(budgets.findByCategory("Groceries")).isEmpty();
    }

    @Test
    @DisplayName("rejects an escalation schedule on a non-monthly budget")
    void rejectsEscalationOnNonMonthlyBudget() {
        Map<String, Object> body = bodyWithEscalation("Groceries", 400, "fixed", 50, 3, "2025-12");
        body.put("period", "weekly");

        assertThat(controller.set(body).getStatusCode().value()).isEqualTo(400);
        assertThat(budgets.findByCategory("Groceries")).isEmpty();
    }

    @Test
    @DisplayName("rejects rollover on a non-monthly budget")
    void rejectsRolloverOnNonMonthlyBudget() {
        Map<String, Object> body = bodyWithRollover("Groceries", 400, true);
        body.put("period", "quarterly");

        assertThat(controller.set(body).getStatusCode().value()).isEqualTo(400);
        assertThat(budgets.findByCategory("Groceries")).isEmpty();
    }

    @Test
    @DisplayName("omitting period on a later save resets it to monthly")
    void omittingPeriodOnResaveResetsToMonthly() {
        controller.set(bodyWithPeriod("Groceries", 400, "weekly"));

        controller.set(body("Groceries", 400));

        assertThat(budgets.findByCategory("Groceries")).get()
                .extracting(Budget::period).isEqualTo("monthly");
    }

    @Test
    @DisplayName("a weekly budget measures only the week containing the anchor month's last day")
    void weeklyBudgetMeasuresOnlyItsOwnWeek() {
        java.time.LocalDate lastDayOfJune = java.time.YearMonth.parse("2026-06").atEndOfMonth();
        java.time.LocalDate monday = lastDayOfJune.minusDays(lastDayOfJune.getDayOfWeek().getValue() - 1L);
        java.time.LocalDate sunday = monday.plusDays(6);

        transactions.insertBatch(List.of(
                new ParsedTransaction(monday.plusDays(3).toString(), "MIDWEEK SHOP", 40.00, "debit", "Groceries"),
                new ParsedTransaction(monday.minusDays(1).toString(), "PREVIOUS WEEK SHOP", 999.00, "debit", "Groceries")
        ), "weekly-budget-batch", 1L);

        controller.set(bodyWithPeriod("Groceries", 100, "weekly"));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.spent()).isEqualTo(40.0);
        assertThat(p.period()).isEqualTo("weekly");
        assertThat(p.periodStart()).isEqualTo(monday.toString());
        assertThat(p.periodEnd()).isEqualTo(sunday.toString());
    }

    @Test
    @DisplayName("a quarterly budget measures the whole calendar quarter containing the anchor month")
    void quarterlyBudgetMeasuresWholeQuarter() {
        transactions.insertBatch(List.of(
                // Q2 2026 (Apr-Jun) -- included alongside the shared seed's May and June spend.
                new ParsedTransaction("2026-04-15", "APRIL SHOP", 60.00, "debit", "Groceries"),
                // Q1 2026 -- excluded, a different quarter entirely.
                new ParsedTransaction("2026-03-01", "MARCH SHOP", 999.00, "debit", "Groceries")
        ), "quarterly-budget-batch", 1L);

        controller.set(bodyWithPeriod("Groceries", 100, "quarterly"));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.spent()).isEqualTo(560.0); // 100 (May) + 400 (June) + 60 (April)
        assertThat(p.period()).isEqualTo("quarterly");
        assertThat(p.periodStart()).isEqualTo("2026-04-01");
        assertThat(p.periodEnd()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("a monthly budget's period range is exactly the measured month")
    void monthlyBudgetPeriodRangeIsTheMonth() {
        controller.set(body("Groceries", 500));

        BudgetProgress p = progressFor("Groceries", "2026-06");
        assertThat(p.period()).isEqualTo("monthly");
        assertThat(p.periodStart()).isEqualTo("2026-06-01");
        assertThat(p.periodEnd()).isEqualTo("2026-06-30");
    }

    @Test
    @DisplayName("a non-monthly budget's own target doesn't distort the combined monthly total")
    void nonMonthlyBudgetsExcludedFromTotals() {
        controller.set(body("Groceries", 500));
        controller.set(bodyWithPeriod("Travel", 50, "weekly"));

        BudgetService.BudgetSummary summary = controller.list(null, "2026-06");
        assertThat(summary.totalLimit()).isEqualTo(500.0);
        assertThat(summary.totalSpent()).isEqualTo(400.0);
        // The weekly budget still gets its own row -- just left out of the shared total.
        assertThat(summary.budgets()).extracting(BudgetProgress::category).contains("Travel");
    }

    // --- deleting ---------------------------------------------------------------

    @Test
    @DisplayName("deletes a budget, and reports a missing one as 404")
    void deletesBudget() {
        controller.set(body("Groceries", 500));
        long id = budgets.findByCategory("Groceries").orElseThrow().id();

        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(200);
        assertThat(budgets.findByCategory("Groceries")).isEmpty();
        assertThat(controller.delete(id).getStatusCode().value()).isEqualTo(404);
    }

    // --- staying in step with categories ----------------------------------------

    @Test
    @DisplayName("a renamed category keeps its budget")
    void budgetFollowsCategoryRename() {
        Category custom = categories.create("Hobbies", false, false);
        controller.set(body("Hobbies", 120));

        categories.rename(custom.id(), "Hobbies", "Hobbies & Crafts");

        assertThat(budgets.findByCategory("Hobbies")).isEmpty();
        assertThat(budgets.findByCategory("Hobbies & Crafts")).get()
                .extracting(Budget::monthlyLimit).isEqualTo(120.0);
    }

    @Test
    @DisplayName("deleting a category drops its budget rather than folding it into another")
    void budgetGoesWithDeletedCategory() {
        Category custom = categories.create("Hobbies", false, false);
        controller.set(body("Hobbies", 120));
        controller.set(body("Other", 50));

        categories.deleteAndReassign(custom.id(), "Hobbies", "Other");

        assertThat(budgets.findByCategory("Hobbies")).isEmpty();
        // The category transactions were folded into keeps the target its own user set.
        assertThat(budgets.findByCategory("Other")).get()
                .extracting(Budget::monthlyLimit).isEqualTo(50.0);
    }
}

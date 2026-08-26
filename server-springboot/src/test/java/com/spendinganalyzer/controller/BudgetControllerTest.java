package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.BudgetProgress;
import com.spendinganalyzer.model.Budget;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.model.ParsedTransaction;
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
    }

    @Test
    @DisplayName("rejects a malformed month rather than guessing")
    void rejectsMalformedMonth() {
        assertThat(catchThrowable(() -> controller.list(null, "June 2026")))
                .isInstanceOf(IllegalArgumentException.class);
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

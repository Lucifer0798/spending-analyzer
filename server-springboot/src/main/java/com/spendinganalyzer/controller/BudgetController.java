package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.repository.BudgetRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.service.BudgetService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class BudgetController {

    private final BudgetRepository budgets;
    private final BudgetService budgetService;
    private final CategoryRepository categories;

    public BudgetController(
            BudgetRepository budgets,
            BudgetService budgetService,
            CategoryRepository categories
    ) {
        this.budgets = budgets;
        this.budgetService = budgetService;
        this.categories = categories;
    }

    /**
     * Every budget with its spend for a month. Omit {@code month} to get the newest month on
     * record, which is what the dashboard shows.
     */
    @GetMapping("/budgets")
    public BudgetService.BudgetSummary list(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String month
    ) {
        return budgetService.progress(accountId, month);
    }

    /**
     * Sets a category's monthly target, replacing any existing one. An upsert rather than
     * separate create and update calls: "budget Groceries at 500" is a single intent, and the
     * caller should not have to discover whether a budget already exists to express it.
     */
    @PostMapping("/budgets")
    public ResponseEntity<?> set(@RequestBody Map<String, Object> body) {
        String category = body.get("category") instanceof String s ? s.trim() : "";
        Double limit = body.get("monthly_limit") instanceof Number n ? n.doubleValue() : null;

        if (category.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("category is required."));
        }
        if (!categories.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categories.findAllNames())));
        }
        if (limit == null) {
            return ResponseEntity.badRequest().body(new ErrorResponse("monthly_limit is required."));
        }
        // A zero or negative target is not a budget, and would make percent-used either
        // meaningless or a division by zero.
        if (limit <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("monthly_limit must be greater than zero."));
        }

        return ResponseEntity.ok(budgets.upsert(category, limit));
    }

    @DeleteMapping("/budgets/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!budgets.deleteById(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Budget not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.service.ChangeLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class CategoryController {

    private static final String FALLBACK_CATEGORY = "Other";

    private final CategoryRepository categoryRepository;
    private final ChangeLogService changeLog;

    public CategoryController(CategoryRepository categoryRepository, ChangeLogService changeLog) {
        this.categoryRepository = categoryRepository;
        this.changeLog = changeLog;
    }

    /**
     * Everything a rename or delete of {@code name} cascades into, read before it happens, so one
     * History entry can put it all back -- the category itself, its transactions, memory, budget,
     * keyword rules, and the categories transfer pairs would restore.
     */
    private ChangeLogService.Watch[] watchCategory(long id, String name) {
        Map<String, Object> p = Map.of("id", id, "name", name);
        return new ChangeLogService.Watch[] {
                changeLog.watch("categories", "id = :id", p),
                changeLog.watch("transactions", List.of("category"), "category = :name", p),
                changeLog.watch("merchant_categories", "category = :name", p),
                changeLog.watch("budgets", "category = :name", p),
                changeLog.watch("keyword_rules", "category = :name", p),
                changeLog.watch("transfer_pairs", List.of("debit_prev_category", "credit_prev_category"),
                        "debit_prev_category = :name OR credit_prev_category = :name", p),
        };
    }

    public record CategoryWithCount(
            long id, String name, boolean is_builtin, boolean is_income,
            boolean is_transfer, int sort_order, String group_name, String color, int transactionCount
    ) {}

    /** "#" plus exactly six hex digits -- what an {@code <input type="color">} always sends. */
    private static final java.util.regex.Pattern HEX_COLOR = java.util.regex.Pattern.compile("^#[0-9a-fA-F]{6}$");

    /**
     * Returns both a plain name list (what the transaction pickers bind to) and the
     * detailed rows with flags and usage counts (what the management screen needs).
     */
    @GetMapping("/categories")
    public Map<String, Object> list() {
        List<Category> all = categoryRepository.findAll();
        List<CategoryWithCount> detailed = all.stream()
                .map(c -> new CategoryWithCount(c.id(), c.name(), c.isBuiltin(), c.isIncome(),
                        c.isTransfer(), c.sortOrder(), c.groupName(), c.color(),
                        categoryRepository.transactionCount(c.name())))
                .toList();
        return Map.of(
                "categories", all.stream().map(Category::name).toList(),
                "detailed", detailed
        );
    }

    @PostMapping("/categories")
    @Transactional
    public ResponseEntity<?> create(@RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s.trim() : "";
        boolean isIncome = body.get("is_income") instanceof Boolean b && b;
        boolean isTransfer = body.get("is_transfer") instanceof Boolean b && b;

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }
        if (isIncome && isTransfer) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("A category cannot be both income and transfer."));
        }
        if (categoryRepository.nameExists(name, null)) {
            return ResponseEntity.status(409).body(new ErrorResponse("A category named '" + name + "' already exists."));
        }

        var watch = changeLog.watch("categories", "name = :name", Map.of("name", name));
        Category created = categoryRepository.create(name, isIncome, isTransfer);
        changeLog.recordSettings("category", "Added the category \"" + name + "\"", watch);
        return ResponseEntity.ok(created);
    }

    /**
     * Every check runs before anything is written, so a rejected request changes nothing (a
     * rename used to land before a bad color was refused) and the History entry is the whole edit.
     */
    @PatchMapping("/categories/{id}")
    @Transactional
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        var existing = categoryRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Category not found."));
        }
        Category category = existing.get();

        String newName = body.get("name") instanceof String s && !s.isBlank() ? s.trim() : null;
        Boolean isIncome = body.get("is_income") instanceof Boolean b ? b : null;
        Boolean isTransfer = body.get("is_transfer") instanceof Boolean b ? b : null;
        // containsKey, not just a non-null check: the key's presence is what says "touch the
        // group", so a rename- or flag-only PATCH that never mentions it leaves it alone, while
        // an explicit null (or blank) clears it -- the same "whole field, not a merge" contract
        // an upsert gives everywhere else in this app. Color follows the same convention.
        boolean touchGroup = body.containsKey("group_name");
        String groupName = body.get("group_name") instanceof String s && !s.isBlank() ? s.trim() : null;
        boolean touchColor = body.containsKey("color");
        String color = body.get("color") instanceof String s && !s.isBlank() ? s.trim() : null;

        if (newName != null) {
            if (category.isBuiltin()) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Built-in categories cannot be renamed."));
            }
            if (categoryRepository.nameExists(newName, id)) {
                return ResponseEntity.status(409)
                        .body(new ErrorResponse("A category named '" + newName + "' already exists."));
            }
        }
        if (isIncome != null || isTransfer != null) {
            boolean resolvedIncome = isIncome != null ? isIncome : category.isIncome();
            boolean resolvedTransfer = isTransfer != null ? isTransfer : category.isTransfer();
            if (resolvedIncome && resolvedTransfer) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("A category cannot be both income and transfer."));
            }
        }
        if (touchColor && color != null && !HEX_COLOR.matcher(color).matches()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("color must be a hex code like #4f46e5, got: " + color));
        }

        var watches = newName != null
                ? watchCategory(id, category.name())
                : new ChangeLogService.Watch[] { changeLog.watch("categories", "id = :id", Map.of("id", id)) };
        if (newName != null) categoryRepository.rename(id, category.name(), newName);
        if (isIncome != null || isTransfer != null) categoryRepository.updateFlags(id, isIncome, isTransfer);
        if (touchGroup) categoryRepository.updateGroup(id, groupName);
        if (touchColor) categoryRepository.updateColor(id, color);

        List<String> what = new ArrayList<>();
        if (isIncome != null || isTransfer != null) what.add("income/transfer setting");
        if (touchGroup) what.add("group");
        if (touchColor) what.add("color");
        String summary = newName != null
                ? "Renamed the category \"" + category.name() + "\" to \"" + newName + "\""
                        + (what.isEmpty() ? "" : " and changed its " + String.join(", ", what))
                : "Changed the " + String.join(", ", what) + " of the category \"" + category.name() + "\"";
        changeLog.recordSettings("category", summary, watches);

        return ResponseEntity.ok(categoryRepository.findById(id).orElseThrow());
    }

    /**
     * Only user-created categories can be deleted; their transactions fall back to
     * "Other" so nothing is left pointing at a category that no longer exists.
     */
    @DeleteMapping("/categories/{id}")
    @Transactional
    public ResponseEntity<?> delete(@PathVariable long id) {
        var existing = categoryRepository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Category not found."));
        }
        Category category = existing.get();
        if (category.isBuiltin()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Built-in categories cannot be deleted."));
        }

        int reassigned = categoryRepository.transactionCount(category.name());
        var watches = watchCategory(id, category.name());
        categoryRepository.deleteAndReassign(id, category.name(), FALLBACK_CATEGORY);
        changeLog.recordSettings("category", "Deleted the category \"" + category.name() + "\""
                + (reassigned == 0 ? "" : " (" + reassigned + " transaction" + (reassigned == 1 ? "" : "s")
                        + " moved to " + FALLBACK_CATEGORY + ")"), watches);

        return ResponseEntity.ok(Map.of(
                "ok", true,
                "transactionsReassignedTo", FALLBACK_CATEGORY,
                "transactionsReassigned", reassigned
        ));
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.FilterPreset;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.FilterPresetRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

/**
 * Saved combinations of the Transactions page's filters — category, tag, search, account, and
 * date range — so a view like "Business trips" can be reapplied in one click instead of being
 * rebuilt by hand every time.
 */
@RestController
@RequestMapping("/api/filter-presets")
public class FilterPresetController {

    private final FilterPresetRepository presets;
    private final CategoryRepository categories;
    private final AccountRepository accounts;

    public FilterPresetController(
            FilterPresetRepository presets, CategoryRepository categories, AccountRepository accounts) {
        this.presets = presets;
        this.categories = categories;
        this.accounts = accounts;
    }

    @GetMapping
    public List<FilterPreset> list() {
        return presets.findAll();
    }

    @PostMapping
    public ResponseEntity<?> save(@RequestBody Map<String, Object> body) {
        String name = body.get("name") instanceof String s ? s.trim() : "";
        String category = asTrimmedString(body.get("category"));
        String tag = asTrimmedString(body.get("tag"));
        String search = asTrimmedString(body.get("search"));
        Long accountId = body.get("account_id") instanceof Number n ? n.longValue() : null;
        String dateFrom = asTrimmedString(body.get("date_from"));
        String dateTo = asTrimmedString(body.get("date_to"));

        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }
        if (category != null && !categories.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categories.findAllNames())));
        }
        if (accountId != null && accounts.findById(accountId).isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("account_id does not refer to an existing account."));
        }
        if (dateFrom != null && !isValidDate(dateFrom)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date_from must be in YYYY-MM-DD form."));
        }
        if (dateTo != null && !isValidDate(dateTo)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("date_to must be in YYYY-MM-DD form."));
        }

        return ResponseEntity.ok(presets.upsert(name, category, tag, search, accountId, dateFrom, dateTo));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!presets.delete(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Preset not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static String asTrimmedString(Object value) {
        return value instanceof String s && !s.isBlank() ? s.trim() : null;
    }

    private static boolean isValidDate(String s) {
        try {
            LocalDate.parse(s);
            return true;
        } catch (DateTimeParseException e) {
            return false;
        }
    }
}

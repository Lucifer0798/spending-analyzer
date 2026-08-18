package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.TransactionsListResponse;
import com.spendinganalyzer.model.Categories;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository repository;

    public TransactionController(TransactionRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/transactions")
    public TransactionsListResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String month,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        var transactions = repository.find(category, month, limit, offset);
        int total = repository.count(category, month);
        return new TransactionsListResponse(transactions, total);
    }

    @PatchMapping("/transactions/{id}")
    public ResponseEntity<?> updateCategory(@PathVariable long id, @RequestBody Map<String, String> body) {
        String category = body.get("category");
        if (category == null || !Categories.ALL.contains(category)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("category must be one of: " + String.join(", ", Categories.ALL)));
        }

        boolean updated = repository.updateCategory(id, category, "user");
        if (!updated) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @GetMapping("/categories")
    public Map<String, Object> categories() {
        return Map.of("categories", Categories.ALL);
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        repository.resetAll();
        return Map.of("ok", true);
    }
}

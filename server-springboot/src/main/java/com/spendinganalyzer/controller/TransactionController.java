package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.TransactionsListResponse;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;

    public TransactionController(TransactionRepository repository, CategoryRepository categoryRepository) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
    }

    @GetMapping("/transactions")
    public TransactionsListResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        var transactions = repository.find(category, month, accountId, limit, offset);
        int total = repository.count(category, month, accountId);
        return new TransactionsListResponse(transactions, total);
    }

    @PatchMapping("/transactions/{id}")
    public ResponseEntity<?> updateCategory(@PathVariable long id, @RequestBody Map<String, String> body) {
        String category = body.get("category");
        if (category == null || !categoryRepository.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categoryRepository.findAllNames())));
        }

        if (!repository.updateCategory(id, category, "user")) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        repository.resetAll();
        return Map.of("ok", true);
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.TransactionsListResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.MerchantNormalizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final MerchantCategoryRepository merchantCategoryRepository;

    public TransactionController(
            TransactionRepository repository,
            CategoryRepository categoryRepository,
            MerchantCategoryRepository merchantCategoryRepository
    ) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.merchantCategoryRepository = merchantCategoryRepository;
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

    /**
     * Recategorizing a transaction also teaches merchant memory. The correction is what
     * makes the fix durable — without it the same merchant would be re-guessed on the next
     * import and the user would have to correct it again.
     */
    @PatchMapping("/transactions/{id}")
    public ResponseEntity<?> updateCategory(@PathVariable long id, @RequestBody Map<String, String> body) {
        String category = body.get("category");
        if (category == null || !categoryRepository.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categoryRepository.findAllNames())));
        }

        var existing = repository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }

        repository.updateCategory(id, category, "user");

        String merchantKey = MerchantNormalizer.normalize(existing.get().description());
        merchantCategoryRepository.remember(merchantKey, category, MerchantCategory.SOURCE_USER);

        return ResponseEntity.ok(Map.of("ok", true, "learnedMerchant", merchantKey));
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        repository.resetAll();
        return Map.of("ok", true);
    }
}

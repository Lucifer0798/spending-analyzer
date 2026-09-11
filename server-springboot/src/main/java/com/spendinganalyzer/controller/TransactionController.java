package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.TransactionWithTags;
import com.spendinganalyzer.dto.TransactionsListResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.MerchantNormalizer;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class TransactionController {

    private static final List<String> TYPES = List.of("debit", "credit");

    private final TransactionRepository repository;
    private final CategoryRepository categoryRepository;
    private final MerchantCategoryRepository merchantCategoryRepository;
    private final TagRepository tagRepository;

    public TransactionController(
            TransactionRepository repository,
            CategoryRepository categoryRepository,
            MerchantCategoryRepository merchantCategoryRepository,
            TagRepository tagRepository
    ) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.merchantCategoryRepository = merchantCategoryRepository;
        this.tagRepository = tagRepository;
    }

    @GetMapping("/transactions")
    public TransactionsListResponse list(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to,
            @RequestParam(required = false) String tag,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        DateRange range = DateRange.of(from, to);
        var transactions = repository.find(category, month, accountId, range, tag, limit, offset);
        int total = repository.count(category, month, accountId, range, tag);
        return new TransactionsListResponse(attachTags(transactions), total);
    }

    private List<TransactionWithTags> attachTags(List<Transaction> transactions) {
        Map<Long, List<String>> tagsById = tagRepository.namesByTransactionId(
                transactions.stream().map(Transaction::id).toList());
        return transactions.stream()
                .map(t -> new TransactionWithTags(t, tagsById.getOrDefault(t.id(), List.of())))
                .toList();
    }

    /** Tags a transaction, creating the tag first if this is the first time it's been used. */
    @PostMapping("/transactions/{id}/tags")
    public ResponseEntity<?> addTag(@PathVariable long id, @RequestBody Map<String, String> body) {
        if (repository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }
        String name = body.get("name") == null ? "" : body.get("name").trim();
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }

        tagRepository.addTag(id, name);
        return ResponseEntity.ok(Map.of("ok", true, "tags", tagRepository.namesFor(id)));
    }

    /** Untags a transaction; the tag itself remains for whatever other transactions carry it. */
    @DeleteMapping("/transactions/{id}/tags/{name}")
    public ResponseEntity<?> removeTag(@PathVariable long id, @PathVariable String name) {
        if (repository.findById(id).isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }

        tagRepository.removeTag(id, name);
        return ResponseEntity.ok(Map.of("ok", true, "tags", tagRepository.namesFor(id)));
    }

    /**
     * Edits a transaction. Every field is optional, so a caller can change just the amount
     * or just the category.
     *
     * <p>Changing the category also teaches merchant memory — that correction is what makes
     * the fix durable, otherwise the same merchant is re-guessed on the next import.
     */
    @PatchMapping("/transactions/{id}")
    public ResponseEntity<?> update(@PathVariable long id, @RequestBody Map<String, Object> body) {
        var existing = repository.findById(id);
        if (existing.isEmpty()) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }

        String category = asTrimmedString(body.get("category"));
        String date = asTrimmedString(body.get("date"));
        String description = asTrimmedString(body.get("description"));
        String type = asTrimmedString(body.get("type"));
        Double amount = body.get("amount") instanceof Number n ? n.doubleValue() : null;

        if (category != null && !categoryRepository.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categoryRepository.findAllNames())));
        }
        if (date != null) {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("date must be in YYYY-MM-DD form, got: " + date));
            }
        }
        if (description != null && description.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("description cannot be empty."));
        }
        // Amounts are stored unsigned, with direction carried by type; a negative or zero
        // amount would silently corrupt every total that sums this column.
        if (amount != null && amount <= 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("amount must be greater than zero. Use type to set direction."));
        }
        if (type != null && !TYPES.contains(type)) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("type must be one of: " + String.join(", ", TYPES)));
        }

        boolean changedAnything = false;

        if (date != null || description != null || amount != null || type != null) {
            changedAnything = repository.updateFields(id, date, description, amount, type);
        }

        String learnedMerchant = null;
        if (category != null) {
            repository.updateCategory(id, category, "user");
            // Use the new description when one was supplied, so memory keys on what the
            // transaction now says rather than what it used to.
            String source = description != null ? description : existing.get().description();
            learnedMerchant = MerchantNormalizer.normalize(source);
            merchantCategoryRepository.remember(learnedMerchant, category, MerchantCategory.SOURCE_USER);
            changedAnything = true;
        }

        if (!changedAnything) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Nothing to update. Supply at least one of: category, date, description, amount, type."));
        }

        Transaction updated = repository.findById(id).orElseThrow();
        return ResponseEntity.ok(Map.of(
                "ok", true,
                "transaction", updated,
                "learnedMerchant", learnedMerchant == null ? "" : learnedMerchant
        ));
    }

    @DeleteMapping("/transactions/{id}")
    public ResponseEntity<?> delete(@PathVariable long id) {
        if (!repository.deleteById(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Transaction not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/reset")
    public Map<String, Object> reset() {
        repository.resetAll();
        return Map.of("ok", true);
    }

    private static String asTrimmedString(Object value) {
        return value instanceof String s ? s.trim() : null;
    }
}

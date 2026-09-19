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
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

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
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "200") int limit,
            @RequestParam(defaultValue = "0") int offset
    ) {
        DateRange range = DateRange.of(from, to);
        var transactions = repository.find(category, month, accountId, range, tag, search, limit, offset);
        int total = repository.count(category, month, accountId, range, tag, search);
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
     *
     * <p>{@code split_share}/{@code split_note} work like a category rename in one respect and
     * differently in another: like every other field here, omitting them entirely leaves an
     * existing split untouched. But {@code split_share} is what actually sets or clears the
     * split — sending it as {@code null} clears both columns together, since a note with no
     * share is meaningless — while {@code split_note} alone can only update the note on a split
     * that already exists.
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

        if (body.containsKey("split_share")) {
            Object rawShare = body.get("split_share");
            if (rawShare == null) {
                repository.updateSplit(id, null, null);
            } else if (rawShare instanceof Number n) {
                double share = n.doubleValue();
                double effectiveAmount = amount != null ? amount : existing.get().amount();
                if (share < 0 || share > effectiveAmount) {
                    return ResponseEntity.badRequest().body(new ErrorResponse(
                            "split_share must be between 0 and the transaction amount (" + effectiveAmount + ")."));
                }
                String note = body.containsKey("split_note")
                        ? asTrimmedString(body.get("split_note"))
                        : existing.get().splitNote();
                repository.updateSplit(id, share, (note == null || note.isEmpty()) ? null : note);
            } else {
                return ResponseEntity.badRequest().body(new ErrorResponse("split_share must be a number or null."));
            }
            changedAnything = true;
        } else if (body.containsKey("split_note")) {
            if (existing.get().splitShare() == null) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Set split_share before adding a note."));
            }
            String note = asTrimmedString(body.get("split_note"));
            repository.updateSplit(id, existing.get().splitShare(), (note == null || note.isEmpty()) ? null : note);
            changedAnything = true;
        }

        if (!changedAnything) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Nothing to update. Supply at least one of: category, date, description, amount, type, split_share, split_note."));
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

    // --- bulk actions -------------------------------------------------------------

    /**
     * Categorizes several transactions in one request, teaching merchant memory for each one's
     * own description — the same durability a single-transaction category edit gets. Every
     * distinct merchant among the selection is only taught once, even if several selected rows
     * share it.
     */
    @PatchMapping("/transactions/bulk-category")
    public ResponseEntity<?> bulkCategory(@RequestBody Map<String, Object> body) {
        List<Long> ids = asIdList(body.get("ids"));
        String category = asTrimmedString(body.get("category"));

        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("ids is required and must not be empty."));
        }
        if (category == null || category.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("category is required."));
        }
        if (!categoryRepository.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categoryRepository.findAllNames())));
        }

        List<Transaction> targets = repository.findByIds(ids);
        int updated = repository.updateCategoryBulk(ids, category, MerchantCategory.SOURCE_USER);

        Set<String> taughtMerchants = new LinkedHashSet<>();
        for (Transaction t : targets) {
            String merchantKey = MerchantNormalizer.normalize(t.description());
            if (taughtMerchants.add(merchantKey)) {
                merchantCategoryRepository.remember(merchantKey, category, MerchantCategory.SOURCE_USER);
            }
        }

        return ResponseEntity.ok(Map.of("ok", true, "updated", updated));
    }

    /** Tags several transactions at once, creating the tag first if this is the first time it's been used. */
    @PostMapping("/transactions/bulk-tags")
    public ResponseEntity<?> bulkAddTag(@RequestBody Map<String, Object> body) {
        List<Long> ids = asIdList(body.get("ids"));
        String name = asTrimmedString(body.get("name"));

        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("ids is required and must not be empty."));
        }
        if (name == null || name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required."));
        }

        tagRepository.addTagBulk(ids, name);
        return ResponseEntity.ok(Map.of("ok", true, "tagged", ids.size()));
    }

    /**
     * Deletes several transactions at once. A {@code POST} rather than a {@code DELETE}, since
     * the set of ids to remove has to travel as a body and a body on a {@code DELETE} is
     * needlessly contentious for no benefit here.
     */
    @PostMapping("/transactions/bulk-delete")
    public ResponseEntity<?> bulkDelete(@RequestBody Map<String, Object> body) {
        List<Long> ids = asIdList(body.get("ids"));
        if (ids.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("ids is required and must not be empty."));
        }

        int deleted = repository.deleteBulk(ids);
        return ResponseEntity.ok(Map.of("ok", true, "deleted", deleted));
    }

    private static List<Long> asIdList(Object value) {
        List<Long> ids = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Number n) ids.add(n.longValue());
            }
        }
        return ids;
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

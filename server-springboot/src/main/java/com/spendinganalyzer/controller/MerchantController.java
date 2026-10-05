package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.CategoryRepository;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import com.spendinganalyzer.service.MerchantImportService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MerchantController {

    private final MerchantCategoryRepository repository;
    private final CategoryRepository categoryRepository;
    private final MerchantImportService importService;

    public MerchantController(
            MerchantCategoryRepository repository,
            CategoryRepository categoryRepository,
            MerchantImportService importService
    ) {
        this.repository = repository;
        this.categoryRepository = categoryRepository;
        this.importService = importService;
    }

    @GetMapping("/merchants")
    public Map<String, Object> list() {
        List<MerchantCategory> merchants = repository.findAll();
        int totalHits = merchants.stream().mapToInt(MerchantCategory::hitCount).sum();
        return Map.of(
                "merchants", merchants,
                "count", merchants.size(),
                // Transactions categorised from memory rather than by the built-in rules.
                "totalMemoryHits", totalHits
        );
    }

    /**
     * Creates or replaces one rule, optionally limited to an amount band.
     *
     * <p>A band is what lets a merchant whose description never varies — a subscription and an
     * order both arriving as "AMAZON.COM" — map to two categories. Omit the bounds for a
     * catch-all, which is what correcting a transaction by hand writes.
     */
    @PostMapping("/merchants")
    public ResponseEntity<?> saveRule(@RequestBody Map<String, Object> body) {
        String merchantKey = body.get("merchant_key") instanceof String s ? s.trim().toUpperCase() : "";
        String category = body.get("category") instanceof String s ? s.trim() : "";

        if (merchantKey.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("merchant_key is required."));
        }
        if (!categoryRepository.exists(category)) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "category must be one of: " + String.join(", ", categoryRepository.findAllNames())));
        }

        double min = body.get("min_amount") instanceof Number n ? n.doubleValue() : 0;
        double max = body.get("max_amount") instanceof Number n ? n.doubleValue() : MerchantCategory.UNBOUNDED;

        if (min < 0) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("min_amount cannot be negative — amounts are stored unsigned."));
        }
        // An empty or backwards band could never match a transaction, so it is a mistake rather
        // than a rule that simply does nothing.
        if (min >= max) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("min_amount must be less than max_amount."));
        }

        repository.saveRule(merchantKey, category, min, max, MerchantCategory.SOURCE_USER);
        return ResponseEntity.ok(Map.of("ok", true, "merchants", repository.findByKey(merchantKey)));
    }

    /** Forgets one merchant so it is asked about fresh on the next categorization run. */
    @DeleteMapping("/merchants/{id}")
    public ResponseEntity<?> forget(@PathVariable long id) {
        if (!repository.delete(id)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Merchant not found."));
        }
        return ResponseEntity.ok(Map.of("ok", true));
    }

    @DeleteMapping("/merchants")
    public Map<String, Object> forgetAll() {
        return Map.of("ok", true, "forgotten", repository.deleteAll());
    }

    /**
     * Reads rules from a CSV in the shape {@code /api/export/merchants.csv} writes. {@code mode}
     * is {@code merge} (the default: keep everything the file doesn't mention, overwrite what it
     * does) or {@code replace} (memory afterwards holds exactly the file). Nothing is written if
     * any row is invalid -- see {@link MerchantImportService}.
     */
    @PostMapping("/merchants/import")
    public ResponseEntity<?> importRules(
            @RequestParam("file") MultipartFile file,
            @RequestParam(defaultValue = "merge") String mode
    ) {
        if (!mode.equals("merge") && !mode.equals("replace")) {
            return ResponseEntity.badRequest().body(new ErrorResponse("mode must be merge or replace."));
        }
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("No file uploaded. Attach a file under field name 'file'."));
        }

        try {
            var rules = importService.parse(file.getInputStream());
            return ResponseEntity.ok(importService.apply(rules, mode.equals("replace")));
        } catch (MerchantImportService.ImportException e) {
            return ResponseEntity.badRequest().body(Map.of("error", e.getMessage(), "errors", e.errors));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Failed to read uploaded file."));
        }
    }
}

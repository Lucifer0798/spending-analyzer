package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.UncategorizedMerchant;
import com.spendinganalyzer.service.UncategorizedReviewService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * The review queue: uncategorized transactions grouped by merchant. Read-only on purpose -- the
 * client answers a group with {@code PATCH /api/transactions/bulk-category} and its
 * {@code transaction_ids}, which already categorizes them and teaches merchant memory exactly as
 * a single correction does, so there's one write path for "the user set this category", not two.
 */
@RestController
@RequestMapping("/api/transactions")
public class UncategorizedReviewController {

    private final UncategorizedReviewService reviewService;

    public UncategorizedReviewController(UncategorizedReviewService reviewService) {
        this.reviewService = reviewService;
    }

    @GetMapping("/uncategorized-merchants")
    public Map<String, Object> uncategorizedMerchants(@RequestParam(required = false) Long accountId) {
        List<UncategorizedMerchant> merchants = reviewService.merchants(accountId);
        int transactions = merchants.stream().mapToInt(UncategorizedMerchant::count).sum();
        return Map.of("merchants", merchants, "transactions", transactions);
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.RecurringSeries;
import com.spendinganalyzer.dto.SummaryResponse;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.InsightsService;
import com.spendinganalyzer.service.RecurringDetectionService;
import com.spendinganalyzer.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class InsightsController {

    private final StatsService statsService;
    private final InsightsService insightsService;
    private final RecurringDetectionService recurringDetectionService;
    private final TransactionRepository transactionRepository;

    public InsightsController(
            StatsService statsService,
            InsightsService insightsService,
            RecurringDetectionService recurringDetectionService,
            TransactionRepository transactionRepository
    ) {
        this.statsService = statsService;
        this.insightsService = insightsService;
        this.recurringDetectionService = recurringDetectionService;
        this.transactionRepository = transactionRepository;
    }

    @GetMapping("/summary")
    public SummaryResponse summary(@RequestParam(required = false) Long accountId) {
        return new SummaryResponse(
                statsService.computeCategoryTotals(accountId),
                statsService.computeMonthlyTotals(accountId),
                statsService.computeMonthlyCategorySeries(accountId)
        );
    }

    @GetMapping("/recurring")
    public Map<String, Object> recurring(@RequestParam(required = false) Long accountId) {
        List<RecurringSeries> series =
                recurringDetectionService.detect(transactionRepository.findSpendingTransactions(accountId));

        double totalAnnualized = series.stream().mapToDouble(RecurringSeries::annualizedCost).sum();
        double totalMonthly = totalAnnualized / 12.0;

        return Map.of(
                "recurring", series,
                "totalAnnualizedCost", Math.round(totalAnnualized * 100.0) / 100.0,
                "totalMonthlyEquivalent", Math.round(totalMonthly * 100.0) / 100.0
        );
    }

    @GetMapping("/predictions")
    public ResponseEntity<?> getPredictions() {
        return ResponseEntity.ok(insightsService.getCachedPredictions());
    }

    @PostMapping("/predictions/refresh")
    public ResponseEntity<?> refreshPredictions(@RequestParam(required = false) Long accountId) {
        try {
            return ResponseEntity.ok(insightsService.refreshPredictions(accountId));
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Prediction generation failed: " + e.getMessage()));
        }
    }
}

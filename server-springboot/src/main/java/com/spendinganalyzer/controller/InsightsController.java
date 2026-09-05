package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ComparisonResponse;
import com.spendinganalyzer.dto.DateRange;
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

import java.util.HashMap;
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
    public SummaryResponse summary(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        return new SummaryResponse(
                statsService.computeCategoryTotals(accountId, range),
                statsService.computeMonthlyTotals(accountId, range),
                statsService.computeMonthlyCategorySeries(accountId, range)
        );
    }

    /**
     * The active range against the period immediately before it, of the same length. Only
     * meaningful for a fully-bounded range -- {@code applicable} is false for "all time" or a
     * half-open filter, rather than the endpoint guessing at a period nobody asked for.
     */
    @GetMapping("/summary/comparison")
    public ComparisonResponse comparison(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        return statsService.computeComparison(accountId, range)
                .map(ComparisonResponse::of)
                .orElse(ComparisonResponse.NOT_APPLICABLE);
    }

    /** Earliest and latest dates on record, so the UI can bound its date pickers. */
    @GetMapping("/date-bounds")
    public Map<String, Object> dateBounds(@RequestParam(required = false) Long accountId) {
        DateRange available = statsService.availableRange(accountId);
        Map<String, Object> body = new HashMap<>();
        body.put("earliest", available.from());
        body.put("latest", available.to());
        return body;
    }

    @GetMapping("/recurring")
    public Map<String, Object> recurring(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        List<RecurringSeries> series = recurringDetectionService.detect(
                transactionRepository.findSpendingTransactions(accountId, range));

        double totalAnnualized = series.stream().mapToDouble(RecurringSeries::annualizedCost).sum();

        return Map.of(
                "recurring", series,
                "totalAnnualizedCost", Math.round(totalAnnualized * 100.0) / 100.0,
                "totalMonthlyEquivalent", Math.round((totalAnnualized / 12.0) * 100.0) / 100.0
        );
    }

    @GetMapping("/predictions")
    public ResponseEntity<?> getPredictions() {
        return ResponseEntity.ok(insightsService.getCachedPredictions());
    }

    /**
     * Forecasts deliberately ignore any date filter: a projection built from a narrow
     * window would be worse, and the cached result is not keyed by range.
     */
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

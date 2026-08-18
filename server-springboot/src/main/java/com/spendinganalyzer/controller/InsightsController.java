package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.SummaryResponse;
import com.spendinganalyzer.service.InsightsService;
import com.spendinganalyzer.service.StatsService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class InsightsController {

    private final StatsService statsService;
    private final InsightsService insightsService;

    public InsightsController(StatsService statsService, InsightsService insightsService) {
        this.statsService = statsService;
        this.insightsService = insightsService;
    }

    @GetMapping("/summary")
    public SummaryResponse summary() {
        return new SummaryResponse(
                statsService.computeCategoryTotals(),
                statsService.computeMonthlyTotals(),
                statsService.computeMonthlyCategorySeries()
        );
    }

    @GetMapping("/predictions")
    public ResponseEntity<?> getPredictions() {
        return ResponseEntity.ok(insightsService.getCachedPredictions());
    }

    @PostMapping("/predictions/refresh")
    public ResponseEntity<?> refreshPredictions() {
        try {
            return ResponseEntity.ok(insightsService.refreshPredictions());
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Prediction generation failed: " + e.getMessage()));
        }
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.CashFlowService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Each account's projected balance -- see {@link CashFlowService}. Not filtered by account or date
 * range: like net worth, it starts from logged balances, which belong to no date filter.
 */
@RestController
@RequestMapping("/api")
public class CashFlowController {

    private final CashFlowService service;

    public CashFlowController(CashFlowService service) {
        this.service = service;
    }

    @GetMapping("/cash-flow")
    public ResponseEntity<?> cashFlow(@RequestParam(defaultValue = "60") int horizonDays) {
        if (horizonDays < 7 || horizonDays > 120) {
            return ResponseEntity.badRequest().body(new ErrorResponse("horizonDays must be between 7 and 120."));
        }
        return ResponseEntity.ok(service.forecast(horizonDays));
    }
}

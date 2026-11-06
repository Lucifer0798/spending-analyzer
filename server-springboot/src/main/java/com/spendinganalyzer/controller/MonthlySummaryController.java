package com.spendinganalyzer.controller;

import com.spendinganalyzer.service.MonthlySummaryService;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/** How the last complete month went -- see {@link MonthlySummaryService}. */
@RestController
@RequestMapping("/api")
public class MonthlySummaryController {

    private final MonthlySummaryService service;

    public MonthlySummaryController(MonthlySummaryService service) {
        this.service = service;
    }

    @GetMapping("/monthly-summary")
    public MonthlySummaryService.Summary summary(@RequestParam(required = false) Long accountId) {
        return service.lastMonth(accountId);
    }
}

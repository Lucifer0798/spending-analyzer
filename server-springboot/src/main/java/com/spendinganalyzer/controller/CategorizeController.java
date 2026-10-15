package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.ReapplyRulesResult;
import com.spendinganalyzer.service.CategorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CategorizeController {

    private final CategorizationService categorizationService;

    public CategorizeController(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    /** {@code batchId}, an upload's id, adds that upload's own counts -- see {@link CategorizationService#categorizeAll(String)}. */
    @PostMapping("/categorize")
    public ResponseEntity<?> categorize(@RequestParam(required = false) String batchId) {
        try {
            CategorizeResponse response = categorizationService.categorizeAll(batchId);
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Categorization failed: " + e.getMessage()));
        }
    }

    /**
     * Re-runs memory and the rules over transactions a rule categorized, after rules change. Only
     * {@code category_source = 'rule'} rows are considered -- nothing the user set is touched.
     * {@code dryRun=true} (the default) reports what would change without writing, so the client
     * can show it and ask first.
     */
    @PostMapping("/categorize/reapply-rules")
    public ReapplyRulesResult reapplyRules(@RequestParam(defaultValue = "true") boolean dryRun) {
        return categorizationService.reapplyRules(dryRun);
    }
}

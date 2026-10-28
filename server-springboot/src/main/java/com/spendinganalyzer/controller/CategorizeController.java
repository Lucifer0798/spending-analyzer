package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.ReapplyRulesResult;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.CategorizationService;
import com.spendinganalyzer.service.ChangeLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api")
public class CategorizeController {

    private final CategorizationService categorizationService;
    private final TransactionRepository transactionRepository;
    private final ChangeLogService changeLog;

    public CategorizeController(CategorizationService categorizationService, TransactionRepository transactionRepository,
                                ChangeLogService changeLog) {
        this.categorizationService = categorizationService;
        this.transactionRepository = transactionRepository;
        this.changeLog = changeLog;
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
        if (dryRun) return categorizationService.reapplyRules(true);
        // Snapshot what the rules can touch, so applying them can be undone from History.
        List<Transaction> before = transactionRepository.findByCategorySource("rule");
        ReapplyRulesResult result = categorizationService.reapplyRules(false);
        changeLog.recordEdit("reapply-rules",
                "Re-applied keyword rules (" + (result.changed() + result.cleared()) + " changed)",
                before, transactionRepository.findByIds(before.stream().map(Transaction::id).toList()), List.of());
        return result;
    }
}

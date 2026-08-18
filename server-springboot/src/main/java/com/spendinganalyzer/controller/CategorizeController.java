package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.CategorizeResponse;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.CategorizationService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api")
public class CategorizeController {

    private final CategorizationService categorizationService;

    public CategorizeController(CategorizationService categorizationService) {
        this.categorizationService = categorizationService;
    }

    @PostMapping("/categorize")
    public ResponseEntity<?> categorize() {
        try {
            CategorizeResponse response = categorizationService.categorizeAll();
            return ResponseEntity.ok(response);
        } catch (Exception e) {
            return ResponseEntity.status(502)
                    .body(new ErrorResponse("Categorization failed: " + e.getMessage()));
        }
    }
}

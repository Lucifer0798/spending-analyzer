package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api")
public class MerchantController {

    private final MerchantCategoryRepository repository;

    public MerchantController(MerchantCategoryRepository repository) {
        this.repository = repository;
    }

    @GetMapping("/merchants")
    public Map<String, Object> list() {
        List<MerchantCategory> merchants = repository.findAll();
        int totalHits = merchants.stream().mapToInt(MerchantCategory::hitCount).sum();
        return Map.of(
                "merchants", merchants,
                "count", merchants.size(),
                // Transactions categorised from memory rather than by the model.
                "totalMemoryHits", totalHits
        );
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
}

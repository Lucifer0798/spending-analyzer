package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.DuplicateChargeService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Likely double charges -- see {@link DuplicateChargeService}. */
@RestController
@RequestMapping("/api/transactions/duplicates")
public class DuplicateChargeController {

    private final DuplicateChargeService service;

    public DuplicateChargeController(DuplicateChargeService service) {
        this.service = service;
    }

    @GetMapping
    public Map<String, Object> list(@RequestParam(required = false) Long accountId) {
        return Map.of("groups", service.find(accountId));
    }

    /** Body: {@code {"transaction_ids": [12, 15]}} -- the whole group, as listed. */
    @PostMapping("/dismiss")
    public ResponseEntity<?> dismiss(@RequestBody Map<String, Object> body) {
        List<Long> ids = new ArrayList<>();
        if (body.get("transaction_ids") instanceof List<?> list) {
            for (Object o : list) if (o instanceof Number n) ids.add(n.longValue());
        }
        if (ids.size() < 2) {
            return ResponseEntity.badRequest().body(new ErrorResponse("transaction_ids must list the group's transactions (at least two)."));
        }
        service.dismiss(ids);
        return ResponseEntity.ok(Map.of("ok", true));
    }
}

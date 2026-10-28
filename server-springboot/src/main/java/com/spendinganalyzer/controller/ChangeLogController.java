package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.ChangeLogService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

/** Recent changes to transactions, and undoing one -- see {@link ChangeLogService}. */
@RestController
@RequestMapping("/api/history")
public class ChangeLogController {

    private final ChangeLogService service;

    public ChangeLogController(ChangeLogService service) {
        this.service = service;
    }

    @GetMapping
    public ResponseEntity<?> recent(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 200) {
            return ResponseEntity.badRequest().body(new ErrorResponse("limit must be between 1 and 200."));
        }
        return ResponseEntity.ok(Map.of("entries", service.recent(limit)));
    }

    @PostMapping("/{id}/undo")
    public ResponseEntity<?> undo(@PathVariable long id) {
        try {
            return service.undo(id)
                    .<ResponseEntity<?>>map(ResponseEntity::ok)
                    .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("No such change.")));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
    }
}

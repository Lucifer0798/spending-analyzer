package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.OwedService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;
import java.util.NoSuchElementException;

/** Who owes you -- see {@link OwedService}. */
@RestController
@RequestMapping("/api/owed")
public class OwedController {

    private final OwedService service;

    public OwedController(OwedService service) {
        this.service = service;
    }

    /** Split charges with money still to come back, and the total outstanding per currency. */
    @GetMapping
    public OwedService.Owed list(@RequestParam(required = false) Long accountId) {
        return service.list(accountId);
    }

    /**
     * Records a repayment toward split charge {@code id}. Body, all optional:
     * {@code {"repaymentId": 12}} links the credit it arrived as (its amount and date);
     * otherwise {@code {"amount": 30, "repaidOn": "2026-11-25"}}, defaulting to everything
     * outstanding, today.
     */
    @PostMapping("/{id}/repayments")
    public ResponseEntity<?> record(@PathVariable long id, @RequestBody(required = false) Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Object rawAmount = b.get("amount");
        if (rawAmount != null && !(rawAmount instanceof Number)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("amount must be a number."));
        }
        Object rawRepayment = b.get("repaymentId");
        if (rawRepayment != null && !(rawRepayment instanceof Number)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("repaymentId must be a transaction id."));
        }
        Object rawDate = b.get("repaidOn");
        try {
            long recorded = service.record(id,
                    rawAmount == null ? null : ((Number) rawAmount).doubleValue(),
                    rawRepayment == null ? null : ((Number) rawRepayment).longValue(),
                    rawDate instanceof String s ? s : null);
            return ResponseEntity.ok(Map.of("ok", true, "id", recorded));
        } catch (NoSuchElementException e) {
            return ResponseEntity.status(404).body(new ErrorResponse(e.getMessage()));
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        }
    }

    /** Takes a recorded repayment back -- the Undo beside it. */
    @DeleteMapping("/repayments/{repaymentRecordId}")
    public ResponseEntity<?> undo(@PathVariable long repaymentRecordId) {
        return service.undo(repaymentRecordId)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(404).body(new ErrorResponse("No such repayment."));
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.NetWorthResponse;
import com.spendinganalyzer.repository.NetWorthTargetRepository;
import com.spendinganalyzer.service.NetWorthService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.Currency;
import java.util.Map;

/**
 * The net worth view across every active account — logging or forgetting a balance lives on
 * {@code /api/accounts/{id}/balances} instead, the same split transaction tags follow between
 * the account-scoped write and the cross-account read.
 */
@RestController
@RequestMapping("/api/net-worth")
public class NetWorthController {

    private final NetWorthService netWorthService;
    private final NetWorthTargetRepository targets;

    public NetWorthController(NetWorthService netWorthService, NetWorthTargetRepository targets) {
        this.netWorthService = netWorthService;
        this.targets = targets;
    }

    @GetMapping
    public NetWorthResponse get() {
        return netWorthService.compute();
    }

    /**
     * Sets the single net worth target, replacing any existing one — there is only ever one, the
     * same upsert-a-whole-row convention {@code BudgetController.set} and {@code GoalController
     * .create} follow. {@code target_date} is optional: without it, progress is informational
     * only, the same as a savings goal with no target date of its own.
     */
    @PostMapping("/target")
    public ResponseEntity<?> setTarget(@RequestBody Map<String, Object> body) {
        Double amount = body.get("target_amount") instanceof Number n ? n.doubleValue() : null;
        String date = body.get("target_date") instanceof String s && !s.isBlank() ? s.trim() : null;
        String currency = body.get("currency") instanceof String s && !s.isBlank() ? s.trim().toUpperCase() : null;

        if (amount == null || amount <= 0) {
            return ResponseEntity.badRequest().body(new ErrorResponse("target_amount must be greater than zero."));
        }
        if (currency == null || !isValidCurrency(currency)) {
            return ResponseEntity.badRequest().body(new ErrorResponse("currency must be a valid ISO 4217 code."));
        }
        if (date != null) {
            try {
                LocalDate.parse(date);
            } catch (DateTimeParseException e) {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("target_date must be in YYYY-MM-DD form, got: " + date));
            }
        }

        return ResponseEntity.ok(targets.upsert(amount, date, currency));
    }

    @DeleteMapping("/target")
    public ResponseEntity<?> clearTarget() {
        targets.delete();
        return ResponseEntity.ok(Map.of("ok", true));
    }

    private static boolean isValidCurrency(String code) {
        try {
            Currency.getInstance(code);
            return true;
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}

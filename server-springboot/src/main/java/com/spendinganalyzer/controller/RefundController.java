package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.RefundPairView;
import com.spendinganalyzer.model.RefundPair;
import com.spendinganalyzer.service.RefundMatchingService;
import com.spendinganalyzer.service.RefundMatchingService.PairRef;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Refund matching -- see {@link RefundMatchingService}. */
@RestController
@RequestMapping("/api/refunds")
public class RefundController {

    private final RefundMatchingService service;

    public RefundController(RefundMatchingService service) {
        this.service = service;
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            return ResponseEntity.badRequest().body(new ErrorResponse("limit must be between 1 and 500."));
        }
        RefundMatchingService.Suggestions found = service.suggest(limit);
        return ResponseEntity.ok(Map.of(
                "total", found.total(),
                "suggestions", found.suggestions(),
                "maxDaysAfter", RefundMatchingService.MAX_DAYS_AFTER));
    }

    /** Confirmed and dismissed pairs, the most recent refund first. */
    @GetMapping
    public Map<String, Object> list() {
        List<RefundPairView> all = service.list();
        return Map.of(
                "confirmed", all.stream().filter(p -> RefundPair.CONFIRMED.equals(p.status())).toList(),
                "dismissed", all.stream().filter(p -> RefundPair.DISMISSED.equals(p.status())).toList());
    }

    /** Body: {@code {"pairs": [{"purchaseId": 1, "refundId": 2}, ...]}}. */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody Map<String, Object> body) {
        List<PairRef> refs = asPairs(body.get("pairs"));
        if (refs.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("pairs is required and must not be empty."));
        }
        RefundMatchingService.ConfirmResult result = service.confirm(refs);
        return ResponseEntity.ok(Map.of(
                "confirmed", result.confirmed(),
                "skipped", result.skipped().stream()
                        .map(s -> Map.of("purchaseId", s.purchaseId(), "refundId", s.refundId(), "reason", s.reason()))
                        .toList()));
    }

    /** Same body as confirm. */
    @PostMapping("/dismiss")
    public ResponseEntity<?> dismiss(@RequestBody Map<String, Object> body) {
        List<PairRef> refs = asPairs(body.get("pairs"));
        if (refs.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("pairs is required and must not be empty."));
        }
        return ResponseEntity.ok(Map.of("dismissed", service.dismiss(refs)));
    }

    /** Unpairs a confirmed refund (it counts as before again) or forgets a dismissal. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> undo(@PathVariable long id) {
        return service.undo(id)
                ? ResponseEntity.ok(Map.of("ok", true))
                : ResponseEntity.status(404).body(new ErrorResponse("No such refund pair."));
    }

    private static List<PairRef> asPairs(Object value) {
        List<PairRef> refs = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("purchaseId") instanceof Number p && m.get("refundId") instanceof Number r) {
                    refs.add(new PairRef(p.longValue(), r.longValue()));
                }
            }
        }
        return refs;
    }
}

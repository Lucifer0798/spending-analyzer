package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.TransferPairView;
import com.spendinganalyzer.model.TransferPair;
import com.spendinganalyzer.service.TransferMatchingService;
import com.spendinganalyzer.service.TransferMatchingService.PairRef;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Transfer matching -- see {@link TransferMatchingService}. Suggestions span every account and
 * all time on purpose: a transfer is by definition between two accounts, so the account filter
 * would hide exactly the half that makes it one.
 */
@RestController
@RequestMapping("/api/transfers")
public class TransferController {

    private final TransferMatchingService service;

    public TransferController(TransferMatchingService service) {
        this.service = service;
    }

    @GetMapping("/suggestions")
    public ResponseEntity<?> suggestions(@RequestParam(defaultValue = "50") int limit) {
        if (limit < 1 || limit > 500) {
            return ResponseEntity.badRequest().body(new ErrorResponse("limit must be between 1 and 500."));
        }
        TransferMatchingService.Suggestions found = service.suggest(limit);
        Map<String, Object> body = new HashMap<>();
        body.put("total", found.total());
        body.put("suggestions", found.suggestions());
        body.put("transfer_category", service.transferCategory().orElse(null));
        body.put("max_days_apart", TransferMatchingService.MAX_DAYS_APART);
        return ResponseEntity.ok(body);
    }

    /** Confirmed and dismissed pairs, each with the most recent transfer date first. */
    @GetMapping
    public Map<String, Object> list() {
        List<TransferPairView> all = service.list();
        return Map.of(
                "confirmed", all.stream().filter(p -> TransferPair.CONFIRMED.equals(p.status())).toList(),
                "dismissed", all.stream().filter(p -> TransferPair.DISMISSED.equals(p.status())).toList());
    }

    /** Body: {@code {"pairs": [{"debit_id": 1, "credit_id": 2}, ...]}}. */
    @PostMapping("/confirm")
    public ResponseEntity<?> confirm(@RequestBody Map<String, Object> body) {
        List<PairRef> refs = asPairs(body.get("pairs"));
        if (refs.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("pairs is required and must not be empty."));
        }
        try {
            TransferMatchingService.ConfirmResult result = service.confirm(refs);
            return ResponseEntity.ok(Map.of(
                    "confirmed", result.confirmed(),
                    "skipped", result.skipped().stream()
                            .map(s -> Map.of("debit_id", s.debitId(), "credit_id", s.creditId(), "reason", s.reason()))
                            .toList()));
        } catch (IllegalStateException e) {
            return ResponseEntity.status(409).body(new ErrorResponse(e.getMessage()));
        }
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

    /** Undoes a confirmation (restoring both categories) or forgets a dismissal. */
    @DeleteMapping("/{id}")
    public ResponseEntity<?> undo(@PathVariable long id) {
        return service.undo(id)
                .<ResponseEntity<?>>map(restored -> ResponseEntity.ok(Map.of("ok", true, "restored", restored)))
                .orElseGet(() -> ResponseEntity.status(404).body(new ErrorResponse("No such transfer pair.")));
    }

    private static List<PairRef> asPairs(Object value) {
        List<PairRef> refs = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Map<?, ?> m && m.get("debit_id") instanceof Number d && m.get("credit_id") instanceof Number c) {
                    refs.add(new PairRef(d.longValue(), c.longValue()));
                }
            }
        }
        return refs;
    }
}

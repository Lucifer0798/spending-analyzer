package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.ChangeLogService;
import com.spendinganalyzer.service.MerchantNameService;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Merchant names and merges -- see {@link MerchantNameService}. */
@RestController
@RequestMapping("/api/merchant-names")
public class MerchantNameController {

    /** More than any one rename or merge needs; a guard on the request size, not a product limit. */
    static final int MAX_KEYS = 500;

    private final MerchantNameService service;
    private final ChangeLogService changeLog;

    public MerchantNameController(MerchantNameService service, ChangeLogService changeLog) {
        this.service = service;
        this.changeLog = changeLog;
    }

    private ChangeLogService.Watch watch(List<String> keys) {
        return changeLog.watch("merchant_names", "merchant_key IN (:keys)", Map.of("keys", keys));
    }

    @GetMapping
    public MerchantNameService.Listing list() {
        return service.list();
    }

    /**
     * Body: {@code {"keys": ["AMZN MKTP US", "AMAZON.COM"], "name": "Amazon"}} -- one key renames,
     * several merge. A POST like {@code /api/merchants}' rule save: CORS doesn't allow PUT.
     */
    @PostMapping
    @Transactional
    public ResponseEntity<?> rename(@RequestBody Map<String, Object> body) {
        List<String> keys = keys(body);
        if (keys == null) return badKeys();
        String name = body.get("name") instanceof String s ? s.trim() : "";
        if (name.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("name is required -- to take a name off, use /clear."));
        }
        if (name.length() > MerchantNameService.MAX_NAME_LENGTH) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "name can be at most " + MerchantNameService.MAX_NAME_LENGTH + " characters."));
        }
        var watch = watch(keys);
        String stored = service.rename(keys, name);
        changeLog.recordSettings("merchant-name", keys.size() == 1
                ? "Named " + keys.get(0) + " \"" + stored + "\""
                : "Merged " + keys.size() + " merchants as \"" + stored + "\"", watch);
        return ResponseEntity.ok(Map.of("ok", true, "name", stored, "updated", keys.size()));
    }

    /** Body: {@code {"keys": [...]}} -- each goes back to showing (and counting) as itself. */
    @PostMapping("/clear")
    @Transactional
    public ResponseEntity<?> clear(@RequestBody Map<String, Object> body) {
        List<String> keys = keys(body);
        if (keys == null) return badKeys();
        var watch = watch(keys);
        int cleared = service.clear(keys);
        changeLog.recordSettings("merchant-name", keys.size() == 1
                ? "Took the name off " + keys.get(0)
                : "Took the names off " + keys.size() + " merchants", watch);
        return ResponseEntity.ok(Map.of("ok", true, "cleared", cleared));
    }

    /** Body: {@code {"stem": "AMAZON"}} -- a suggestion's shared word, as listed. */
    @PostMapping("/suggestions/dismiss")
    @Transactional
    public ResponseEntity<?> dismiss(@RequestBody Map<String, Object> body) {
        String stem = body.get("stem") instanceof String s ? s.trim() : "";
        if (stem.isEmpty()) return ResponseEntity.badRequest().body(new ErrorResponse("stem is required."));
        var watch = changeLog.watch("merchant_suggestion_dismissals", "stem = :stem", Map.of("stem", stem));
        service.dismissSuggestion(stem);
        changeLog.recordSettings("merchant-name", "Said merchants starting " + stem + " aren't one merchant", watch);
        return ResponseEntity.ok(Map.of("ok", true));
    }

    /** The distinct, non-blank keys in the body, or null when there are none or too many. */
    private static List<String> keys(Map<String, Object> body) {
        Set<String> keys = new LinkedHashSet<>();
        if (body.get("keys") instanceof List<?> list) {
            for (Object o : list) if (o instanceof String s && !s.isBlank()) keys.add(s);
        }
        return keys.isEmpty() || keys.size() > MAX_KEYS ? null : new ArrayList<>(keys);
    }

    private static ResponseEntity<ErrorResponse> badKeys() {
        return ResponseEntity.badRequest().body(new ErrorResponse(
                "keys must list between 1 and " + MAX_KEYS + " merchant keys."));
    }
}

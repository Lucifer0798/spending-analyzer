package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.BackupData;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.service.BackupService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;

/**
 * A full backup, for keeping one or moving to a new instance — distinct from {@link
 * ExportController}'s CSVs, which mirror one screen's filtered view rather than everything.
 *
 * <p>Import is a restore, not a merge: it wipes every table a backup covers and reloads it
 * exactly as exported, ids included. Merging two instances would need real conflict-resolution
 * rules for every table — which account is "the same" as which, what happens when two merchant
 * rules disagree — and a wrong rule could silently produce duplicate or inconsistent data rather
 * than an obvious error. A restore has no such rules to get wrong: it is either exactly the file,
 * or (on a rejected version) unchanged.
 */
@RestController
@RequestMapping("/api/backup")
public class BackupController {

    private final BackupService backupService;

    public BackupController(BackupService backupService) {
        this.backupService = backupService;
    }

    /**
     * Accounts, categories, transactions, merchant memory, budgets, recurring overrides, and
     * savings goals, as one JSON file. {@code predictions_cache} is left out — regenerating a
     * forecast is one click, the same reasoning the account-scoped cache migration used to
     * justify dropping the old row rather than migrating it.
     */
    @GetMapping
    public ResponseEntity<BackupData> export() {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename("spending-analyzer-backup-" + LocalDate.now() + ".json", StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(MediaType.APPLICATION_JSON)
                .body(backupService.export());
    }

    /**
     * Restores from a file this same endpoint produced. There is no recovery from a hand-edited
     * or foreign JSON file beyond the version check below — this is a restore tool, not a general
     * importer.
     */
    @PostMapping("/import")
    public ResponseEntity<?> restore(@RequestBody BackupData data) {
        if (data.version() != BackupData.CURRENT_VERSION) {
            return ResponseEntity.badRequest().body(new ErrorResponse(
                    "Unsupported backup version: " + data.version() + ". Expected " + BackupData.CURRENT_VERSION + "."));
        }
        return ResponseEntity.ok(backupService.restore(data));
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.UploadResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.DuplicateDetectionService;
import com.spendinganalyzer.service.FileParsingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final FileParsingService fileParsingService;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final DuplicateDetectionService duplicateDetectionService;

    public UploadController(
            FileParsingService fileParsingService,
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            DuplicateDetectionService duplicateDetectionService
    ) {
        this.fileParsingService = fileParsingService;
        this.transactionRepository = transactionRepository;
        this.accountRepository = accountRepository;
        this.duplicateDetectionService = duplicateDetectionService;
    }

    /**
     * The six optional overrides let a caller correct whichever columns auto-detection couldn't
     * confidently map on its own -- see {@link FileParsingService.ColumnMapping}. Each is null by
     * default, which means "keep guessing," so a caller only needs to supply the ones a prior
     * 422 response said it couldn't work out.
     */
    @PostMapping("/upload")
    public ResponseEntity<?> upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(required = false) Long accountId,
            @RequestParam(defaultValue = "true") boolean skipDuplicates,
            @RequestParam(required = false) String dateColumn,
            @RequestParam(required = false) String descriptionColumn,
            @RequestParam(required = false) String amountColumn,
            @RequestParam(required = false) String debitColumn,
            @RequestParam(required = false) String creditColumn,
            @RequestParam(required = false) String categoryColumn
    ) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("No file uploaded. Attach a file under field name 'file'."));
        }

        long targetAccountId = accountId != null ? accountId : Account.DEFAULT_ID;
        var account = accountRepository.findById(targetAccountId);
        if (account.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("Account " + targetAccountId + " does not exist."));
        }

        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        var columnMapping = new FileParsingService.ColumnMapping(
                dateColumn, descriptionColumn, amountColumn, debitColumn, creditColumn, categoryColumn);
        List<ParsedTransaction> parsed;

        try {
            if (name.endsWith(".csv")) {
                parsed = fileParsingService.parseCsv(file.getInputStream(), columnMapping);
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                parsed = fileParsingService.parseExcel(file.getInputStream(), columnMapping);
            } else {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Unsupported file type. Upload a .csv or .xlsx file."));
            }
        } catch (FileParsingService.ColumnDetectionException e) {
            // 422: the request itself was fine, but the file's columns need a human to confirm
            // or correct them -- distinct from a plain 400 so the frontend can offer a mapping
            // form instead of just showing an error.
            return ResponseEntity.unprocessableEntity().body(e.result);
        } catch (FileParsingService.ParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Failed to read uploaded file."));
        }

        if (parsed.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("No valid transactions found in the file."));
        }

        List<ParsedTransaction> toInsert = parsed;
        int skipped = 0;

        if (skipDuplicates) {
            Map<String, Integer> existing = transactionRepository.countExistingKeys(
                    targetAccountId,
                    duplicateDetectionService.minDate(parsed),
                    duplicateDetectionService.maxDate(parsed));

            DuplicateDetectionService.Result result =
                    duplicateDetectionService.filterDuplicates(parsed, existing);
            toInsert = result.toInsert();
            skipped = result.skipped();
        }

        String batchId = UUID.randomUUID().toString();
        if (!toInsert.isEmpty()) {
            transactionRepository.insertBatch(toInsert, batchId, targetAccountId);
        }

        long preCategorized = toInsert.stream().filter(t -> t.category() != null).count();

        return ResponseEntity.ok(new UploadResponse(
                batchId,
                toInsert.size(),
                (int) preCategorized,
                skipped,
                parsed.size(),
                targetAccountId,
                account.get().name()
        ));
    }
}

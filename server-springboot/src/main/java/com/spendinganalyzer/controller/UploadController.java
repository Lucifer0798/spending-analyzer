package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.UploadResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.ChangeLogService;
import com.spendinganalyzer.service.DuplicateDetectionService;
import com.spendinganalyzer.service.FileParsingService;
import com.spendinganalyzer.service.OfxParser;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api")
public class UploadController {

    /** All OFX: .qfx is Quicken's name for it, .qbo QuickBooks Web Connect's. */
    private static final List<String> OFX_EXTENSIONS = List.of(".ofx", ".qfx", ".qbo");

    private final FileParsingService fileParsingService;
    private final OfxParser ofxParser;
    private final ChangeLogService changeLog;
    private final TransactionRepository transactionRepository;
    private final AccountRepository accountRepository;
    private final DuplicateDetectionService duplicateDetectionService;

    public UploadController(
            FileParsingService fileParsingService,
            TransactionRepository transactionRepository,
            AccountRepository accountRepository,
            DuplicateDetectionService duplicateDetectionService,
            OfxParser ofxParser,
            ChangeLogService changeLog
    ) {
        this.fileParsingService = fileParsingService;
        this.ofxParser = ofxParser;
        this.changeLog = changeLog;
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
            } else if (OFX_EXTENSIONS.stream().anyMatch(name::endsWith)) {
                OfxParser.Statement statement = ofxParser.parse(file.getInputStream());
                String accountCurrency = account.get().currency();
                // A CSV can't say what currency it's in; an OFX statement can, so a mismatch is
                // caught here rather than quietly mixing two currencies in one account's totals.
                if (statement.currency() != null && !statement.currency().equalsIgnoreCase(accountCurrency)) {
                    return ResponseEntity.badRequest().body(new ErrorResponse(
                            "This statement is in " + statement.currency() + ", but " + account.get().name()
                                    + " is a " + accountCurrency + " account. Import it into a "
                                    + statement.currency() + " account instead."));
                }
                parsed = statement.transactions();
            } else {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Unsupported file type. Upload a .csv, .xlsx, .ofx or .qfx file."));
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
            Set<String> fileIds = parsed.stream().map(ParsedTransaction::externalId)
                    .filter(Objects::nonNull).collect(Collectors.toSet());
            // With bank IDs in the file, rows are matched on those first; the count-based check
            // then only compares against stored rows that have no ID -- see filterDuplicates.
            Map<String, Integer> existing = transactionRepository.countExistingKeys(
                    targetAccountId,
                    duplicateDetectionService.minDate(parsed),
                    duplicateDetectionService.maxDate(parsed),
                    !fileIds.isEmpty());

            DuplicateDetectionService.Result result = duplicateDetectionService.filterDuplicates(
                    parsed, existing, transactionRepository.existingExternalIds(targetAccountId, fileIds));
            toInsert = result.toInsert();
            skipped = result.skipped();
        }

        String batchId = UUID.randomUUID().toString();
        Long changeId = null;
        if (!toInsert.isEmpty()) {
            transactionRepository.insertBatch(toInsert, batchId, targetAccountId);
            changeId = changeLog.recordImport("Imported " + toInsert.size() + " transaction" + (toInsert.size() == 1 ? "" : "s")
                    + " into " + account.get().name() + " from " + file.getOriginalFilename(), batchId, toInsert.size());
        }

        long preCategorized = toInsert.stream().filter(t -> t.category() != null).count();

        return ResponseEntity.ok(new UploadResponse(
                batchId,
                toInsert.size(),
                (int) preCategorized,
                skipped,
                parsed.size(),
                targetAccountId,
                account.get().name(),
                changeId
        ));
    }
}

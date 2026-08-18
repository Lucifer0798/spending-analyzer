package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.UploadResponse;
import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.FileParsingService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api")
public class UploadController {

    private final FileParsingService fileParsingService;
    private final TransactionRepository transactionRepository;

    public UploadController(FileParsingService fileParsingService, TransactionRepository transactionRepository) {
        this.fileParsingService = fileParsingService;
        this.transactionRepository = transactionRepository;
    }

    @PostMapping("/upload")
    public ResponseEntity<?> upload(@RequestParam("file") MultipartFile file) {
        if (file.isEmpty()) {
            return ResponseEntity.badRequest()
                    .body(new ErrorResponse("No file uploaded. Attach a file under field name 'file'."));
        }

        String name = file.getOriginalFilename() != null ? file.getOriginalFilename().toLowerCase() : "";
        List<ParsedTransaction> transactions;

        try {
            if (name.endsWith(".csv")) {
                transactions = fileParsingService.parseCsv(file.getInputStream());
            } else if (name.endsWith(".xlsx") || name.endsWith(".xls")) {
                transactions = fileParsingService.parseExcel(file.getInputStream());
            } else {
                return ResponseEntity.badRequest()
                        .body(new ErrorResponse("Unsupported file type. Upload a .csv or .xlsx file."));
            }
        } catch (FileParsingService.ParseException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse(e.getMessage()));
        } catch (IOException e) {
            return ResponseEntity.badRequest().body(new ErrorResponse("Failed to read uploaded file."));
        }

        if (transactions.isEmpty()) {
            return ResponseEntity.badRequest().body(new ErrorResponse("No valid transactions found in the file."));
        }

        String batchId = UUID.randomUUID().toString();
        transactionRepository.insertBatch(transactions, batchId);

        long preCategorized = transactions.stream().filter(t -> t.category() != null).count();

        return ResponseEntity.ok(new UploadResponse(batchId, transactions.size(), (int) preCategorized));
    }
}

package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.CsvExportService;
import com.spendinganalyzer.service.InsightsService;
import com.spendinganalyzer.service.StatsService;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

/**
 * CSV downloads of whatever the user is currently looking at. The transaction and spend exports
 * take the same account and date-range filters as the screen they mirror, so exporting a filtered
 * view gives exactly the filtered rows rather than everything on record. The two forecast exports
 * take no filters, because there is only ever one cached forecast to export.
 *
 * <p>These are plain GETs returning an attachment, so the frontend can link straight to them
 * and let the browser handle the download.
 */
@RestController
@RequestMapping("/api/export")
public class ExportController {

    /**
     * Exports are deliberately unpaged — a partial export is worse than none, and the
     * transactions list endpoint's 200-row default would silently truncate the file.
     */
    private static final int NO_LIMIT = Integer.MAX_VALUE;

    private final TransactionRepository transactionRepository;
    private final StatsService statsService;
    private final InsightsService insightsService;
    private final CsvExportService csv;

    public ExportController(
            TransactionRepository transactionRepository,
            StatsService statsService,
            InsightsService insightsService,
            CsvExportService csv
    ) {
        this.transactionRepository = transactionRepository;
        this.statsService = statsService;
        this.insightsService = insightsService;
        this.csv = csv;
    }

    @GetMapping("/transactions.csv")
    public ResponseEntity<byte[]> transactions(
            @RequestParam(required = false) String category,
            @RequestParam(required = false) String month,
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        var rows = transactionRepository.find(category, month, accountId, range, NO_LIMIT, 0);
        return attachment("transactions", csv.transactions(rows));
    }

    @GetMapping("/categories.csv")
    public ResponseEntity<byte[]> categories(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        return attachment("spend-by-category", csv.categoryTotals(
                statsService.computeCategoryTotals(accountId, range)));
    }

    @GetMapping("/monthly.csv")
    public ResponseEntity<byte[]> monthly(
            @RequestParam(required = false) Long accountId,
            @RequestParam(required = false) String from,
            @RequestParam(required = false) String to
    ) {
        DateRange range = DateRange.of(from, to);
        return attachment("spend-by-month", csv.monthlyTotals(
                statsService.computeMonthlyTotals(accountId, range)));
    }

    /**
     * The forecast and its savings suggestions, as two tables.
     *
     * <p>These take no account or date parameters, unlike every other export here. That is not an
     * oversight: there is one cached forecast, built from an account's full history on purpose,
     * so there is no filtered version of it to export.
     */
    @GetMapping("/predictions.csv")
    public ResponseEntity<byte[]> predictions() {
        var cached = insightsService.getCachedPredictions();
        var payload = cached.predictions();

        // Nothing generated yet gives a header-only file rather than a 404 — the frontend hides
        // the link in that case, and an empty table is a truthful answer to "export the forecast".
        return attachment("predictions", csv.predictions(
                payload == null ? List.of() : payload.predictions(), cached.generatedAt()));
    }

    @GetMapping("/recommendations.csv")
    public ResponseEntity<byte[]> recommendations() {
        var cached = insightsService.getCachedPredictions();
        var payload = cached.predictions();

        return attachment("recommendations", csv.recommendations(
                payload == null ? List.of() : payload.recommendations(), cached.generatedAt()));
    }

    /** Dated filename so repeated exports land beside each other instead of overwriting. */
    private static ResponseEntity<byte[]> attachment(String name, byte[] body) {
        ContentDisposition disposition = ContentDisposition.attachment()
                .filename(name + "-" + LocalDate.now() + ".csv", StandardCharsets.UTF_8)
                .build();

        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, disposition.toString())
                .contentType(new MediaType("text", "csv", StandardCharsets.UTF_8))
                .body(body);
    }
}

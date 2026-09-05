package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.repository.TransactionRepository;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class ExportControllerTest {

    /** Bigger than the transactions endpoint's 200-row page, which is the point of the size test. */
    private static final int SEEDED_ROWS = 250;

    private static final String MARKER = "EXPORT FIXTURE";

    @Autowired
    private ExportController controller;

    @Autowired
    private TransactionRepository transactions;

    @BeforeEach
    void seed() {
        List<ParsedTransaction> rows = new ArrayList<>();
        for (int i = 0; i < SEEDED_ROWS; i++) {
            // Spread across two months so the date-range filter has something to cut.
            String date = i < 100 ? "2026-05-%02d".formatted((i % 28) + 1) : "2026-06-%02d".formatted((i % 28) + 1);
            rows.add(new ParsedTransaction(date, MARKER + " " + i, 10.00 + i, "debit", "Groceries"));
        }
        transactions.insertBatch(rows, "export-test-batch", 1L);
    }

    private static List<CSVRecord> parse(ResponseEntity<byte[]> response) throws IOException {
        String text = new String(response.getBody(), StandardCharsets.UTF_8);
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            return parser.getRecords();
        }
    }

    /** Only the rows this test seeded, so unrelated data in the file-backed test database cannot skew counts. */
    private static List<CSVRecord> seededRows(ResponseEntity<byte[]> response) throws IOException {
        return parse(response).stream().filter(r -> r.get("description").startsWith(MARKER)).toList();
    }

    // --- transactions -----------------------------------------------------------

    @Test
    @DisplayName("exports every matching row rather than the first page")
    void doesNotTruncateAtThePageSize() throws IOException {
        ResponseEntity<byte[]> response = controller.transactions(null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(seededRows(response)).hasSize(SEEDED_ROWS);
    }

    @Test
    @DisplayName("applies the date range, so an export matches the filtered view on screen")
    void appliesDateRange() throws IOException {
        ResponseEntity<byte[]> response =
                controller.transactions(null, null, null, "2026-06-01", "2026-06-30");

        List<CSVRecord> rows = seededRows(response);
        assertThat(rows).hasSize(SEEDED_ROWS - 100);
        assertThat(rows).allSatisfy(r -> assertThat(r.get("date")).startsWith("2026-06"));
    }

    @Test
    @DisplayName("applies the category filter")
    void appliesCategoryFilter() throws IOException {
        assertThat(seededRows(controller.transactions("Groceries", null, null, null, null)))
                .hasSize(SEEDED_ROWS);
        assertThat(seededRows(controller.transactions("Travel", null, null, null, null)))
                .isEmpty();
    }

    @Test
    @DisplayName("serves a dated .csv attachment")
    void sendsAttachmentHeaders() {
        ResponseEntity<byte[]> response = controller.transactions(null, null, null, null, null);

        HttpHeaders headers = response.getHeaders();
        assertThat(headers.getContentType().toString()).startsWith("text/csv");
        assertThat(headers.getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("transactions-" + LocalDate.now() + ".csv");
    }

    // --- summary exports --------------------------------------------------------

    @Test
    @DisplayName("category export totals the seeded spend under its category")
    void exportsCategoryTotals() throws IOException {
        ResponseEntity<byte[]> response = controller.categories(null, "2026-05-01", "2026-06-30");

        CSVRecord groceries = parse(response).stream()
                .filter(r -> r.get("category").equals("Groceries"))
                .findFirst().orElseThrow();

        double expected = 0;
        for (int i = 0; i < SEEDED_ROWS; i++) expected += 10.00 + i;

        assertThat(Double.parseDouble(groceries.get("total"))).isEqualTo(expected);
        assertThat(Integer.parseInt(groceries.get("transactions"))).isEqualTo(SEEDED_ROWS);
    }

    @Test
    @DisplayName("monthly export has a row per month in the range")
    void exportsMonthlyTotals() throws IOException {
        ResponseEntity<byte[]> response = controller.monthly(null, "2026-05-01", "2026-06-30");

        List<String> months = parse(response).stream().map(r -> r.get("month")).toList();
        assertThat(months).contains("2026-05", "2026-06");
    }

    // --- forecast exports -------------------------------------------------------

    @Test
    @DisplayName("forecast exports are a valid empty table when nothing has been generated")
    void forecastExportsSurviveNoForecast() throws IOException {
        // No API key in tests, so no forecast has ever been generated — the case a first-time
        // user is in, and the one where a null payload would throw.
        ResponseEntity<byte[]> predictions = controller.predictions(null);
        ResponseEntity<byte[]> recommendations = controller.recommendations(null);

        assertThat(predictions.getStatusCode().value()).isEqualTo(200);
        assertThat(recommendations.getStatusCode().value()).isEqualTo(200);
        assertThat(parse(predictions)).isEmpty();
        assertThat(parse(recommendations)).isEmpty();
        assertThat(new String(predictions.getBody(), StandardCharsets.UTF_8))
                .contains("predicted_next_month");
    }

    @Test
    @DisplayName("forecast exports are named and typed like the rest")
    void forecastAttachmentHeaders() {
        assertThat(controller.predictions(null).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("attachment")
                .contains("predictions-" + LocalDate.now() + ".csv");
        assertThat(controller.recommendations(null).getHeaders().getFirst(HttpHeaders.CONTENT_DISPOSITION))
                .contains("recommendations-" + LocalDate.now() + ".csv");
    }

    @Test
    @DisplayName("rejects a malformed date instead of silently exporting everything")
    void rejectsBadDates() {
        assertThat(org.assertj.core.api.Assertions.catchThrowable(
                () -> controller.transactions(null, null, null, "not-a-date", null)))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

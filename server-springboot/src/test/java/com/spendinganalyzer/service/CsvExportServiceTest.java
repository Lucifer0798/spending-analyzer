package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.model.Transaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CsvExportServiceTest {

    private final CsvExportService service = new CsvExportService();

    private static Transaction transaction(
            String date, String description, double amount, String type, String category, String account) {
        return new Transaction(
                1L, date, description, amount, type, category, "ai", "batch", "2026-08-01", 1L, account);
    }

    /** Reads an export back the way a spreadsheet would, so escaping is exercised rather than assumed. */
    private static List<CSVRecord> parse(byte[] csv) throws IOException {
        String text = new String(csv, StandardCharsets.UTF_8);
        // Strip the BOM; it is verified separately and would otherwise land in the first header name.
        if (text.startsWith("\uFEFF")) text = text.substring(1);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader().setSkipHeaderRecord(true).get();
        try (CSVParser parser = format.parse(new StringReader(text))) {
            return parser.getRecords();
        }
    }

    // --- transactions -----------------------------------------------------------

    @Test
    @DisplayName("starts with a UTF-8 BOM so Excel does not mangle non-ASCII merchants")
    void writesByteOrderMark() throws IOException {
        byte[] csv = service.transactions(List.of(
                transaction("2026-06-01", "CAFÉ MÜNCHEN", 12.50, "debit", "Dining & Coffee", "Visa")));

        assertThat(csv[0]).isEqualTo((byte) 0xEF);
        assertThat(csv[1]).isEqualTo((byte) 0xBB);
        assertThat(csv[2]).isEqualTo((byte) 0xBF);
        assertThat(parse(csv).get(0).get("description")).isEqualTo("CAFÉ MÜNCHEN");
    }

    @Test
    @DisplayName("signs the amount by direction so a spreadsheet SUM is meaningful")
    void signsAmountsByType() throws IOException {
        byte[] csv = service.transactions(List.of(
                transaction("2026-06-01", "GROCERY RUN", 40.00, "debit", "Groceries", "Visa"),
                transaction("2026-06-02", "PAYDAY", 2000.00, "credit", "Income", "Visa")));

        List<CSVRecord> rows = parse(csv);
        // The stored amount stays positive; direction lives in its own column, as in the database.
        assertThat(rows.get(0).get("amount")).isEqualTo("40.0");
        assertThat(rows.get(0).get("signed_amount")).isEqualTo("-40.0");
        assertThat(rows.get(1).get("signed_amount")).isEqualTo("2000.0");
    }

    @Test
    @DisplayName("escapes commas and quotes in descriptions")
    void escapesSeparatorsInText() throws IOException {
        byte[] csv = service.transactions(List.of(
                transaction("2026-06-01", "SMITH, JONES & CO \"THE SHOP\"", 10.00, "debit", "Shopping", "Visa")));

        assertThat(parse(csv).get(0).get("description")).isEqualTo("SMITH, JONES & CO \"THE SHOP\"");
    }

    @Test
    @DisplayName("writes an uncategorized transaction as a blank cell, not the text 'null'")
    void rendersMissingValuesAsEmpty() throws IOException {
        byte[] csv = service.transactions(List.of(
                transaction("2026-06-01", "MYSTERY CHARGE", 5.00, "debit", null, null)));

        CSVRecord row = parse(csv).get(0);
        assertThat(row.get("category")).isEmpty();
        assertThat(row.get("account")).isEmpty();
    }

    @Test
    @DisplayName("writes a header row even when there is nothing to export")
    void writesHeaderForEmptyExport() throws IOException {
        byte[] csv = service.transactions(List.of());

        assertThat(parse(csv)).isEmpty();
        assertThat(new String(csv, StandardCharsets.UTF_8)).contains("date", "signed_amount");
    }

    // --- category totals --------------------------------------------------------

    @Test
    @DisplayName("computes each category's share of total spend")
    void computesCategoryShare() throws IOException {
        byte[] csv = service.categoryTotals(List.of(
                new CategoryTotal("Groceries", 750.00, 30),
                new CategoryTotal("Travel", 250.00, 2)));

        List<CSVRecord> rows = parse(csv);
        assertThat(rows.get(0).get("share_percent")).isEqualTo("75.0");
        assertThat(rows.get(1).get("share_percent")).isEqualTo("25.0");
        assertThat(rows.get(0).get("transactions")).isEqualTo("30");
    }

    @Test
    @DisplayName("does not divide by zero when every total is zero")
    void survivesZeroTotals() throws IOException {
        byte[] csv = service.categoryTotals(List.of(new CategoryTotal("Groceries", 0.0, 0)));

        assertThat(parse(csv).get(0).get("share_percent")).isEqualTo("0.0");
    }

    // --- monthly totals ---------------------------------------------------------

    @Test
    @DisplayName("writes one row per month in the order given")
    void writesMonthlyTotals() throws IOException {
        byte[] csv = service.monthlyTotals(List.of(
                new MonthlyTotal("2026-05", 1200.50),
                new MonthlyTotal("2026-06", 980.25)));

        List<CSVRecord> rows = parse(csv);
        assertThat(rows).hasSize(2);
        assertThat(rows.get(0).get("month")).isEqualTo("2026-05");
        assertThat(rows.get(1).get("total")).isEqualTo("980.25");
    }
}

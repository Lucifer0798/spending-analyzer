package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import com.spendinganalyzer.model.Transaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FileParsingServiceTest {

    private final FileParsingService service = new FileParsingService();

    private List<ParsedTransaction> parse(String csv) throws IOException {
        return service.parseCsv(new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("negative amounts are debits, positive are credits")
    void infersDirectionFromSign() throws IOException {
        var result = parse("""
                Date,Description,Amount
                2026-05-01,COFFEE SHOP,-4.50
                2026-05-02,SALARY,2400.00
                """);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("debit");
        assertThat(result.get(0).amount()).isEqualTo(4.50);   // stored unsigned
        assertThat(result.get(1).type()).isEqualTo("credit");
        assertThat(result.get(1).amount()).isEqualTo(2400.00);
    }

    @Test
    @DisplayName("accepts ISO, US, and long-form dates")
    void parsesCommonDateFormats() throws IOException {
        var result = parse("""
                Date,Description,Amount
                2026-05-01,A,-1.00
                5/2/2026,B,-1.00
                05/03/2026,C,-1.00
                "May 4, 2026",D,-1.00
                """);

        assertThat(result).extracting(ParsedTransaction::date)
                .containsExactly("2026-05-01", "2026-05-02", "2026-05-03", "2026-05-04");
    }

    @Test
    @DisplayName("recognises alternative header names")
    void detectsAlternativeHeaders() throws IOException {
        var result = parse("""
                Transaction Date,Merchant,Transaction Amount
                2026-05-01,CORNER SHOP,-12.34
                """);

        assertThat(result).singleElement().satisfies(t -> {
            assertThat(t.description()).isEqualTo("CORNER SHOP");
            assertThat(t.amount()).isEqualTo(12.34);
        });
    }

    @Test
    @DisplayName("handles separate debit and credit columns")
    void handlesSeparateDebitAndCreditColumns() throws IOException {
        var result = parse("""
                Date,Description,Debit,Credit
                2026-05-01,RENT,1200.00,
                2026-05-02,REFUND,,45.00
                """);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("debit");
        assertThat(result.get(0).amount()).isEqualTo(1200.00);
        assertThat(result.get(1).type()).isEqualTo("credit");
        assertThat(result.get(1).amount()).isEqualTo(45.00);
    }

    @Test
    @DisplayName("strips currency symbols and thousands separators")
    void stripsCurrencyFormatting() throws IOException {
        var result = parse("""
                Date,Description,Amount
                2026-05-01,BIG PURCHASE,"-$1,234.56"
                """);

        assertThat(result).singleElement()
                .extracting(ParsedTransaction::amount).isEqualTo(1234.56);
    }

    @Test
    @DisplayName("reads parenthesised amounts as negative")
    void treatsParenthesesAsNegative() throws IOException {
        // Accounting exports write negatives as (123.45).
        var result = parse("""
                Date,Description,Amount
                2026-05-01,ACCOUNTING STYLE,(99.99)
                """);

        assertThat(result).singleElement().satisfies(t -> {
            assertThat(t.type()).isEqualTo("debit");
            assertThat(t.amount()).isEqualTo(99.99);
        });
    }

    @Test
    @DisplayName("picks up a category column when the export has one")
    void readsOptionalCategoryColumn() throws IOException {
        var result = parse("""
                Date,Description,Amount,Category
                2026-05-01,SHOP,-10.00,Groceries
                2026-05-02,SHOP,-10.00,
                """);

        assertThat(result.get(0).category()).isEqualTo("Groceries");
        assertThat(result.get(1).category()).isNull();
    }

    @Test
    @DisplayName("skips unusable rows instead of failing the whole import")
    void skipsUnparseableRows() throws IOException {
        var result = parse("""
                Date,Description,Amount
                2026-05-01,GOOD ROW,-10.00
                not-a-date,BAD DATE,-10.00
                2026-05-03,,-10.00
                2026-05-04,ZERO AMOUNT,0.00
                2026-05-05,ANOTHER GOOD ROW,-20.00
                """);

        assertThat(result).extracting(ParsedTransaction::description)
                .containsExactly("GOOD ROW", "ANOTHER GOOD ROW");
    }

    @Test
    @DisplayName("reports which headers it saw when required columns are missing")
    void failsHelpfullyOnUnrecognisedColumns() {
        assertThatThrownBy(() -> parse("""
                Foo,Bar,Baz
                1,2,3
                """))
                .isInstanceOf(FileParsingService.ParseException.class)
                .hasMessageContaining("Could not detect required columns")
                .hasMessageContaining("Foo");
    }

    @Test
    @DisplayName("an empty file yields no transactions rather than an error")
    void handlesEmptyFile() throws IOException {
        assertThat(parse("Date,Description,Amount\n")).isEmpty();
    }

    // --- column mapping override --------------------------------------------------

    @Test
    @DisplayName("a column-detection failure carries the headers and whatever it did manage to guess")
    void columnDetectionFailureCarriesPartialResult() {
        String csv = """
                Fecha,Descripcion,Importe
                2026-05-01,TIENDA,-12.34
                """;

        assertThatThrownBy(() -> parse(csv))
                .isInstanceOf(FileParsingService.ColumnDetectionException.class)
                .satisfies(e -> {
                    var result = ((FileParsingService.ColumnDetectionException) e).result;
                    assertThat(result.headers()).containsExactly("Fecha", "Descripcion", "Importe");
                    assertThat(result.dateColumn()).isNull();
                    assertThat(result.descriptionColumn()).isNull();
                    assertThat(result.amountColumn()).isNull();
                });
    }

    @Test
    @DisplayName("an explicit full mapping parses a file auto-detection can't")
    void explicitMappingParsesUnrecognisedHeaders() throws IOException {
        var mapping = new FileParsingService.ColumnMapping("Fecha", "Descripcion", "Importe", null, null, null);
        var result = service.parseCsv(
                new ByteArrayInputStream("""
                        Fecha,Descripcion,Importe
                        2026-05-01,TIENDA,-12.34
                        """.getBytes(StandardCharsets.UTF_8)),
                mapping);

        assertThat(result).singleElement().satisfies(t -> {
            assertThat(t.date()).isEqualTo("2026-05-01");
            assertThat(t.description()).isEqualTo("TIENDA");
            assertThat(t.amount()).isEqualTo(12.34);
            assertThat(t.type()).isEqualTo("debit");
        });
    }

    @Test
    @DisplayName("a partial mapping only overrides the column it names, auto-detecting the rest")
    void partialMappingMergesWithAutoDetection() throws IOException {
        // Description and Amount are ordinary headers auto-detection already understands;
        // only the date column has a name it doesn't recognise.
        var mapping = new FileParsingService.ColumnMapping("Fecha", null, null, null, null, null);
        var result = service.parseCsv(
                new ByteArrayInputStream("""
                        Fecha,Description,Amount
                        2026-05-01,CORNER SHOP,-12.34
                        """.getBytes(StandardCharsets.UTF_8)),
                mapping);

        assertThat(result).singleElement().extracting(ParsedTransaction::date).isEqualTo("2026-05-01");
    }

    @Test
    @DisplayName("an explicit mapping to separate debit/credit columns works the same as auto-detected ones")
    void explicitMappingHandlesDebitCreditColumns() throws IOException {
        var mapping = new FileParsingService.ColumnMapping("Fecha", "Descripcion", null, "Cargo", "Abono", null);
        var result = service.parseCsv(
                new ByteArrayInputStream("""
                        Fecha,Descripcion,Cargo,Abono
                        2026-05-01,RENT,1200.00,
                        2026-05-02,REFUND,,45.00
                        """.getBytes(StandardCharsets.UTF_8)),
                mapping);

        assertThat(result).hasSize(2);
        assertThat(result.get(0).type()).isEqualTo("debit");
        assertThat(result.get(1).type()).isEqualTo("credit");
    }

    // --- re-importing this app's own exports ------------------------------------

    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    private static byte[] withBom(String csv) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);
        out.writeBytes(csv.getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    private static byte[] exportedTransactions() {
        return new CsvExportService().transactions(List.of(
                new Transaction(1L, "2026-05-01", "COFFEE SHOP", 4.50, "debit", "Dining", "ai", "batch",
                        "2026-05-02", 1L, "Checking", "USD", null, null),
                new Transaction(2L, "2026-05-02", "SALARY", 2400.00, "credit", "Income", "ai", "batch",
                        "2026-05-02", 1L, "Checking", "USD", null, null)));
    }

    @Test
    @DisplayName("a transactions export from this app re-imports despite its byte-order mark")
    void reimportsOwnExportDespiteByteOrderMark() throws IOException {
        byte[] exported = exportedTransactions();
        assertThat(exported).startsWith(UTF8_BOM);

        var result = service.parseCsv(new ByteArrayInputStream(exported));

        assertThat(result).extracting(ParsedTransaction::date).containsExactly("2026-05-01", "2026-05-02");
        assertThat(result).extracting(ParsedTransaction::description).containsExactly("COFFEE SHOP", "SALARY");
        assertThat(result).extracting(ParsedTransaction::category).containsExactly("Dining", "Income");
    }

    @Test
    @DisplayName("a re-imported export keeps each transaction's direction, not just its size")
    void reimportedExportKeepsDirection() throws IOException {
        // The export's "amount" column is unsigned (direction lives in "type"); reading it as a
        // signed amount would turn every debit into a credit.
        var result = service.parseCsv(new ByteArrayInputStream(exportedTransactions()));

        assertThat(result).extracting(ParsedTransaction::type).containsExactly("debit", "credit");
        assertThat(result).extracting(ParsedTransaction::amount).containsExactly(4.50, 2400.00);
    }

    @Test
    @DisplayName("a byte-order mark never reaches the header names offered on the mapping form")
    void columnDetectionHeadersOmitByteOrderMark() {
        byte[] csv = withBom("""
                Fecha,Descripcion,Importe
                2026-05-01,TIENDA,-12.34
                """);

        assertThatThrownBy(() -> service.parseCsv(new ByteArrayInputStream(csv)))
                .isInstanceOf(FileParsingService.ColumnDetectionException.class)
                .satisfies(e -> assertThat(((FileParsingService.ColumnDetectionException) e).result.headers())
                        .containsExactly("Fecha", "Descripcion", "Importe"));
    }

    @Test
    @DisplayName("a mapped column name from the form matches the first header of a file with a byte-order mark")
    void explicitMappingMatchesFirstHeaderBehindByteOrderMark() throws IOException {
        var mapping = new FileParsingService.ColumnMapping("Fecha", "Descripcion", "Importe", null, null, null);
        var result = service.parseCsv(
                new ByteArrayInputStream(withBom("""
                        Fecha,Descripcion,Importe
                        2026-05-01,TIENDA,-12.34
                        """)),
                mapping);

        assertThat(result).singleElement().satisfies(t -> {
            assertThat(t.date()).isEqualTo("2026-05-01");
            assertThat(t.type()).isEqualTo("debit");
        });
    }

    @Test
    @DisplayName("a mapped column name that still carries a byte-order mark matches the stripped header")
    void explicitMappingNameIsStrippedOfByteOrderMark() throws IOException {
        String bom = String.valueOf((char) 0xFEFF);
        var mapping = new FileParsingService.ColumnMapping(bom + "Fecha", "Descripcion", "Importe", null, null, null);
        var result = service.parseCsv(
                new ByteArrayInputStream(withBom("""
                        Fecha,Descripcion,Importe
                        2026-05-01,TIENDA,-12.34
                        """)),
                mapping);

        assertThat(result).singleElement().extracting(ParsedTransaction::date).isEqualTo("2026-05-01");
    }
}

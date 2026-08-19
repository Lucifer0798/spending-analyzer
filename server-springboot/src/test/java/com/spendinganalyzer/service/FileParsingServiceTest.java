package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
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
}

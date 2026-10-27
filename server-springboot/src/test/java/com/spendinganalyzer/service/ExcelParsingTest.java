package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.apache.poi.hssf.usermodel.HSSFWorkbook;
import org.apache.poi.ss.usermodel.CellStyle;
import org.apache.poi.ss.usermodel.CreationHelper;
import org.apache.poi.ss.usermodel.Row;
import org.apache.poi.ss.usermodel.Sheet;
import org.apache.poi.ss.usermodel.Workbook;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Date;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Excel statements, built in memory the way a bank's export lays them out: real date cells, numbers
 * with formats, the odd formula. Every case runs as .xlsx and as legacy .xls, which go through
 * different halves of Apache POI -- these are what an upgrade of it has to keep passing.
 */
class ExcelParsingTest {

    private final FileParsingService parser = new FileParsingService();

    private static Workbook workbook(String format) {
        return format.equals("xlsx") ? new XSSFWorkbook() : new HSSFWorkbook();
    }

    private static byte[] bytes(Workbook wb) throws IOException {
        try (wb; ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            wb.write(out);
            return out.toByteArray();
        }
    }

    private static Date date(int year, int month, int day) {
        return Date.from(LocalDate.of(year, month, day).atStartOfDay().toInstant(ZoneOffset.UTC));
    }

    private List<ParsedTransaction> parse(byte[] file) throws IOException {
        return parser.parseExcel(new ByteArrayInputStream(file));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"xlsx", "xls"})
    @DisplayName("date cells, formatted and negative amounts, a formula, a text date, and a blank row")
    void typicalStatement(String format) throws IOException {
        Workbook wb = workbook(format);
        CreationHelper helper = wb.getCreationHelper();
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(helper.createDataFormat().getFormat("dd/mm/yyyy"));
        CellStyle moneyStyle = wb.createCellStyle();
        moneyStyle.setDataFormat(helper.createDataFormat().getFormat("#,##0.00"));

        Sheet sheet = wb.createSheet("Statement");
        Row header = sheet.createRow(0);
        header.createCell(0).setCellValue("Date");
        header.createCell(1).setCellValue("Description");
        header.createCell(2).setCellValue("Amount");

        Row r1 = sheet.createRow(1);
        r1.createCell(0).setCellValue(date(2026, 5, 1));
        r1.getCell(0).setCellStyle(dateStyle);
        r1.createCell(1).setCellValue("COFFEE SHOP");
        r1.createCell(2).setCellValue(-4.5);

        Row r2 = sheet.createRow(2);
        r2.createCell(0).setCellValue(date(2026, 5, 2));
        r2.getCell(0).setCellStyle(dateStyle);
        r2.createCell(1).setCellValue("SALARY");
        r2.createCell(2).setCellValue(2400);
        r2.getCell(2).setCellStyle(moneyStyle);   // shows as 2,400.00

        Row r3 = sheet.createRow(3);
        r3.createCell(0).setCellValue("2026-05-03");   // a date typed as text
        r3.createCell(1).setCellValue("SPLIT BILL");
        r3.createCell(2).setCellFormula("-10-2.5");

        // Row 4 left out entirely; row 5 follows the gap.
        Row r5 = sheet.createRow(5);
        r5.createCell(0).setCellValue(date(2026, 5, 31));
        r5.getCell(0).setCellStyle(dateStyle);
        r5.createCell(1).setCellValue("  RENT  ");
        r5.createCell(2).setCellValue(-1234.56);
        r5.getCell(2).setCellStyle(moneyStyle);   // shows as -1,234.56

        assertThat(parse(bytes(wb))).containsExactly(
                new ParsedTransaction("2026-05-01", "COFFEE SHOP", 4.5, "debit", null),
                new ParsedTransaction("2026-05-02", "SALARY", 2400.0, "credit", null),
                new ParsedTransaction("2026-05-03", "SPLIT BILL", 12.5, "debit", null),
                new ParsedTransaction("2026-05-31", "RENT", 1234.56, "debit", null));
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = {"xlsx", "xls"})
    @DisplayName("separate debit and credit columns, a category column, and a blank trailing header")
    void debitCreditColumns(String format) throws IOException {
        Workbook wb = workbook(format);
        CellStyle dateStyle = wb.createCellStyle();
        dateStyle.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("yyyy-mm-dd"));

        Sheet sheet = wb.createSheet();
        Row header = sheet.createRow(0);
        String[] names = {"Posted Date", "Payee", "Withdrawal", "Deposit", "Category", ""};
        for (int i = 0; i < names.length; i++) header.createCell(i).setCellValue(names[i]);

        Row out = sheet.createRow(1);
        out.createCell(0).setCellValue(date(2026, 6, 10));
        out.getCell(0).setCellStyle(dateStyle);
        out.createCell(1).setCellValue("GROCER");
        out.createCell(2).setCellValue(82.15);
        out.createCell(4).setCellValue("Groceries");

        Row in = sheet.createRow(2);
        in.createCell(0).setCellValue(date(2026, 6, 11));
        in.getCell(0).setCellStyle(dateStyle);
        in.createCell(1).setCellValue("REFUND");
        in.createCell(3).setCellValue(15);
        in.createCell(5).setCellValue("ignored -- no header");

        assertThat(parse(bytes(wb))).containsExactly(
                new ParsedTransaction("2026-06-10", "GROCER", 82.15, "debit", "Groceries"),
                new ParsedTransaction("2026-06-11", "REFUND", 15.0, "credit", null));
    }
}

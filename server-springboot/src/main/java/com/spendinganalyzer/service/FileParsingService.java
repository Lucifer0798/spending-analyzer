package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import org.apache.poi.ss.usermodel.*;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class FileParsingService {

    private static final Pattern DATE_HEADERS =
            Pattern.compile("^(date|transaction date|posted date|posting date)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DESC_HEADERS =
            Pattern.compile("^(description|memo|payee|name|merchant|details)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern AMOUNT_HEADERS =
            Pattern.compile("^(amount|transaction amount)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern DEBIT_HEADERS =
            Pattern.compile("^(debit|withdrawal|amount debit)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CREDIT_HEADERS =
            Pattern.compile("^(credit|deposit|amount credit)$", Pattern.CASE_INSENSITIVE);
    private static final Pattern CATEGORY_HEADERS =
            Pattern.compile("^(category|type of transaction)$", Pattern.CASE_INSENSITIVE);

    private static final List<DateTimeFormatter> FALLBACK_DATE_FORMATS = List.of(
            DateTimeFormatter.ofPattern("M/d/yyyy"),
            DateTimeFormatter.ofPattern("MM-dd-yyyy"),
            DateTimeFormatter.ofPattern("yyyy/M/d"),
            DateTimeFormatter.ofPattern("MMM d, yyyy"),
            DateTimeFormatter.ofPattern("MMMM d, yyyy"),
            DateTimeFormatter.ofPattern("d MMM yyyy")
    );

    public static class ParseException extends RuntimeException {
        public ParseException(String message) {
            super(message);
        }
    }

    public List<ParsedTransaction> parseCsv(InputStream input) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();
        CSVFormat format = CSVFormat.DEFAULT.builder()
                .setHeader()
                .setSkipHeaderRecord(true)
                .setIgnoreSurroundingSpaces(true)
                .setTrim(true)
                .get();

        try (InputStreamReader reader = new InputStreamReader(input, StandardCharsets.UTF_8);
             CSVParser parser = format.parse(reader)) {
            List<String> headers = new ArrayList<>(parser.getHeaderNames());
            for (CSVRecord record : parser) {
                Map<String, String> row = new LinkedHashMap<>();
                for (String header : headers) {
                    row.put(header, record.isSet(header) ? record.get(header) : "");
                }
                rows.add(row);
            }
        }

        return rowsToTransactions(rows);
    }

    public List<ParsedTransaction> parseExcel(InputStream input) throws IOException {
        List<Map<String, String>> rows = new ArrayList<>();

        try (Workbook workbook = WorkbookFactory.create(input)) {
            Sheet sheet = workbook.getSheetAt(0);
            if (sheet == null) return List.of();

            DataFormatter formatter = new DataFormatter();
            FormulaEvaluator evaluator = workbook.getCreationHelper().createFormulaEvaluator();

            Row headerRow = sheet.getRow(sheet.getFirstRowNum());
            if (headerRow == null) return List.of();

            List<String> headers = new ArrayList<>();
            short lastCol = headerRow.getLastCellNum();
            for (int c = 0; c < lastCol; c++) {
                Cell cell = headerRow.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                headers.add(formatter.formatCellValue(cell).trim());
            }

            for (int r = sheet.getFirstRowNum() + 1; r <= sheet.getLastRowNum(); r++) {
                Row row = sheet.getRow(r);
                if (row == null) continue;

                Map<String, String> obj = new LinkedHashMap<>();
                for (int c = 0; c < headers.size(); c++) {
                    String header = headers.get(c);
                    if (header.isEmpty()) continue;
                    Cell cell = row.getCell(c, Row.MissingCellPolicy.CREATE_NULL_AS_BLANK);
                    obj.put(header, cellToString(cell, formatter, evaluator));
                }
                rows.add(obj);
            }
        }

        return rowsToTransactions(rows);
    }

    private String cellToString(Cell cell, DataFormatter formatter, FormulaEvaluator evaluator) {
        if (cell.getCellType() == CellType.NUMERIC && DateUtil.isCellDateFormatted(cell)) {
            return cell.getLocalDateTimeCellValue().toLocalDate().format(DateTimeFormatter.ISO_LOCAL_DATE);
        }
        try {
            return formatter.formatCellValue(cell, evaluator).trim();
        } catch (Exception e) {
            return formatter.formatCellValue(cell).trim();
        }
    }

    private List<ParsedTransaction> rowsToTransactions(List<Map<String, String>> rows) {
        if (rows.isEmpty()) return List.of();

        List<String> headers = new ArrayList<>(rows.get(0).keySet());

        String dateCol = findColumn(headers, DATE_HEADERS);
        String descCol = findColumn(headers, DESC_HEADERS);
        String amountCol = findColumn(headers, AMOUNT_HEADERS);
        String debitCol = findColumn(headers, DEBIT_HEADERS);
        String creditCol = findColumn(headers, CREDIT_HEADERS);
        String categoryCol = findColumn(headers, CATEGORY_HEADERS);

        if (dateCol == null || descCol == null || (amountCol == null && debitCol == null && creditCol == null)) {
            throw new ParseException(
                    "Could not detect required columns (date, description, amount). Found headers: "
                            + String.join(", ", headers));
        }

        List<ParsedTransaction> results = new ArrayList<>();

        for (Map<String, String> row : rows) {
            String dateRaw = row.getOrDefault(dateCol, "");
            String descRaw = row.getOrDefault(descCol, "");
            if (dateRaw.isBlank() || descRaw.isBlank()) continue;

            String date = parseDate(dateRaw);
            if (date == null) continue;

            double amount;
            String type;

            if (amountCol != null) {
                double raw = parseAmount(row.getOrDefault(amountCol, "0"));
                type = raw < 0 ? "debit" : "credit";
                amount = Math.abs(raw);
            } else {
                double debitVal = debitCol != null ? parseAmount(row.getOrDefault(debitCol, "0")) : 0;
                double creditVal = creditCol != null ? parseAmount(row.getOrDefault(creditCol, "0")) : 0;
                if (Math.abs(debitVal) > 0) {
                    amount = Math.abs(debitVal);
                    type = "debit";
                } else {
                    amount = Math.abs(creditVal);
                    type = "credit";
                }
            }

            if (amount == 0) continue;

            String category = null;
            if (categoryCol != null) {
                String c = row.getOrDefault(categoryCol, "").trim();
                category = c.isEmpty() ? null : c;
            }

            results.add(new ParsedTransaction(date, descRaw.trim(), amount, type, category));
        }

        return results;
    }

    private static String findColumn(List<String> headers, Pattern pattern) {
        return headers.stream()
                .filter(h -> pattern.matcher(h.trim()).matches())
                .findFirst()
                .orElse(null);
    }

    private static String parseDate(String raw) {
        String trimmed = raw.trim();
        if (trimmed.isEmpty()) return null;

        Matcher iso = Pattern.compile("^(\\d{4})-(\\d{1,2})-(\\d{1,2})").matcher(trimmed);
        if (iso.find()) {
            return "%s-%s-%s".formatted(
                    iso.group(1),
                    iso.group(2).length() == 1 ? "0" + iso.group(2) : iso.group(2),
                    iso.group(3).length() == 1 ? "0" + iso.group(3) : iso.group(3)
            );
        }

        Matcher us = Pattern.compile("^(\\d{1,2})/(\\d{1,2})/(\\d{4})$").matcher(trimmed);
        if (us.matches()) {
            return "%s-%s-%s".formatted(
                    us.group(3),
                    us.group(1).length() == 1 ? "0" + us.group(1) : us.group(1),
                    us.group(2).length() == 1 ? "0" + us.group(2) : us.group(2)
            );
        }

        for (DateTimeFormatter fmt : FALLBACK_DATE_FORMATS) {
            try {
                LocalDate parsed = LocalDate.parse(trimmed, fmt);
                return parsed.format(DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException ignored) {
                // try next format
            }
        }

        return null;
    }

    private static double parseAmount(String raw) {
        if (raw == null) return 0;
        String cleaned = raw.replaceAll("[$,\\s]", "").replaceAll("^\\((.*)\\)$", "-$1");
        try {
            return Double.parseDouble(cleaned);
        } catch (NumberFormatException e) {
            return 0;
        }
    }
}

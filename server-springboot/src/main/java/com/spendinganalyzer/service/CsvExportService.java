package com.spendinganalyzer.service;

import com.spendinganalyzer.dto.CategoryTotal;
import com.spendinganalyzer.dto.MonthlyTotal;
import com.spendinganalyzer.model.Transaction;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Renders the data behind the dashboard and the transactions table as CSV.
 *
 * <p>These files are meant to be opened in a spreadsheet, which drives two decisions that would
 * look odd otherwise: the byte-order mark, and the signed amount column.
 */
@Service
public class CsvExportService {

    /**
     * Excel reads a CSV in the operating system's codepage unless a byte-order mark says
     * otherwise, which turns any non-ASCII merchant name into mojibake. Three bytes here save
     * the user from having to walk the import wizard to pick an encoding by hand.
     */
    private static final byte[] UTF8_BOM = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};

    /** Lets the row-writing lambdas below throw, which {@link java.util.function.Consumer} cannot. */
    @FunctionalInterface
    private interface RowWriter {
        void writeTo(CSVPrinter printer) throws IOException;
    }

    private static byte[] toCsv(String[] header, RowWriter rows) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.writeBytes(UTF8_BOM);

        CSVFormat format = CSVFormat.DEFAULT.builder().setHeader(header).get();
        try (CSVPrinter printer = new CSVPrinter(new OutputStreamWriter(out, StandardCharsets.UTF_8), format)) {
            rows.writeTo(printer);
        } catch (IOException e) {
            // Writing to memory cannot fail for any reason the caller could act on.
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }

    /**
     * One row per transaction.
     *
     * <p>{@code amount} is the stored value, which is always positive because direction is
     * carried by {@code type}. That makes a naive SUM over the column wrong, so
     * {@code signed_amount} is included alongside it: negative for debits, positive for credits.
     */
    public byte[] transactions(List<Transaction> transactions) {
        String[] header = {
                "date", "description", "category", "type",
                "amount", "signed_amount", "account", "category_source"
        };
        return toCsv(header, printer -> {
            for (Transaction t : transactions) {
                printer.printRecord(
                        t.date(),
                        t.description(),
                        t.category() == null ? "" : t.category(),
                        t.type(),
                        t.amount(),
                        "debit".equals(t.type()) ? -t.amount() : t.amount(),
                        t.accountName() == null ? "" : t.accountName(),
                        t.categorySource() == null ? "" : t.categorySource()
                );
            }
        });
    }

    /**
     * Spend per category, matching the dashboard's category chart. The share column is what the
     * chart conveys visually and is tedious to recompute in a spreadsheet from a filtered export.
     */
    public byte[] categoryTotals(List<CategoryTotal> totals) {
        double grandTotal = totals.stream().mapToDouble(CategoryTotal::total).sum();

        return toCsv(new String[]{"category", "total", "transactions", "share_percent"}, printer -> {
            for (CategoryTotal c : totals) {
                double share = grandTotal == 0 ? 0 : (c.total() / grandTotal) * 100;
                printer.printRecord(c.category(), c.total(), c.count(), round2(share));
            }
        });
    }

    /** Spend per month, matching the dashboard's trend line. */
    public byte[] monthlyTotals(List<MonthlyTotal> totals) {
        return toCsv(new String[]{"month", "total"}, printer -> {
            for (MonthlyTotal m : totals) {
                printer.printRecord(m.month(), m.total());
            }
        });
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}

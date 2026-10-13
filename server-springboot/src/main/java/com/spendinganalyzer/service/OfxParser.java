package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.math.BigDecimal;
import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Reads OFX statements -- what banks offer as "Quicken" (.qfx), "Microsoft Money" (.ofx) or
 * "QuickBooks Web Connect" (.qbo) downloads. Unlike a CSV there's nothing to guess: every
 * transaction has a posted date, a signed amount and the bank's own ID for it (FITID).
 *
 * <p>Two dialects exist: OFX 1.x is SGML, where a value's closing tag is optional
 * ({@code <NAME>COFFEE SHOP} then the next tag), and 2.x is XML. Rather than two parsers, this reads
 * each {@code <STMTTRN>} block and takes every {@code <TAG>value} pair in it up to the next
 * {@code <}, which is the same text in both. Aggregates ({@code <STMTTRN>...</STMTTRN>}) are closed
 * in both dialects, which is what makes the blocks findable.
 */
@Service
public class OfxParser {

    /** What one statement holds, and the currency it states (null when it doesn't say). */
    public record Statement(List<ParsedTransaction> transactions, String currency) {}

    private static final Pattern TRANSACTION = Pattern.compile("<STMTTRN>(.*?)</STMTTRN>",
            Pattern.CASE_INSENSITIVE | Pattern.DOTALL);
    private static final Pattern FIELD = Pattern.compile("<([A-Za-z0-9.]+)>([^<]*)");
    private static final Pattern STATEMENT_START = Pattern.compile("<(?:CC)?STMTRS>", Pattern.CASE_INSENSITIVE);
    private static final Pattern CURRENCY = Pattern.compile("<CURDEF>\\s*([A-Za-z]{3})", Pattern.CASE_INSENSITIVE);
    private static final Pattern NUMERIC_ENTITY = Pattern.compile("&#(x?)([0-9A-Fa-f]+);");

    public Statement parse(InputStream input) throws IOException {
        String text = decode(input.readAllBytes());
        if (!text.toUpperCase(Locale.ROOT).contains("<OFX>")) {
            throw new FileParsingService.ParseException(
                    "This doesn't look like an OFX/QFX statement -- there's no <OFX> section in it.");
        }

        int statements = 0;
        Matcher starts = STATEMENT_START.matcher(text);
        while (starts.find()) statements++;
        if (statements > 1) {
            // Each account here has its own currency and its own duplicate checks, so one upload
            // can't sensibly land in several.
            throw new FileParsingService.ParseException("This file holds statements for " + statements
                    + " accounts. Download one account at a time and import each into its own account here.");
        }

        Matcher currency = CURRENCY.matcher(text);
        String currencyCode = currency.find() ? currency.group(1).toUpperCase(Locale.ROOT) : null;

        List<ParsedTransaction> transactions = new ArrayList<>();
        Matcher block = TRANSACTION.matcher(text);
        while (block.find()) {
            ParsedTransaction t = toTransaction(fields(block.group(1)));
            if (t != null) transactions.add(t);
        }
        return new Statement(transactions, currencyCode);
    }

    /** First value of each tag in the block -- a nested {@code <PAYEE><NAME>} can't override the row's own. */
    private static Map<String, String> fields(String block) {
        Map<String, String> fields = new HashMap<>();
        Matcher m = FIELD.matcher(block);
        while (m.find()) {
            String value = unescape(m.group(2)).trim();
            if (!value.isEmpty()) fields.putIfAbsent(m.group(1).toUpperCase(Locale.ROOT), value);
        }
        return fields;
    }

    /** Null for a row without a usable date or a non-zero amount -- skipped, as a bad CSV row is. */
    private static ParsedTransaction toTransaction(Map<String, String> f) {
        String date = date(f.getOrDefault("DTPOSTED", f.get("DTUSER")));
        BigDecimal amount = amount(f.get("TRNAMT"));
        if (date == null || amount == null || amount.signum() == 0) return null;

        String fitId = f.get("FITID");
        return new ParsedTransaction(
                date,
                description(f.get("NAME"), f.get("MEMO"), f.get("TRNTYPE")),
                amount.abs().doubleValue(),
                // The sign is authoritative; TRNTYPE ("POS", "XFER", "OTHER"...) varies by bank.
                amount.signum() < 0 ? "debit" : "credit",
                null,
                fitId);
    }

    /** {@code YYYYMMDD}, optionally followed by a time and a timezone -- only the calendar date is kept. */
    static String date(String value) {
        if (value == null || value.length() < 8) return null;
        String digits = value.substring(0, 8);
        try {
            return LocalDate.parse(digits.substring(0, 4) + "-" + digits.substring(4, 6) + "-" + digits.substring(6, 8)).toString();
        } catch (DateTimeParseException e) {
            return null;
        }
    }

    /** OFX amounts are plain decimals, but some European banks write the decimal point as a comma. */
    static BigDecimal amount(String value) {
        if (value == null) return null;
        String v = value.replace(" ", "").replace("+", "");
        if (v.contains(",") && !v.contains(".")) v = v.replace(',', '.');
        else v = v.replace(",", "");
        try {
            return new BigDecimal(v);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * NAME is the payee, often cut off at 32 characters; MEMO is where many banks put the rest. Both,
     * when they say different things -- what a CSV export of the same account usually shows -- but
     * never one repeated inside the other.
     */
    static String description(String name, String memo, String type) {
        String text;
        if (name != null && memo != null) {
            String n = name.toUpperCase(Locale.ROOT);
            String m = memo.toUpperCase(Locale.ROOT);
            text = n.contains(m) ? name : m.contains(n) ? memo : name + " - " + memo;
        } else if (name != null) {
            text = name;
        } else if (memo != null) {
            text = memo;
        } else {
            text = type != null ? type : "Transaction";
        }
        return text.replaceAll("\\s+", " ").trim();
    }

    private static String unescape(String value) {
        Matcher m = NUMERIC_ENTITY.matcher(value);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            int codePoint = Integer.parseInt(m.group(2), m.group(1).isEmpty() ? 10 : 16);
            m.appendReplacement(sb, Matcher.quoteReplacement(new String(Character.toChars(codePoint))));
        }
        m.appendTail(sb);
        return sb.toString()
                .replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"")
                .replace("&apos;", "'").replace("&nbsp;", " ").replace("&amp;", "&");
    }

    /**
     * UTF-8 when the bytes are valid UTF-8 (every OFX 2 file, and many 1.x ones), otherwise
     * Windows-1252 -- what OFX 1.x's {@code CHARSET:1252} header means and what most banks send.
     */
    private static String decode(byte[] bytes) {
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(bytes))
                    .toString();
        } catch (CharacterCodingException e) {
            return new String(bytes, Charset.forName("windows-1252"));
        }
    }
}

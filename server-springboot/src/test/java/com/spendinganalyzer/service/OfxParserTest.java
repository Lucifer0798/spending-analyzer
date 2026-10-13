package com.spendinganalyzer.service;

import com.spendinganalyzer.model.ParsedTransaction;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OfxParserTest {

    private final OfxParser parser = new OfxParser();

    /** OFX 1.x: SGML, leaf values with no closing tags, Windows-1252. */
    static final String SGML = """
            OFXHEADER:100
            DATA:OFXSGML
            VERSION:102
            SECURITY:NONE
            ENCODING:USASCII
            CHARSET:1252
            COMPRESSION:NONE
            OLDFILEUID:NONE
            NEWFILEUID:NONE

            <OFX>
            <SIGNONMSGSRSV1><SONRS><STATUS><CODE>0<SEVERITY>INFO</STATUS><DTSERVER>20260930120000<LANGUAGE>ENG</SONRS></SIGNONMSGSRSV1>
            <BANKMSGSRSV1><STMTTRNRS><TRNUID>1<STATUS><CODE>0<SEVERITY>INFO</STATUS>
            <STMTRS><CURDEF>USD
            <BANKACCTFROM><BANKID>123456789<ACCTID>000111222<ACCTTYPE>CHECKING</BANKACCTFROM>
            <BANKTRANLIST><DTSTART>20260901<DTEND>20260930
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260905120000[-5:EST]<TRNAMT>-4.50<FITID>2026090501<NAME>BLUE BOTTLE COFFEE<MEMO>POS PURCHASE</STMTTRN>
            <STMTTRN><TRNTYPE>CREDIT<DTPOSTED>20260915<TRNAMT>2400.00<FITID>2026091502<NAME>ACME CORP PAYROLL</STMTTRN>
            <STMTTRN><TRNTYPE>DEBIT<DTPOSTED>20260920<TRNAMT>-12.99<FITID>2026092003<NAME>AT&amp;T WIRELESS</STMTTRN>
            </BANKTRANLIST>
            <LEDGERBAL><BALAMT>1234.56<DTASOF>20260930</LEDGERBAL>
            </STMTRS></STMTTRNRS></BANKMSGSRSV1>
            </OFX>
            """;

    /** OFX 2.x: XML, a credit card statement, comma decimals, the date only in DTUSER. */
    static final String XML = """
            <?xml version="1.0" encoding="UTF-8"?>
            <?OFX OFXHEADER="200" VERSION="220" SECURITY="NONE" OLDFILEUID="NONE" NEWFILEUID="NONE"?>
            <OFX>
              <CREDITCARDMSGSRSV1><CCSTMTTRNRS><CCSTMTRS>
                <CURDEF>EUR</CURDEF>
                <CCACCTFROM><ACCTID>4111</ACCTID></CCACCTFROM>
                <BANKTRANLIST>
                  <STMTTRN>
                    <TRNTYPE>DEBIT</TRNTYPE>
                    <DTUSER>20260803</DTUSER>
                    <TRNAMT>-19,99</TRNAMT>
                    <FITID>CC-1</FITID>
                    <NAME>AMAZON MKTP</NAME>
                    <MEMO>AMAZON MKTP DE*2K4 LUXEMBOURG</MEMO>
                  </STMTTRN>
                  <STMTTRN>
                    <TRNTYPE>PAYMENT</TRNTYPE>
                    <DTPOSTED>20260810000000</DTPOSTED>
                    <TRNAMT>+250.00</TRNAMT>
                    <FITID>CC-2</FITID>
                    <NAME>PAYMENT - THANK YOU</NAME>
                  </STMTTRN>
                </BANKTRANLIST>
              </CCSTMTRS></CCSTMTTRNRS></CREDITCARDMSGSRSV1>
            </OFX>
            """;

    private OfxParser.Statement parse(String text, Charset charset) throws IOException {
        return parser.parse(new ByteArrayInputStream(text.getBytes(charset)));
    }

    @Test
    @DisplayName("reads OFX 1.x SGML: dates, signed amounts, NAME and MEMO, entities, the bank's IDs and currency")
    void readsSgml() throws IOException {
        OfxParser.Statement s = parse(SGML, StandardCharsets.US_ASCII);

        assertThat(s.currency()).isEqualTo("USD");
        assertThat(s.transactions()).containsExactly(
                new ParsedTransaction("2026-09-05", "BLUE BOTTLE COFFEE - POS PURCHASE", 4.50, "debit", null, "2026090501"),
                new ParsedTransaction("2026-09-15", "ACME CORP PAYROLL", 2400.00, "credit", null, "2026091502"),
                new ParsedTransaction("2026-09-20", "AT&T WIRELESS", 12.99, "debit", null, "2026092003"));
    }

    @Test
    @DisplayName("reads OFX 2.x XML: a card statement, comma decimals, DTUSER, and a MEMO that already holds the NAME")
    void readsXml() throws IOException {
        OfxParser.Statement s = parse(XML, StandardCharsets.UTF_8);

        assertThat(s.currency()).isEqualTo("EUR");
        assertThat(s.transactions()).containsExactly(
                new ParsedTransaction("2026-08-03", "AMAZON MKTP DE*2K4 LUXEMBOURG", 19.99, "debit", null, "CC-1"),
                new ParsedTransaction("2026-08-10", "PAYMENT - THANK YOU", 250.00, "credit", null, "CC-2"));
    }

    @Test
    @DisplayName("a Windows-1252 file keeps its accented characters")
    void decodesWindows1252() throws IOException {
        String text = SGML.replace("BLUE BOTTLE COFFEE", "CAFÉ NOIR");

        OfxParser.Statement s = parse(text, Charset.forName("windows-1252"));

        assertThat(s.transactions().get(0).description()).isEqualTo("CAFÉ NOIR - POS PURCHASE");
    }

    @Test
    @DisplayName("skips rows with no usable date or a zero amount, as a bad CSV row is skipped")
    void skipsUnusableRows() throws IOException {
        String text = SGML
                .replace("<DTPOSTED>20260915", "<DTPOSTED>2026")
                .replace("<TRNAMT>-12.99", "<TRNAMT>0.00");

        assertThat(parse(text, StandardCharsets.US_ASCII).transactions())
                .extracting(ParsedTransaction::externalId).containsExactly("2026090501");
    }

    @Test
    @DisplayName("refuses a file with statements for several accounts, and anything that isn't OFX")
    void refusesMultiAccountAndNonOfx() {
        String twoAccounts = SGML.replace("</STMTRS>", "</STMTRS><STMTRS><CURDEF>USD</STMTRS>");

        assertThatThrownBy(() -> parse(twoAccounts, StandardCharsets.US_ASCII))
                .isInstanceOf(FileParsingService.ParseException.class)
                .hasMessageContaining("2 accounts");
        assertThatThrownBy(() -> parse("Date,Description,Amount\n2026-05-01,COFFEE,-4.50\n", StandardCharsets.UTF_8))
                .isInstanceOf(FileParsingService.ParseException.class)
                .hasMessageContaining("OFX");
    }
}

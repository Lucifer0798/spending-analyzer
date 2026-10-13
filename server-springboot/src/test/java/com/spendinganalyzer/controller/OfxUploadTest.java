package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.DateRange;
import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.dto.UploadResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.Transaction;
import com.spendinganalyzer.repository.AccountRepository;
import com.spendinganalyzer.repository.TransactionRepository;
import com.spendinganalyzer.service.BackupService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Uploading OFX/QFX statements, and how the bank's transaction IDs change duplicate detection. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class OfxUploadTest {

    @Autowired
    private UploadController controller;

    @Autowired
    private TransactionRepository transactions;

    @Autowired
    private AccountRepository accounts;

    @Autowired
    private BackupService backupService;

    private static String statement(String rows) {
        return """
                OFXHEADER:100
                DATA:OFXSGML

                <OFX><BANKMSGSRSV1><STMTTRNRS><STMTRS><CURDEF>USD
                <BANKACCTFROM><ACCTID>1</BANKACCTFROM>
                <BANKTRANLIST>
                """ + rows + """
                </BANKTRANLIST></STMTRS></STMTTRNRS></BANKMSGSRSV1></OFX>
                """;
    }

    private static String row(String fitId, String date, String amount, String name) {
        return "<STMTTRN><TRNTYPE>OTHER<DTPOSTED>" + date + "<TRNAMT>" + amount
                + "<FITID>" + fitId + "<NAME>" + name + "</STMTTRN>\n";
    }

    private UploadResponse upload(String filename, String content, long accountId) {
        ResponseEntity<?> response = controller.upload(
                new MockMultipartFile("file", filename, "application/octet-stream", content.getBytes(StandardCharsets.UTF_8)),
                accountId, true, null, null, null, null, null, null);
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        return (UploadResponse) response.getBody();
    }

    private List<Transaction> all() {
        return transactions.find(null, null, null, DateRange.ALL, 100, 0);
    }

    @Test
    @DisplayName("imports a .qfx, storing the bank's IDs; the same file again imports nothing")
    void importsAndReimportsByBankId() {
        String file = statement(row("A1", "20260905", "-4.50", "COFFEE") + row("A2", "20260915", "2400.00", "PAYROLL"));

        assertThat(upload("checking.qfx", file, Account.DEFAULT_ID).inserted()).isEqualTo(2);
        UploadResponse again = upload("checking.ofx", file, Account.DEFAULT_ID);

        assertThat(again.inserted()).isZero();
        assertThat(again.skippedDuplicates()).isEqualTo(2);
        assertThat(all()).extracting(Transaction::externalId).containsExactlyInAnyOrder("A1", "A2");
    }

    @Test
    @DisplayName("a known ID is a duplicate even when the bank reworded it; a new ID is new even when it reads the same")
    void idBeatsDescription() {
        upload("a.qfx", statement(row("A1", "20260905", "-4.50", "COFFEE")), Account.DEFAULT_ID);

        UploadResponse second = upload("b.qfx", statement(
                row("A1", "20260906", "-4.50", "COFFEE SHOP #12")     // pending -> posted, renamed
                        + row("A2", "20260905", "-4.50", "COFFEE")),  // a second, identical coffee
                Account.DEFAULT_ID);

        assertThat(second.inserted()).isEqualTo(1);
        assertThat(second.skippedDuplicates()).isEqualTo(1);
        assertThat(all()).extracting(Transaction::externalId).containsExactlyInAnyOrder("A1", "A2");
    }

    @Test
    @DisplayName("an OFX overlapping a CSV imported earlier doesn't double the rows it shares")
    void overlapsEarlierCsv() {
        controller.upload(new MockMultipartFile("file", "old.csv", "text/csv",
                        "Date,Description,Amount\n2026-09-05,COFFEE,-4.50\n".getBytes(StandardCharsets.UTF_8)),
                Account.DEFAULT_ID, true, null, null, null, null, null, null);

        UploadResponse ofx = upload("new.qfx", statement(
                row("A1", "20260905", "-4.50", "COFFEE") + row("A2", "20260907", "-9.00", "LUNCH")), Account.DEFAULT_ID);

        assertThat(ofx.inserted()).isEqualTo(1);
        assertThat(ofx.skippedDuplicates()).isEqualTo(1);
    }

    @Test
    @DisplayName("IDs are per account: the same ID in another account is a different transaction")
    void idsArePerAccount() {
        long savings = accounts.create("Savings", "savings", "USD").id();
        String file = statement(row("A1", "20260905", "-4.50", "COFFEE"));

        upload("a.qfx", file, Account.DEFAULT_ID);

        assertThat(upload("a.qfx", file, savings).inserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("refuses a statement in a different currency from the account")
    void refusesCurrencyMismatch() {
        long euro = accounts.create("Euro", "checking", "EUR").id();

        ResponseEntity<?> response = controller.upload(
                new MockMultipartFile("file", "a.qfx", "application/octet-stream",
                        statement(row("A1", "20260905", "-4.50", "COFFEE")).getBytes(StandardCharsets.UTF_8)),
                euro, true, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(((ErrorResponse) response.getBody()).error()).contains("USD").contains("EUR");
    }

    @Test
    @DisplayName("bank IDs survive a backup and restore, so re-imports still recognise them afterwards")
    void idsSurviveBackup() {
        String file = statement(row("A1", "20260905", "-4.50", "COFFEE"));
        upload("a.qfx", file, Account.DEFAULT_ID);

        backupService.restore(backupService.export());

        assertThat(all()).extracting(Transaction::externalId).containsExactly("A1");
        assertThat(upload("a.qfx", file, Account.DEFAULT_ID).inserted()).isZero();
    }
}

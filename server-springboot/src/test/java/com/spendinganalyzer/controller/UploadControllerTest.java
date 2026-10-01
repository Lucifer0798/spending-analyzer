package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ColumnDetectionResult;
import com.spendinganalyzer.dto.UploadResponse;
import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.TransactionRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/** Uploading a statement, including the column-mapping override for files auto-detection can't read. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class UploadControllerTest {

    @Autowired
    private UploadController controller;

    @Autowired
    private TransactionRepository transactions;

    private static MockMultipartFile csv(String content) {
        return new MockMultipartFile("file", "statement.csv", "text/csv", content.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    @DisplayName("parses an ordinary file with no mapping needed")
    void parsesOrdinaryFile() {
        var file = csv("""
                Date,Description,Amount
                2026-05-01,COFFEE SHOP,-4.50
                """);

        ResponseEntity<?> response = controller.upload(file, Account.DEFAULT_ID, true, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((UploadResponse) response.getBody()).inserted()).isEqualTo(1);
    }

    @Test
    @DisplayName("a file with unrecognisable headers returns 422 with the headers and partial guesses")
    void unrecognisedHeadersReturn422() {
        var file = csv("""
                Fecha,Descripcion,Importe
                2026-05-01,TIENDA,-12.34
                """);

        ResponseEntity<?> response = controller.upload(file, Account.DEFAULT_ID, true, null, null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(422);
        var result = (ColumnDetectionResult) response.getBody();
        assertThat(result.headers()).containsExactly("Fecha", "Descripcion", "Importe");
        assertThat(result.dateColumn()).isNull();

        // Nothing was written -- a 422 is a request for more information, not a partial import.
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 10, 0)).isEmpty();
    }

    @Test
    @DisplayName("an explicit column mapping imports a file auto-detection couldn't")
    void explicitMappingImportsFile() {
        var file = csv("""
                Fecha,Descripcion,Importe
                2026-05-01,TIENDA,-12.34
                """);

        ResponseEntity<?> response =
                controller.upload(file, Account.DEFAULT_ID, true, "Fecha", "Descripcion", "Importe", null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((UploadResponse) response.getBody()).inserted()).isEqualTo(1);
        assertThat(transactions.find(null, null, null, com.spendinganalyzer.dto.DateRange.ALL, 10, 0))
                .extracting("description").containsExactly("TIENDA");
    }

    @Test
    @DisplayName("a partial mapping only overrides the column it names")
    void partialMappingOverridesOnlyNamedColumn() {
        var file = csv("""
                Fecha,Description,Amount
                2026-05-01,CORNER SHOP,-12.34
                """);

        ResponseEntity<?> response =
                controller.upload(file, Account.DEFAULT_ID, true, "Fecha", null, null, null, null, null);

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((UploadResponse) response.getBody()).inserted()).isEqualTo(1);
    }
}

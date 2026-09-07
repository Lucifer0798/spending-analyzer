package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.RecurringOverride;
import com.spendinganalyzer.repository.RecurringOverrideRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class RecurringOverrideControllerTest {

    @Autowired
    private RecurringOverrideController controller;

    @Autowired
    private RecurringOverrideRepository repository;

    @Test
    @DisplayName("saves a cancel flag")
    void savesCancelFlag() {
        ResponseEntity<?> response = controller.save(Map.of("merchant_key", "NETFLIX", "action", "cancel"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((RecurringOverride) response.getBody()).action()).isEqualTo("cancel");
    }

    @Test
    @DisplayName("saves an exclude flag")
    void savesExcludeFlag() {
        ResponseEntity<?> response = controller.save(Map.of("merchant_key", "NETFLIX", "action", "exclude"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((RecurringOverride) response.getBody()).action()).isEqualTo("exclude");
    }

    @Test
    @DisplayName("rejects a missing merchant_key")
    void rejectsMissingMerchantKey() {
        ResponseEntity<?> response = controller.save(Map.of("action", "cancel"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an action that isn't cancel or exclude")
    void rejectsInvalidAction() {
        ResponseEntity<?> response = controller.save(Map.of("merchant_key", "NETFLIX", "action", "delete"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(repository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("lists every override")
    void listsOverrides() {
        controller.save(Map.of("merchant_key", "NETFLIX", "action", "cancel"));
        controller.save(Map.of("merchant_key", "SPOTIFY", "action", "exclude"));

        assertThat(controller.list()).hasSize(2);
    }

    @Test
    @DisplayName("clears an override, and reports a missing one as 404")
    void clearsOverride() {
        RecurringOverride saved = repository.upsert("NETFLIX", RecurringOverride.ACTION_CANCEL);

        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(repository.findAll()).isEmpty();
        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(404);
    }
}

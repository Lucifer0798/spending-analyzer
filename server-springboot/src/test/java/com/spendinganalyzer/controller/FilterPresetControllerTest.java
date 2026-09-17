package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.model.FilterPreset;
import com.spendinganalyzer.repository.FilterPresetRepository;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class FilterPresetControllerTest {

    @Autowired
    private FilterPresetController controller;

    @Autowired
    private FilterPresetRepository presets;

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    @Test
    @DisplayName("saves a preset")
    void savesAPreset() {
        ResponseEntity<?> response = controller.save(body(
                "name", "Business trips", "category", "Travel", "tag", "business",
                "account_id", Account.DEFAULT_ID, "date_from", "2026-01-01", "date_to", "2026-12-31"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        FilterPreset saved = (FilterPreset) response.getBody();
        assertThat(saved.name()).isEqualTo("Business trips");
        assertThat(saved.category()).isEqualTo("Travel");
    }

    @Test
    @DisplayName("saves a preset with only a name, everything else left unset")
    void savesAMinimalPreset() {
        ResponseEntity<?> response = controller.save(body("name", "Everything"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(((FilterPreset) response.getBody()).category()).isNull();
    }

    @Test
    @DisplayName("rejects a missing name")
    void rejectsMissingName() {
        assertThat(controller.save(body("category", "Travel")).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an unknown category")
    void rejectsUnknownCategory() {
        ResponseEntity<?> response = controller.save(body("name", "Bad", "category", "Nonsense"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
        assertThat(presets.findAll()).isEmpty();
    }

    @Test
    @DisplayName("rejects an account id that doesn't refer to an existing account")
    void rejectsUnknownAccount() {
        assertThat(controller.save(body("name", "Bad", "account_id", 999_999))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a malformed date")
    void rejectsMalformedDate() {
        assertThat(controller.save(body("name", "Bad", "date_from", "not-a-date"))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("lists every saved preset")
    void listsPresets() {
        controller.save(body("name", "First"));
        controller.save(body("name", "Second"));

        assertThat(controller.list()).hasSize(2);
    }

    @Test
    @DisplayName("deletes a preset, and reports a missing one as 404")
    void deletesPreset() {
        FilterPreset saved = presets.upsert("Temp", null, null, null, null, null, null);

        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(200);
        assertThat(presets.findAll()).isEmpty();
        assertThat(controller.delete(saved.id()).getStatusCode().value()).isEqualTo(404);
    }
}

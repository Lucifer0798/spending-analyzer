package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.Account;
import com.spendinganalyzer.repository.AccountBalanceRepository;
import com.spendinganalyzer.repository.NetWorthTargetRepository;
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

/** Setting and clearing the single net worth target. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class NetWorthControllerTest {

    @Autowired
    private NetWorthController controller;

    @Autowired
    private NetWorthTargetRepository targets;

    @Autowired
    private AccountBalanceRepository balances;

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    @Test
    @DisplayName("sets a target")
    void setsTarget() {
        ResponseEntity<?> response = controller.setTarget(body("target_amount", 10000, "currency", "USD"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(targets.find()).isPresent().get()
                .extracting("targetAmount", "targetDate", "currency")
                .containsExactly(10000.0, null, "USD");
    }

    @Test
    @DisplayName("sets a target with an optional target date")
    void setsTargetWithDate() {
        controller.setTarget(body("target_amount", 10000, "currency", "USD", "target_date", "2027-01-01"));

        assertThat(targets.find()).isPresent().get()
                .extracting("targetDate").isEqualTo("2027-01-01");
    }

    @Test
    @DisplayName("setting a target twice replaces it rather than duplicating it")
    void upsertsRatherThanDuplicating() {
        controller.setTarget(body("target_amount", 10000, "currency", "USD"));
        controller.setTarget(body("target_amount", 20000, "currency", "USD"));

        assertThat(targets.find()).isPresent().get().extracting("targetAmount").isEqualTo(20000.0);
    }

    @Test
    @DisplayName("rejects a zero or negative target amount")
    void rejectsNonPositiveAmount() {
        assertThat(controller.setTarget(body("target_amount", 0, "currency", "USD")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(controller.setTarget(body("target_amount", -100, "currency", "USD")).getStatusCode().value())
                .isEqualTo(400);
        assertThat(targets.find()).isEmpty();
    }

    @Test
    @DisplayName("rejects a missing or invalid currency")
    void rejectsInvalidCurrency() {
        assertThat(controller.setTarget(body("target_amount", 10000)).getStatusCode().value()).isEqualTo(400);
        assertThat(controller.setTarget(body("target_amount", 10000, "currency", "NOTACURRENCY"))
                .getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects a malformed target date")
    void rejectsMalformedDate() {
        ResponseEntity<?> response =
                controller.setTarget(body("target_amount", 10000, "currency", "USD", "target_date", "not-a-date"));

        assertThat(response.getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("clears a target, and reports its absence with an ok response, not an error")
    void clearsTarget() {
        controller.setTarget(body("target_amount", 10000, "currency", "USD"));

        ResponseEntity<?> response = controller.clearTarget();

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(targets.find()).isEmpty();
    }

    @Test
    @DisplayName("a set target shows up in the net worth response")
    void targetAppearsInNetWorthResponse() {
        balances.upsert(Account.DEFAULT_ID, "2026-06-01", 1000.0);
        controller.setTarget(body("target_amount", 5000, "currency", "USD"));

        var response = controller.get();

        assertThat(response.target()).isNotNull();
        assertThat(response.target().targetAmount()).isEqualTo(5000.0);
        assertThat(response.target().achieved()).isFalse();
    }
}

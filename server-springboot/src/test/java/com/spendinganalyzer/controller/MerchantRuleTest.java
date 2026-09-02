package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MerchantRuleTest {

    private static final String MERCHANT = "AMAZON.COM";

    @Autowired
    private MerchantController controller;

    @Autowired
    private MerchantCategoryRepository merchants;

    @BeforeEach
    void clean() {
        merchants.deleteAll();
    }

    private static Map<String, Object> body(Object... keyValues) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < keyValues.length; i += 2) map.put((String) keyValues[i], keyValues[i + 1]);
        return map;
    }

    // --- creating rules ---------------------------------------------------------

    @Test
    @DisplayName("a rule with no bounds covers every amount")
    void savesCatchAll() {
        ResponseEntity<?> response = controller.saveRule(
                body("merchant_key", MERCHANT, "category", "Shopping"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        List<MerchantCategory> rules = merchants.findByKey(MERCHANT);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).isCatchAll()).isTrue();
    }

    @Test
    @DisplayName("a banded rule sits alongside the catch-all rather than replacing it")
    void bandCoexistsWithCatchAll() {
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping"));
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Subscriptions",
                "min_amount", 0, "max_amount", 15));

        // This is the whole feature: one merchant, two categories, chosen by amount.
        assertThat(merchants.findByKey(MERCHANT)).hasSize(2);
        assertThat(MerchantCategory.bestMatch(merchants.findByKey(MERCHANT), 9.99)).get()
                .extracting(MerchantCategory::category).isEqualTo("Subscriptions");
        assertThat(MerchantCategory.bestMatch(merchants.findByKey(MERCHANT), 60.00)).get()
                .extracting(MerchantCategory::category).isEqualTo("Shopping");
    }

    @Test
    @DisplayName("saving the same band twice replaces it instead of duplicating")
    void sameBandIsReplaced() {
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping",
                "min_amount", 0, "max_amount", 15));
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Subscriptions",
                "min_amount", 0, "max_amount", 15));

        List<MerchantCategory> rules = merchants.findByKey(MERCHANT);
        assertThat(rules).hasSize(1);
        assertThat(rules.get(0).category()).isEqualTo("Subscriptions");
    }

    @Test
    @DisplayName("normalises the merchant key, so a lowercase entry still matches")
    void uppercasesMerchantKey() {
        controller.saveRule(body("merchant_key", "amazon.com", "category", "Shopping"));

        assertThat(merchants.findByKey(MERCHANT)).hasSize(1);
    }

    // --- rejecting nonsense -----------------------------------------------------

    @Test
    @DisplayName("rejects a band that could never match anything")
    void rejectsBackwardsBand() {
        assertThat(controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping",
                "min_amount", 100, "max_amount", 10)).getStatusCode().value()).isEqualTo(400);
        // Equal bounds are empty, not a point match.
        assertThat(controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping",
                "min_amount", 10, "max_amount", 10)).getStatusCode().value()).isEqualTo(400);
        assertThat(merchants.findByKey(MERCHANT)).isEmpty();
    }

    @Test
    @DisplayName("rejects a negative lower bound, since amounts are stored unsigned")
    void rejectsNegativeMinimum() {
        assertThat(controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping",
                "min_amount", -5, "max_amount", 10)).getStatusCode().value()).isEqualTo(400);
    }

    @Test
    @DisplayName("rejects an unknown category and a missing merchant")
    void rejectsUnknownCategoryAndBlankMerchant() {
        assertThat(controller.saveRule(body("merchant_key", MERCHANT, "category", "Not A Category"))
                .getStatusCode().value()).isEqualTo(400);
        assertThat(controller.saveRule(body("merchant_key", "", "category", "Shopping"))
                .getStatusCode().value()).isEqualTo(400);
    }

    // --- hit counting -----------------------------------------------------------

    @Test
    @DisplayName("a hit credits only the band that answered")
    void hitsAreCreditedPerBand() {
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Shopping"));
        controller.saveRule(body("merchant_key", MERCHANT, "category", "Subscriptions",
                "min_amount", 0, "max_amount", 15));

        MerchantCategory band = merchants.findByKey(MERCHANT).stream()
                .filter(r -> !r.isCatchAll()).findFirst().orElseThrow();
        merchants.recordHits(Map.of(band.id(), 3));

        Map<Boolean, Integer> hitsByIsCatchAll = new HashMap<>();
        for (MerchantCategory r : merchants.findByKey(MERCHANT)) {
            hitsByIsCatchAll.put(r.isCatchAll(), r.hitCount());
        }
        assertThat(hitsByIsCatchAll.get(false)).isEqualTo(3);
        // Keying hits by merchant instead of by rule would have credited this one too.
        assertThat(hitsByIsCatchAll.get(true)).isZero();
    }

    // --- migration behaviour ----------------------------------------------------

    @Test
    @DisplayName("correcting a transaction still writes a catch-all, not a band")
    void rememberWritesCatchAll() {
        merchants.remember(MERCHANT, "Shopping", MerchantCategory.SOURCE_USER);

        assertThat(merchants.findByKey(MERCHANT)).singleElement()
                .matches(MerchantCategory::isCatchAll);
    }

    @Test
    @DisplayName("a model guess never overwrites a correction")
    void aiDoesNotOverwriteUser() {
        merchants.remember(MERCHANT, "Shopping", MerchantCategory.SOURCE_USER);
        merchants.remember(MERCHANT, "Entertainment", MerchantCategory.SOURCE_AI);

        assertThat(merchants.findByKey(MERCHANT)).singleElement()
                .extracting(MerchantCategory::category).isEqualTo("Shopping");
    }
}

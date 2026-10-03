package com.spendinganalyzer.service;

import com.spendinganalyzer.model.MerchantCategory;
import com.spendinganalyzer.repository.MerchantCategoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.tuple;

/** Merchant memory round-tripping through CSV: export, merge, replace, and all-or-nothing validation. */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class MerchantImportServiceTest {

    @Autowired
    private MerchantImportService service;

    @Autowired
    private MerchantCategoryRepository merchants;

    @Autowired
    private CsvExportService csv;

    @BeforeEach
    void startEmpty() {
        merchants.deleteAll();
    }

    private List<MerchantImportService.ImportedRule> parse(String content) throws Exception {
        return service.parse(new ByteArrayInputStream(content.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    @DisplayName("an exported file imports back to the same rules, catch-alls and bands alike")
    void exportRoundTrips() throws Exception {
        merchants.saveRule("NETFLIX", "Subscriptions", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);
        merchants.saveRule("AMAZON", "Subscriptions", 0, 15, MerchantCategory.SOURCE_USER);
        merchants.saveRule("AMAZON", "Shopping", 15, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_AI);
        byte[] exported = csv.merchantRules(merchants.findAll());

        merchants.deleteAll();
        // The export starts with a byte-order mark; the import has to see past it.
        var rules = service.parse(new ByteArrayInputStream(exported));
        service.apply(rules, true);

        assertThat(merchants.findAll())
                .extracting(MerchantCategory::merchantKey, MerchantCategory::category,
                        MerchantCategory::minAmount, MerchantCategory::maxAmount, MerchantCategory::source)
                .containsExactly(
                        tuple("AMAZON", "Subscriptions", 0.0, 15.0, "user"),
                        tuple("AMAZON", "Shopping", 15.0, MerchantCategory.UNBOUNDED, "ai"),
                        tuple("NETFLIX", "Subscriptions", 0.0, MerchantCategory.UNBOUNDED, "user"));
    }

    @Test
    @DisplayName("the export writes a catch-all's upper bound blank, not the stored sentinel")
    void exportWritesUnboundedAsBlank() {
        merchants.saveRule("NETFLIX", "Subscriptions", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);

        String text = new String(csv.merchantRules(merchants.findAll()), StandardCharsets.UTF_8);

        assertThat(text).contains("NETFLIX,Subscriptions,0.0,,user,0");
        assertThat(text).doesNotContain("1.0E12");
    }

    @Test
    @DisplayName("merging keeps rules the file doesn't mention and overwrites the ones it does, resetting their hits")
    void mergeOverwritesMatchingBandsOnly() throws Exception {
        merchants.saveRule("NETFLIX", "Entertainment", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);
        merchants.saveRule("SPOTIFY", "Subscriptions", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);
        long netflixId = merchants.findByKey("NETFLIX").get(0).id();
        merchants.recordHits(Map.of(netflixId, 5));

        var result = service.apply(parse("""
                merchant_key,category,min_amount,max_amount,source
                NETFLIX,Subscriptions,,,user
                GYM CO,Healthcare,,,
                """), false);

        assertThat(result.created()).isEqualTo(1);
        assertThat(result.updated()).isEqualTo(1);
        assertThat(result.removed()).isZero();
        MerchantCategory netflix = merchants.findByKey("NETFLIX").get(0);
        assertThat(netflix.category()).isEqualTo("Subscriptions");
        assertThat(netflix.hitCount()).isZero();
        assertThat(merchants.findByKey("SPOTIFY")).hasSize(1);   // untouched
        assertThat(merchants.findByKey("GYM CO")).hasSize(1);
    }

    @Test
    @DisplayName("replacing leaves memory holding exactly what the file does")
    void replaceDropsEverythingElse() throws Exception {
        merchants.saveRule("NETFLIX", "Subscriptions", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);
        merchants.saveRule("SPOTIFY", "Subscriptions", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);

        var result = service.apply(parse("""
                merchant_key,category
                GYM CO,Healthcare
                """), true);

        assertThat(result.removed()).isEqualTo(2);
        assertThat(result.created()).isEqualTo(1);
        assertThat(merchants.findAll()).extracting(MerchantCategory::merchantKey).containsExactly("GYM CO");
    }

    @Test
    @DisplayName("a hand-edited file is forgiven its casing: headers, category names, and merchant keys")
    void caseInsensitiveHeadersAndCategories() throws Exception {
        var rules = parse("""
                Merchant_Key,CATEGORY
                corner shop,groceries
                """);

        assertThat(rules).singleElement().satisfies(r -> {
            assertThat(r.merchantKey()).isEqualTo("CORNER SHOP");
            assertThat(r.category()).isEqualTo("Groceries");   // the instance's own spelling
            assertThat(r.source()).isEqualTo("user");          // a blank source is a correction
            assertThat(r.maxAmount()).isEqualTo(MerchantCategory.UNBOUNDED);
        });
    }

    @Test
    @DisplayName("one bad row rejects the whole file, every problem listed by line, and nothing is written")
    void invalidRowsRejectWholeFile() {
        merchants.saveRule("KEEP ME", "Groceries", 0, MerchantCategory.UNBOUNDED, MerchantCategory.SOURCE_USER);

        assertThatThrownBy(() -> parse("""
                merchant_key,category,min_amount,max_amount
                GOOD ROW,Groceries,,
                NO SUCH,Not A Category,,
                NEGATIVE,Groceries,-5,
                BACKWARDS,Groceries,50,10
                WORDS,Groceries,ten,
                ,Groceries,,
                GOOD ROW,Shopping,,
                """))
                .isInstanceOf(MerchantImportService.ImportException.class)
                .satisfies(e -> assertThat(((MerchantImportService.ImportException) e).errors)
                        .containsExactly(
                                "Line 3: category \"Not A Category\" doesn't exist here — create it first.",
                                "Line 4: min_amount can't be negative.",
                                "Line 5: min_amount must be less than max_amount.",
                                "Line 6: min_amount \"ten\" isn't a number.",
                                "Line 7: merchant_key is empty.",
                                "Line 8: duplicates an earlier row for the same merchant and amount band."));

        assertThat(merchants.findAll()).extracting(MerchantCategory::merchantKey).containsExactly("KEEP ME");
    }

    @Test
    @DisplayName("a file missing the required columns says which columns it did find")
    void missingColumnsNamed() {
        assertThatThrownBy(() -> parse("""
                name,type
                NETFLIX,Subscriptions
                """))
                .isInstanceOf(MerchantImportService.ImportException.class)
                .hasMessageContaining("merchant_key and category")
                .hasMessageContaining("name, type");
    }

    @Test
    @DisplayName("a file with a header and nothing else is an error, not a silent no-op")
    void emptyFileRejected() {
        assertThatThrownBy(() -> parse("merchant_key,category\n\n"))
                .isInstanceOf(MerchantImportService.ImportException.class)
                .hasMessageContaining("no merchant rules");
    }

    @Test
    @DisplayName("only the first ten problems are listed, with a count of the rest")
    void errorListIsCapped() {
        StringBuilder file = new StringBuilder("merchant_key,category\n");
        for (int i = 0; i < 13; i++) file.append("SHOP ").append(i).append(",Nope\n");

        assertThatThrownBy(() -> parse(file.toString()))
                .isInstanceOf(MerchantImportService.ImportException.class)
                .satisfies(e -> {
                    var errors = ((MerchantImportService.ImportException) e).errors;
                    assertThat(errors).hasSize(MerchantImportService.MAX_REPORTED_ERRORS + 1);
                    assertThat(errors.get(errors.size() - 1)).isEqualTo("…and 3 more.");
                });
    }
}

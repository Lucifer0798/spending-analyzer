package com.spendinganalyzer.controller;

import com.spendinganalyzer.model.Category;
import com.spendinganalyzer.repository.CategoryRepository;
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

/**
 * No dedicated test file for {@link CategoryController} existed before this one -- category
 * rename/delete cascades were only exercised incidentally through {@code BudgetControllerTest}.
 * This covers that gap plus the category-group field these tests were written alongside.
 */
@SpringBootTest
@ActiveProfiles("test")
@Transactional
class CategoryControllerTest {

    @Autowired
    private CategoryController controller;

    @Autowired
    private CategoryRepository categories;

    private static Map<String, Object> body(Object... kv) {
        Map<String, Object> map = new HashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            map.put((String) kv[i], kv[i + 1]);
        }
        return map;
    }

    // --- creating -----------------------------------------------------------------

    @Test
    @DisplayName("a newly created category has no group")
    void createdCategoryHasNoGroup() {
        Category hobbies = categories.create("Hobbies", false, false);

        assertThat(hobbies.groupName()).isNull();
    }

    // --- setting a group ------------------------------------------------------------

    @Test
    @DisplayName("sets a group on a category")
    void setsAGroup() {
        Category hobbies = categories.create("Hobbies", false, false);

        controller.update(hobbies.id(), body("group_name", "Personal"));

        assertThat(categories.findById(hobbies.id())).get()
                .extracting(Category::groupName).isEqualTo("Personal");
    }

    @Test
    @DisplayName("a group can be set on a built-in category, unlike renaming")
    void groupCanBeSetOnABuiltIn() {
        Category groceries = categories.findAll().stream()
                .filter(c -> c.name().equals("Groceries")).findFirst().orElseThrow();

        ResponseEntity<?> response = controller.update(groceries.id(), body("group_name", "Food"));

        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(categories.findById(groceries.id())).get()
                .extracting(Category::groupName).isEqualTo("Food");
    }

    @Test
    @DisplayName("an explicit null clears an existing group")
    void explicitNullClearsGroup() {
        Category hobbies = categories.create("Hobbies", false, false);
        controller.update(hobbies.id(), body("group_name", "Personal"));

        controller.update(hobbies.id(), body("group_name", null));

        assertThat(categories.findById(hobbies.id())).get()
                .extracting(Category::groupName).isNull();
    }

    @Test
    @DisplayName("a blank group name clears it the same way null does")
    void blankGroupNameClears() {
        Category hobbies = categories.create("Hobbies", false, false);
        controller.update(hobbies.id(), body("group_name", "Personal"));

        controller.update(hobbies.id(), body("group_name", "  "));

        assertThat(categories.findById(hobbies.id())).get()
                .extracting(Category::groupName).isNull();
    }

    @Test
    @DisplayName("a rename that never mentions the group leaves it untouched")
    void renameLeavesGroupUntouched() {
        Category hobbies = categories.create("Hobbies", false, false);
        controller.update(hobbies.id(), body("group_name", "Personal"));

        controller.update(hobbies.id(), body("name", "Hobbies & Crafts"));

        assertThat(categories.findById(hobbies.id())).get()
                .extracting(Category::name, Category::groupName)
                .containsExactly("Hobbies & Crafts", "Personal");
    }

    @Test
    @DisplayName("a flags-only update leaves the group untouched")
    void flagsOnlyUpdateLeavesGroupUntouched() {
        Category hobbies = categories.create("Hobbies", false, false);
        controller.update(hobbies.id(), body("group_name", "Personal"));

        controller.update(hobbies.id(), body("is_income", true));

        assertThat(categories.findById(hobbies.id())).get()
                .extracting(Category::isIncome, Category::groupName)
                .containsExactly(true, "Personal");
    }

    @Test
    @DisplayName("the detailed list includes each category's group")
    void listIncludesGroup() {
        Category hobbies = categories.create("Hobbies", false, false);
        controller.update(hobbies.id(), body("group_name", "Personal"));

        @SuppressWarnings("unchecked")
        var detailed = (java.util.List<CategoryController.CategoryWithCount>) controller.list().get("detailed");

        assertThat(detailed).filteredOn(c -> c.name().equals("Hobbies"))
                .extracting(CategoryController.CategoryWithCount::group_name)
                .containsExactly("Personal");
    }
}

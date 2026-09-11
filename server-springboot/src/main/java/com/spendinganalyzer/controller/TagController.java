package com.spendinganalyzer.controller;

import com.spendinganalyzer.dto.ErrorResponse;
import com.spendinganalyzer.repository.TagRepository;
import com.spendinganalyzer.repository.TagRepository.TagUsage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Every tag on record, for a filter dropdown or autocomplete list — tagging and untagging a
 * specific transaction lives on {@code /api/transactions/{id}/tags} instead, the same split
 * merchant memory and recurring overrides follow: set in context, managed centrally.
 */
@RestController
@RequestMapping("/api/tags")
public class TagController {

    private final TagRepository tags;

    public TagController(TagRepository tags) {
        this.tags = tags;
    }

    @GetMapping
    public List<TagUsage> list() {
        return tags.findAllWithCounts();
    }

    /** Deletes a tag everywhere it's applied — the tag itself, not just one transaction's use of it. */
    @DeleteMapping("/{name}")
    public ResponseEntity<?> delete(@PathVariable String name) {
        if (!tags.exists(name)) {
            return ResponseEntity.status(404).body(new ErrorResponse("Tag not found."));
        }
        int untagged = tags.deleteByName(name);
        return ResponseEntity.ok(Map.of("ok", true, "transactionsUntagged", untagged));
    }
}

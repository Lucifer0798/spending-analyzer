package com.spendinganalyzer.dto;

import java.util.List;

/**
 * What re-applying the rules to rule-categorized transactions did, or would do.
 *
 * @param dryRun    true when nothing was written -- a preview for the confirm step
 * @param checked   transactions a rule had categorized ({@code category_source = 'rule'})
 * @param changed   now in a different category, or the same one decided differently (e.g. merchant
 *                  memory now answers where a rule used to)
 * @param cleared   no rule supports their category any more, so they go back to uncategorized
 * @param unchanged everything else
 * @param examples  up to ten of the changes, so the preview shows what's about to move
 */
public record ReapplyRulesResult(
        boolean dryRun,
        int checked,
        int changed,
        int cleared,
        int unchanged,
        List<Change> examples
) {
    /** @param to null when the transaction goes back to uncategorized */
    public record Change(long id, String description, String from, String to) {}
}

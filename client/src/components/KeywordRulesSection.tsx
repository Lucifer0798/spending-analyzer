import { useEffect, useState } from "react";
import { createKeywordRule, deleteKeywordRule, fetchKeywordRules, previewKeywordRule, runCategorization } from "../api";
import type { KeywordRule, KeywordRulePreview } from "../types";

interface Props {
  /** Category names to offer. */
  categories: string[];
}

/** The same normalisation the server applies, so the preview can tell it matches what's typed. */
function normalise(keyword: string) {
  return keyword.trim().replace(/\s+/g, " ");
}

/**
 * The user's own "description contains X → category" rules: tried after merchant memory and
 * before the built-in rules. A new rule is applied straight away to whatever is still
 * uncategorized; categories already set are never changed by it.
 */
export function KeywordRulesSection({ categories }: Props) {
  const [rules, setRules] = useState<KeywordRule[]>([]);
  const [keyword, setKeyword] = useState("");
  const [category, setCategory] = useState("");
  const [preview, setPreview] = useState<KeywordRulePreview | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);

  const load = () => {
    fetchKeywordRules().then(setRules).catch(() => setRules([]));
  };
  useEffect(load, []);

  const typed = normalise(keyword);
  // Debounced: one preview request once typing pauses, not one per keystroke. A stale preview is
  // never cleared here -- `shownPreview` below only shows one that matches what's typed now.
  useEffect(() => {
    if (typed.length < 2) return;
    const handle = setTimeout(() => {
      previewKeywordRule(typed).then(setPreview).catch(() => {});
    }, 300);
    return () => clearTimeout(handle);
  }, [typed]);
  const shownPreview = preview && preview.keyword.toUpperCase() === typed.toUpperCase() ? preview : null;

  const add = async () => {
    if (typed.length < 2 || !category) return;
    setBusy(true);
    setError(null);
    setNotice(null);
    try {
      await createKeywordRule(typed, category);
      // Fill in whatever is still uncategorized now, rather than waiting for the next import.
      const applied = await runCategorization();
      setNotice(
        applied.fromKeywordRules > 0
          ? `Rule added — categorized ${applied.fromKeywordRules} transaction${applied.fromKeywordRules === 1 ? "" : "s"} with it.`
          : "Rule added. It will apply to future imports."
      );
      setKeyword("");
      setCategory("");
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to add the rule.");
    } finally {
      setBusy(false);
    }
  };

  const remove = async (rule: KeywordRule) => {
    setError(null);
    setNotice(null);
    try {
      await deleteKeywordRule(rule.id);
      setNotice(`Removed "${rule.keyword}". Transactions it already categorized keep their category.`);
      load();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to remove the rule.");
    }
  };

  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Keyword rules</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Your own rules: any description containing the keyword as a whole word goes in that
        category. They're checked after merchant memory and before the built-in rules, longest
        keyword first — useful for patterns broader than one merchant, like anything with
        “VET” going to Pets.
      </p>

      {error && <p className="mt-2 text-sm text-red-600 dark:text-red-400">{error}</p>}
      {notice && <p className="mt-2 text-sm text-emerald-700 dark:text-emerald-400">{notice}</p>}

      {rules.length > 0 && (
        <ul className="mt-3 divide-y divide-slate-100 overflow-hidden rounded-lg border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-950">
          {rules.map((r) => (
            <li key={r.id} className="flex items-center justify-between gap-3 px-4 py-2 text-sm">
              <span className="min-w-0 truncate">
                <span className="font-mono text-slate-800 dark:text-slate-200">{r.keyword}</span>
                <span className="mx-2 text-slate-400">→</span>
                <span className="text-slate-700 dark:text-slate-300">{r.category}</span>
              </span>
              <button
                onClick={() => remove(r)}
                className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
              >
                Remove
              </button>
            </li>
          ))}
        </ul>
      )}

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <input
          value={keyword}
          onChange={(e) => setKeyword(e.target.value)}
          onKeyDown={(e) => e.key === "Enter" && add()}
          placeholder="Keyword, e.g. VET"
          maxLength={60}
          className="w-48 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
        />
        <select
          value={category}
          onChange={(e) => setCategory(e.target.value)}
          aria-label="Category for this keyword"
          className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
        >
          <option value="">Category…</option>
          {categories.map((c) => (
            <option key={c} value={c}>{c}</option>
          ))}
        </select>
        <button
          disabled={busy || typed.length < 2 || !category}
          onClick={add}
          className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {busy ? "Adding…" : "Add rule"}
        </button>
      </div>

      {typed.length >= 2 && shownPreview && (
        <p className="mt-2 text-xs text-slate-500">
          {shownPreview.matches === 0 ? (
            <>Matches no transactions yet — it will apply to future imports.</>
          ) : (
            <>
              Matches {shownPreview.matches} transaction{shownPreview.matches === 1 ? "" : "s"}
              {shownPreview.uncategorized > 0
                ? `, ${shownPreview.uncategorized} still uncategorized (those get this category now)`
                : ", all already categorized (they keep their category)"}
              {" — e.g. "}
              {shownPreview.examples.join(" · ")}
            </>
          )}
        </p>
      )}
    </section>
  );
}

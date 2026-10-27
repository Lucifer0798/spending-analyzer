import { useState } from "react";
import { ApiError, exportUrl, forgetAllMerchants, forgetMerchant, importMerchants, saveMerchantRule } from "../../api";
import type { CategoryDetail, MerchantImportMode, MerchantsResponse } from "../../types";
import { currency } from "../../format";
import { ExportLink } from "../ExportLink";
import type { Banner, Run } from "./types";

export function MerchantMemorySection({
  memory,
  spendingCategories,
  run,
  reload,
  banner,
}: {
  memory: MerchantsResponse | null;
  spendingCategories: CategoryDetail[];
  run: Run;
  /** Refetches the page's data -- an import isn't a `run`, since a rejected file reports per-row problems here. */
  reload: () => Promise<void>;
  banner: Banner;
}) {
  const [ruleMerchant, setRuleMerchant] = useState("");
  const [ruleCategory, setRuleCategory] = useState("");
  const [ruleMin, setRuleMin] = useState("");
  const [ruleMax, setRuleMax] = useState("");

  const [merchantImportMode, setMerchantImportMode] = useState<MerchantImportMode>("merge");
  const [importingMerchants, setImportingMerchants] = useState(false);
  // Line-numbered problems from a rejected file, listed under the heading rather than squeezed
  // into the page's single-line error banner.
  const [merchantImportErrors, setMerchantImportErrors] = useState<string[]>([]);

  const handleMerchantImport = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // lets the same file be chosen again after fixing it
    if (!file) return;

    if (
      merchantImportMode === "replace" &&
      !confirm(
        `Replace all ${memory?.count ?? 0} remembered merchant rules with the contents of ${file.name}? Rules not in the file will be forgotten.`
      )
    ) {
      return;
    }

    setImportingMerchants(true);
    setMerchantImportErrors([]);
    banner.setError(null);
    banner.setNotice(null);
    try {
      const result = await importMerchants(file, merchantImportMode);
      await reload();
      banner.setNotice(
        result.replaced
          ? `Replaced merchant memory: ${result.removed} rules removed, ${result.imported} imported.`
          : `Imported ${result.imported} merchant rules: ${result.created} new, ${result.updated} updated.`
      );
    } catch (err) {
      const errors = err instanceof ApiError ? (err.body as { errors?: unknown }).errors : undefined;
      if (Array.isArray(errors) && errors.length > 0) {
        setMerchantImportErrors(errors.map(String));
      } else {
        banner.setError(err instanceof Error ? err.message : "Import failed.");
      }
    } finally {
      setImportingMerchants(false);
    }
  };

  return (
    <section className="mt-10 mb-10">
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Merchant memory</h2>
        <div className="flex flex-wrap items-center gap-3">
          <ExportLink
            compact
            href={exportUrl("merchants")}
            label="Export CSV"
            disabled={!memory || memory.count === 0}
            title="Nothing remembered yet to export"
          />
          <select
            value={merchantImportMode}
            onChange={(e) => setMerchantImportMode(e.target.value as MerchantImportMode)}
            aria-label="How an imported file combines with what's already remembered"
            className="rounded border border-slate-300 bg-white px-1.5 py-0.5 text-xs dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="merge">Merge</option>
            <option value="replace">Replace all</option>
          </select>
          <label
            className={`text-xs font-medium ${
              importingMerchants
                ? "cursor-wait text-slate-400"
                : "cursor-pointer text-indigo-600 hover:text-indigo-500 hover:underline dark:text-indigo-400"
            }`}
          >
            {importingMerchants ? "Importing…" : "Import CSV"}
            <input
              type="file"
              accept=".csv,text/csv"
              className="hidden"
              disabled={importingMerchants}
              onChange={handleMerchantImport}
            />
          </label>
          {memory && memory.count > 0 && (
            <button
              onClick={() => {
                if (confirm(`Forget all ${memory.count} remembered merchants? Their transactions will be left to the built-in rules on the next import.`)) {
                  run(() => forgetAllMerchants(), "Merchant memory cleared.");
                }
              }}
              className="rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
            >
              Forget all
            </button>
          )}
        </div>
      </div>
      {merchantImportErrors.length > 0 && (
        <div className="mt-2 rounded-lg bg-red-50 p-3 text-xs text-red-700 dark:bg-red-950/40 dark:text-red-300">
          <p className="font-medium">Nothing was imported — fix these rows and try again:</p>
          <ul className="mt-1 list-disc space-y-0.5 pl-5">
            {merchantImportErrors.map((e) => (
              <li key={e}>{e}</li>
            ))}
          </ul>
        </div>
      )}
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        What you've taught the app about each merchant. Merchants here are categorized this way
        on every import, ahead of the built-in rules. Correcting a transaction's category updates
        the entry here, which is what makes the correction stick.
      </p>

      {!memory || memory.count === 0 ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          Nothing learned yet. Import a statement, or correct a transaction's category, and the
          merchant will be remembered.
        </p>
      ) : (
        <>
          <p className="mt-3 text-sm text-slate-600 dark:text-slate-400">
            <strong>{memory.count}</strong> merchants remembered ·{" "}
            <strong>{memory.totalMemoryHits}</strong> transactions categorized from memory
          </p>
          <div className="mt-3 overflow-hidden rounded-lg border border-slate-200 dark:border-slate-800">
            <table className="min-w-full divide-y divide-slate-200 dark:divide-slate-800">
              <tbody className="divide-y divide-slate-100 bg-white dark:divide-slate-800 dark:bg-slate-950">
                {memory.merchants.map((m) => (
                  <tr key={m.id}>
                    <td className="px-4 py-2">
                      <div className="text-sm text-slate-800 dark:text-slate-200">
                        {m.merchant_key}
                        {m.source === "user" && (
                          <span className="ml-2 rounded bg-indigo-100 px-1.5 py-0.5 text-[10px] uppercase text-indigo-700 dark:bg-indigo-950/50 dark:text-indigo-300">
                            your correction
                          </span>
                        )}
                      </div>
                      <div className="text-xs text-slate-500">
                        {m.category}
                        {/* A band is the whole reason one merchant can appear more than once. */}
                        {!m.is_catch_all && (
                          <span className="ml-1 text-indigo-600 dark:text-indigo-400">
                            when {currency(m.min_amount)}–{currency(m.max_amount)}
                          </span>
                        )}
                        {m.hit_count > 0 && ` · reused ${m.hit_count}×`}
                      </div>
                    </td>
                    <td className="px-4 py-2 text-right">
                      <button
                        onClick={() => run(() => forgetMerchant(m.id))}
                        className="rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                        title="Let the built-in rules categorize this merchant again next time"
                      >
                        Forget
                      </button>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </>
      )}

      <div className="mt-4 rounded-lg border border-dashed border-slate-300 p-4 dark:border-slate-700">
        <h3 className="text-sm font-medium text-slate-800 dark:text-slate-200">
          Split a merchant by amount
        </h3>
        <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
          For a merchant whose description never changes whatever you bought — a subscription
          and an order both arriving as the same text. A rule with an amount range wins over the
          merchant's general category, so smaller charges can go somewhere else. Leave the range
          empty to set the general category instead.
        </p>

        <div className="mt-3 flex flex-wrap items-center gap-2">
          <input
            value={ruleMerchant}
            onChange={(e) => setRuleMerchant(e.target.value)}
            placeholder="Merchant, e.g. AMAZON.COM"
            className="min-w-48 flex-1 rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <select
            value={ruleCategory}
            onChange={(e) => setRuleCategory(e.target.value)}
            className="rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            <option value="">Category…</option>
            {spendingCategories.map((c) => (
              <option key={c.id} value={c.name}>{c.name}</option>
            ))}
          </select>
          <input
            type="number"
            min="0"
            value={ruleMin}
            onChange={(e) => setRuleMin(e.target.value)}
            placeholder="from"
            className="w-24 rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <input
            type="number"
            min="0"
            value={ruleMax}
            onChange={(e) => setRuleMax(e.target.value)}
            placeholder="to"
            className="w-24 rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
          />
          <button
            disabled={!ruleMerchant.trim() || !ruleCategory}
            onClick={() =>
              run(
                () =>
                  saveMerchantRule({
                    merchant_key: ruleMerchant.trim(),
                    category: ruleCategory,
                    // Blank means unbounded on that side; the server fills in the default.
                    ...(ruleMin.trim() ? { min_amount: Number(ruleMin) } : {}),
                    ...(ruleMax.trim() ? { max_amount: Number(ruleMax) } : {}),
                  }),
                `Rule saved for ${ruleMerchant.trim().toUpperCase()}.`
              ).then(() => {
                setRuleMerchant("");
                setRuleMin("");
                setRuleMax("");
              })
            }
            className="rounded-md bg-indigo-600 px-4 py-1.5 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
          >
            Save rule
          </button>
        </div>
      </div>
    </section>
  );
}

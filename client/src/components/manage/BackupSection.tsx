import { useState } from "react";
import { backupUrl, importBackup } from "../../api";
import { ExportLink } from "../ExportLink";
import type { Banner } from "./types";

export function BackupSection({
  reload,
  onAccountsChanged,
  banner,
}: {
  reload: () => Promise<void>;
  onAccountsChanged: () => void;
  banner: Banner;
}) {
  const [importing, setImporting] = useState(false);

  /**
   * Import is a restore, not a merge — it replaces everything currently in this instance with
   * whatever the file holds, so the confirmation has to say that plainly before it happens.
   */
  const handleImportFile = async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    e.target.value = ""; // lets the same file be chosen again later
    if (!file) return;

    if (
      !confirm(
        "This deletes everything currently in this instance — accounts, transactions, categories, budgets, merchant memory, recurring flags, savings goals, tags, net worth balances, saved filter presets, and receipts — and replaces it with the backup file. This cannot be undone. Continue?"
      )
    ) {
      return;
    }

    setImporting(true);
    banner.setError(null);
    banner.setNotice(null);
    try {
      const text = await file.text();
      const summary = await importBackup(text);
      await reload();
      onAccountsChanged();
      banner.setNotice(
        `Restored ${summary.accounts} accounts, ${summary.transactions} transactions, ` +
          `${summary.categories} categories, ${summary.budgets} budgets, ` +
          `${summary.merchantCategories} merchant rules, ${summary.recurringOverrides} recurring flags, ` +
          `${summary.goals} savings goals, ${summary.tags} tags, ${summary.accountBalances} balance entries, ` +
          `${summary.filterPresets} filter presets, ${summary.transactionReceipts} receipts, ` +
          `${summary.keywordRules} keyword rules, ${summary.transferPairs} transfer pairs.`
      );
    } catch (err) {
      banner.setError(err instanceof Error ? err.message : "Import failed.");
    } finally {
      setImporting(false);
    }
  };

  return (
    <section className="mt-10 mb-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Backup</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Everything below, as one JSON file — accounts, transactions, categories, budgets,
        merchant memory, recurring flags, savings goals, tags, net worth balances, saved
        filter presets, and receipts — for keeping a backup or moving to a new instance.
        Forecasts aren't included; regenerating one is one click.
      </p>

      <div className="mt-4 flex flex-wrap items-center gap-3">
        <ExportLink href={backupUrl()} label="Export all data" />

        <label
          className={`inline-flex cursor-pointer items-center gap-1.5 rounded-md border border-slate-300 px-3 py-1.5 text-sm font-medium text-slate-700 transition-colors hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800 ${
            importing ? "pointer-events-none opacity-50" : ""
          }`}
        >
          {importing ? "Restoring…" : "Import backup"}
          <input
            type="file"
            accept="application/json"
            disabled={importing}
            onChange={handleImportFile}
            className="hidden"
          />
        </label>
      </div>

      <p className="mt-2 text-xs text-slate-500">
        Importing replaces everything currently in this instance with the file's contents —
        it's a restore, not a merge. Only use a file this "Export all data" button produced.
      </p>
    </section>
  );
}

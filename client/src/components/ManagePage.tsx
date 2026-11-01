import { useEffect, useState } from "react";
import {
  fetchAccounts,
  fetchBudgets,
  fetchCategories,
  fetchMerchants,
  fetchRecurringOverrides,
  fetchTags,
} from "../api";
import type {
  Account,
  AccountType,
  BudgetSummary,
  CategoryDetail,
  MerchantsResponse,
  RecurringOverride,
  Tag,
} from "../types";
import { KeywordRulesSection } from "./KeywordRulesSection";
import { TransfersSection } from "./TransfersSection";
import { AccountsSection } from "./manage/AccountsSection";
import { BackupSection } from "./manage/BackupSection";
import { BudgetsSection } from "./manage/BudgetsSection";
import { CategoriesSection } from "./manage/CategoriesSection";
import { HistorySection } from "./manage/HistorySection";
import { UndoNote } from "./UndoNote";
import { changeIdOf } from "../undo";
import { MerchantMemorySection } from "./manage/MerchantMemorySection";
import { MerchantNamesSection } from "./manage/MerchantNamesSection";
import { RecurringOverridesSection } from "./manage/RecurringOverridesSection";
import { TagsSection } from "./manage/TagsSection";
import type { Banner, Run } from "./manage/types";

interface Props {
  onAccountsChanged: () => void;
}

/**
 * Owns what the sections share: the data they all display (loaded together, reloaded after any
 * change), `run` for their writes, and the one error/notice banner at the top. Each section keeps
 * its own form drafts.
 */
export function ManagePage({ onAccountsChanged }: Props) {
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [types, setTypes] = useState<AccountType[]>([]);
  const [currencies, setCurrencies] = useState<string[]>([]);
  const [categories, setCategories] = useState<CategoryDetail[]>([]);
  const [memory, setMemory] = useState<MerchantsResponse | null>(null);
  const [budgets, setBudgets] = useState<BudgetSummary | null>(null);
  const [recurringOverrides, setRecurringOverrides] = useState<RecurringOverride[]>([]);
  const [tags, setTags] = useState<Tag[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [notice, setNotice] = useState<string | null>(null);
  // The last recorded change, offered for undo in a toast that stays in view wherever on this long
  // page it was made. A new change replaces it (each stays undoable from History below).
  const [lastChange, setLastChange] = useState<{ id: number; text: string } | null>(null);

  const reload = async () => {
    const [accountsRes, categoriesRes, merchantsRes, budgetsRes, recurringOverridesRes, tagsRes] =
      await Promise.all([
        fetchAccounts(true),
        fetchCategories(),
        fetchMerchants(),
        fetchBudgets(null),
        fetchRecurringOverrides(),
        fetchTags(),
      ]);
    setAccounts(accountsRes.accounts);
    setTypes(accountsRes.types);
    setCurrencies(accountsRes.currencies);
    setCategories(categoriesRes.detailed);
    setMemory(merchantsRes);
    setBudgets(budgetsRes);
    setRecurringOverrides(recurringOverridesRes);
    setTags(tagsRes);
  };

  useEffect(() => {
    // reload() fetches from the API on mount; effects are exactly where that belongs, and
    // there is no render-time value to derive instead.
    // oxlint-disable-next-line react/set-state-in-effect
    reload().catch((e) => setError(e instanceof Error ? e.message : "Failed to load."));
  }, []);

  /** Runs an action, then refreshes and reports the outcome in one place. */
  const run: Run = async (action, successMessage) => {
    setError(null);
    setNotice(null);
    try {
      const result = await action();
      await reload();
      onAccountsChanged();
      offerUndo(changeIdOf(result), successMessage ?? "Saved.", successMessage !== undefined);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Something went wrong.");
    }
  };

  /** Undo when the write made a History entry; otherwise just the message, if there's one to show. */
  const offerUndo = (id: number | null, text: string, showWithoutUndo = true) => {
    if (id !== null) {
      setNotice(null);
      setLastChange({ id, text });
    } else if (showWithoutUndo) {
      setNotice(text);
    }
  };

  const banner: Banner = { setError, setNotice, offerUndo: (id, text) => offerUndo(id, text) };

  // Budgeting income or transfers is meaningless — neither counts as spending anywhere else.
  const spendingCategories = categories.filter((c) => !c.is_income && !c.is_transfer);

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Manage</h1>

      {error && (
        <div className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}
      {notice && (
        <div className="mt-4 rounded-lg bg-emerald-50 p-3 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          {notice}
        </div>
      )}


      <AccountsSection accounts={accounts} types={types} currencies={currencies} run={run} />

      <CategoriesSection categories={categories} run={run} />

      <BudgetsSection spendingCategories={spendingCategories} budgets={budgets} run={run} />

      <MerchantMemorySection
        memory={memory}
        spendingCategories={spendingCategories}
        run={run}
        reload={reload}
        banner={banner}
      />

      {/* Fetches its own list; `categories` is a fresh array after every reload, so it follows the page. */}
      <MerchantNamesSection run={run} refreshKey={categories} />

      <KeywordRulesSection categories={categories.map((c) => c.name)} offerUndo={banner.offerUndo} />

      <TransfersSection />

      <RecurringOverridesSection recurringOverrides={recurringOverrides} run={run} />

      <TagsSection tags={tags} run={run} />

      {/* `categories` is a fresh array after every reload, so History re-fetches whenever the page does. */}
      <HistorySection run={run} banner={banner} refreshKey={categories} />

      {lastChange && (
        // Fixed to the bottom of the screen: the change may have been made far below the banner.
        <div className="fixed inset-x-0 bottom-4 z-30 mx-auto w-[calc(100%-2rem)] max-w-xl rounded-lg shadow-lg [&>*]:mb-0">
          <UndoNote
            key={lastChange.id}
            text={lastChange.text}
            changeId={lastChange.id}
            onUndone={() => {
              reload().catch((e) => setError(e instanceof Error ? e.message : "Failed to reload."));
              onAccountsChanged();
            }}
            onDismiss={() => setLastChange(null)}
          />
        </div>
      )}

      <BackupSection reload={reload} onAccountsChanged={onAccountsChanged} banner={banner} />
    </div>
  );
}

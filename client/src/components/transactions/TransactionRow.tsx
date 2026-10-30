import {
  addTransactionTag,
  clearTransactionSplit,
  deleteTransaction,
  deleteTransactionReceipt,
  receiptUrl,
  removeTransactionTag,
  setTransactionSplit,
  updateTransactionCategory,
  uploadTransactionReceipt,
} from "../../api";
import type { TransactionWithTags } from "../../types";
import { currencyPrecise } from "../../format";
import { CARD_CELL, CARD_ROW } from "./layout";
import type { OfferUndo, SetError } from "./types";

interface Props {
  t: TransactionWithTags;
  selected: boolean;
  onToggleSelected: () => void;
  showAccountColumn: boolean;
  categories: string[];
  colorByCategory: Record<string, string>;
  /** What's typed in this row's "+ tag" box -- held by the page, so it outlives an edit or reload. */
  tagDraft: string;
  onTagDraftChange: (value: string) => void;
  /** Updates this row in place, without a reload: tags, the receipt flag, an optimistic category. */
  onPatch: (patch: Partial<TransactionWithTags>) => void;
  /** Reloads the page of transactions. */
  onReload: () => void;
  /** The tag list (filter and suggestions) changed. */
  onTagsChanged: () => void;
  onEdit: () => void;
  offerUndo: OfferUndo;
  setError: SetError;
}

/** One transaction: its category picker, split, tags, receipt, and Edit / Delete. */
export function TransactionRow({
  t,
  selected,
  onToggleSelected,
  showAccountColumn,
  categories,
  colorByCategory,
  tagDraft,
  onTagDraftChange,
  onPatch,
  onReload,
  onTagsChanged,
  onEdit,
  offerUndo,
  setError,
}: Props) {
  const handleAddTag = async () => {
    const name = tagDraft.trim();
    if (!name) return;
    onTagDraftChange("");
    try {
      const { tags: updated } = await addTransactionTag(t.id, name);
      onPatch({ tags: updated });
      onTagsChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to add tag.");
    }
  };

  const handleRemoveTag = async (tag: string) => {
    try {
      const { tags: updated } = await removeTransactionTag(t.id, tag);
      onPatch({ tags: updated });
      onTagsChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to remove tag.");
    }
  };

  const handleUploadReceipt = async (file: File) => {
    try {
      await uploadTransactionReceipt(t.id, file);
      onPatch({ has_receipt: true });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to attach receipt.");
    }
  };

  const handleDeleteReceipt = async () => {
    if (!confirm("Remove this receipt?")) return;
    try {
      await deleteTransactionReceipt(t.id);
      onPatch({ has_receipt: false });
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to remove receipt.");
    }
  };

  const handleCategoryChange = async (category: string) => {
    onPatch({ category, category_source: "user" });
    try {
      const r = await updateTransactionCategory(t.id, category);
      offerUndo(r.changeId, `"${r.transaction.description}" → ${category}`);
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to update category.");
      onReload();
    }
  };

  const handleSetSplit = async () => {
    const fullAmount = currencyPrecise(t.amount, t.account_currency ?? undefined);
    const shareInput = prompt(
      `Your share of the ${fullAmount} charge:`,
      String(t.split_share ?? t.amount)
    );
    if (shareInput === null) return;
    const share = Number(shareInput);
    if (!Number.isFinite(share) || share < 0 || share > t.amount) {
      setError(`Split share must be a number between 0 and ${fullAmount}.`);
      return;
    }
    // A cancelled note prompt still saves the share -- there's no reason to discard a share the
    // user already confirmed just because they had nothing to add for who owes what.
    const note = prompt("Note for who owes what (optional):", t.split_note ?? "") ?? "";

    setError(null);
    try {
      await setTransactionSplit(t.id, share, note);
      onReload();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to save the split.");
    }
  };

  const handleClearSplit = async () => {
    if (!confirm(`Clear the split on "${t.description}"? The full amount will count as yours again.`)) return;
    setError(null);
    try {
      await clearTransactionSplit(t.id);
      onReload();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to clear the split.");
    }
  };

  const handleDelete = async () => {
    if (!confirm(`Delete "${t.description}" (${currencyPrecise(t.amount, t.account_currency ?? undefined)}) on ${t.date}?`)) return;
    setError(null);
    try {
      const r = await deleteTransaction(t.id);
      offerUndo(r.changeId, `Deleted "${t.description}"`);
      onReload();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to delete transaction.");
    }
  };

  return (
    <tr className={`${CARD_ROW} ${selected ? "bg-indigo-50/30 dark:bg-indigo-950/10" : ""}`}>
      <td className={`${CARD_CELL} col-start-1 row-start-1`}>
        <input type="checkbox" checked={selected} onChange={onToggleSelected} />
      </td>
      <td className={`${CARD_CELL} col-start-2 row-start-2 whitespace-nowrap text-xs text-slate-500 md:text-sm md:text-slate-600 dark:md:text-slate-400`}>
        {t.date}
        {/* The account column is hidden on a card, so the name rides along with the date. */}
        {showAccountColumn && <span className="md:hidden"> · {t.account_name}</span>}
      </td>
      <td className={`${CARD_CELL} col-start-2 row-start-1 text-sm font-medium text-slate-800 md:font-normal dark:text-slate-200`}>
        {t.description}
      </td>
      {showAccountColumn && (
        <td className="hidden whitespace-nowrap px-4 py-2 text-xs text-slate-500 md:table-cell">{t.account_name}</td>
      )}
      <td
        className={`${CARD_CELL} col-start-3 row-span-2 row-start-1 whitespace-nowrap text-right text-sm font-medium ${
          t.type === "credit" ? "text-emerald-600" : "text-slate-800 dark:text-slate-200"
        }`}
      >
        {t.type === "credit" ? "+" : "-"}
        {currencyPrecise(t.amount, t.account_currency ?? undefined)}
        <div className="mt-0.5 text-right text-[10px] font-normal normal-case text-slate-400">
          {t.split_share != null ? (
            <span title={t.split_note ?? undefined}>
              your {currencyPrecise(t.split_share, t.account_currency ?? undefined)}
              <button onClick={handleSetSplit} className="ml-1 text-indigo-500 hover:underline">
                edit
              </button>
              <button onClick={handleClearSplit} className="ml-1 text-slate-400 hover:text-red-600" title="Clear split">
                ×
              </button>
            </span>
          ) : (
            <button onClick={handleSetSplit} className="text-indigo-500 hover:underline">
              split
            </button>
          )}
        </div>
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-3 text-sm`}>
        {t.category && colorByCategory[t.category] && (
          <span
            className="mr-1 inline-block h-2 w-2 rounded-full align-middle"
            style={{ backgroundColor: colorByCategory[t.category] }}
          />
        )}
        <select
          value={t.category ?? ""}
          onChange={(e) => handleCategoryChange(e.target.value)}
          className="rounded border border-slate-300 bg-white px-2 py-1 text-xs dark:border-slate-700 dark:bg-slate-900"
        >
          <option value="" disabled>
            Uncategorized
          </option>
          {categories.map((c) => (
            <option key={c} value={c}>
              {c}
            </option>
          ))}
        </select>
        {t.category_source && <span className="ml-2 text-[10px] uppercase text-slate-400">{t.category_source}</span>}
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-4 text-sm`}>
        <div className="flex flex-wrap items-center gap-1">
          {t.tags.map((tag) => (
            <span
              key={tag}
              className="inline-flex items-center gap-1 rounded bg-slate-100 px-1.5 py-0.5 text-xs text-slate-700 dark:bg-slate-800 dark:text-slate-300"
            >
              {tag}
              <button
                onClick={() => handleRemoveTag(tag)}
                className="text-slate-400 hover:text-red-600"
                title={`Remove "${tag}"`}
              >
                ×
              </button>
            </span>
          ))}
          <input
            list="tag-suggestions"
            value={tagDraft}
            onChange={(e) => onTagDraftChange(e.target.value)}
            onKeyDown={(e) => {
              if (e.key === "Enter") handleAddTag();
            }}
            placeholder="+ tag"
            className="w-16 rounded border border-dashed border-slate-300 bg-transparent px-1 py-0.5 text-xs focus:w-28 dark:border-slate-700"
          />
        </div>
      </td>
      <td className={`${CARD_CELL} col-span-2 col-start-2 row-start-5 -ml-2 whitespace-nowrap md:ml-0 md:text-right`}>
        {t.has_receipt ? (
          <>
            <a
              href={receiptUrl(t.id)}
              target="_blank"
              rel="noreferrer"
              title="View receipt"
              className="rounded px-2 py-1 text-xs text-indigo-600 hover:bg-indigo-50 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
            >
              Receipt
            </a>
            <button
              onClick={handleDeleteReceipt}
              title="Remove receipt"
              className="rounded px-1 py-1 text-xs text-slate-400 hover:text-red-600"
            >
              ×
            </button>
          </>
        ) : (
          <label
            title="Attach a receipt image or PDF"
            className="cursor-pointer rounded px-2 py-1 text-xs text-slate-500 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
          >
            + Receipt
            <input
              type="file"
              accept="image/jpeg,image/png,image/webp,image/heic,application/pdf"
              className="hidden"
              onChange={(e) => {
                const file = e.target.files?.[0];
                if (file) handleUploadReceipt(file);
                e.target.value = "";
              }}
            />
          </label>
        )}
        <button
          onClick={onEdit}
          className="ml-1 rounded px-2 py-1 text-xs text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
        >
          Edit
        </button>
        <button
          onClick={handleDelete}
          className="ml-1 rounded px-2 py-1 text-xs text-red-600 hover:bg-red-50 dark:hover:bg-red-950/40"
        >
          Delete
        </button>
      </td>
    </tr>
  );
}

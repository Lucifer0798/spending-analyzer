import { useCallback, useRef, useState } from "react";
import { ApiError, runCategorization, uploadFile } from "../api";
import type { Account, CategorizeResult, ColumnDetectionResult, UploadResult } from "../types";
import { accountTypeLabel } from "../format";

interface Props {
  accounts: Account[];
  selectedAccountId: number | null;
  onDone: () => void;
  /** Opens Transactions with the uncategorized review expanded. */
  onReviewUncategorized: () => void;
}

type Stage = "idle" | "uploading" | "categorizing" | "done" | "error";
type AmountMode = "single" | "debitCredit";

/**
 * Asks the user to confirm or correct which header is which, prefilled with whatever
 * auto-detection already managed to guess. Only the columns a confirmed import actually needs
 * have to be filled in -- a category mapping, for instance, stays optional here exactly as it is
 * for an ordinary file.
 */
function ColumnMappingForm({
  detection,
  fileName,
  busy,
  onConfirm,
  onCancel,
}: {
  detection: ColumnDetectionResult;
  fileName: string;
  busy: boolean;
  onConfirm: (mapping: {
    dateColumn: string;
    descriptionColumn: string;
    amountColumn?: string;
    debitColumn?: string;
    creditColumn?: string;
    categoryColumn?: string;
  }) => void;
  onCancel: () => void;
}) {
  const [dateColumn, setDateColumn] = useState(detection.dateColumn ?? "");
  const [descriptionColumn, setDescriptionColumn] = useState(detection.descriptionColumn ?? "");
  const [amountMode, setAmountMode] = useState<AmountMode>(
    detection.debitColumn || detection.creditColumn ? "debitCredit" : "single"
  );
  const [amountColumn, setAmountColumn] = useState(detection.amountColumn ?? "");
  const [debitColumn, setDebitColumn] = useState(detection.debitColumn ?? "");
  const [creditColumn, setCreditColumn] = useState(detection.creditColumn ?? "");
  const [categoryColumn, setCategoryColumn] = useState(detection.categoryColumn ?? "");

  const amountReady = amountMode === "single" ? !!amountColumn : !!(debitColumn || creditColumn);
  const canSubmit = !!dateColumn && !!descriptionColumn && amountReady;

  const selectClass =
    "mt-1 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900";

  const options = (
    <>
      <option value="">— choose a column —</option>
      {detection.headers.map((h) => (
        <option key={h} value={h}>
          {h}
        </option>
      ))}
    </>
  );

  return (
    <div className="mt-6 rounded-xl border border-amber-300 bg-amber-50 p-5 dark:border-amber-800 dark:bg-amber-950/20">
      <h2 className="text-sm font-semibold text-amber-900 dark:text-amber-200">
        Couldn't tell which columns are which in {fileName}
      </h2>
      <p className="mt-1 text-sm text-amber-800 dark:text-amber-300">
        Here's every column header {fileName} actually has — match the ones this import needs,
        and anything already guessed correctly is filled in for you.
      </p>

      <div className="mt-4 grid gap-4 sm:grid-cols-2">
        <label className="block">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Date</span>
          <select value={dateColumn} onChange={(e) => setDateColumn(e.target.value)} className={selectClass}>
            {options}
          </select>
        </label>
        <label className="block">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Description</span>
          <select
            value={descriptionColumn}
            onChange={(e) => setDescriptionColumn(e.target.value)}
            className={selectClass}
          >
            {options}
          </select>
        </label>
        <label className="block">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Category (optional)</span>
          <select value={categoryColumn} onChange={(e) => setCategoryColumn(e.target.value)} className={selectClass}>
            {options}
          </select>
        </label>
      </div>

      <div className="mt-4">
        <div className="flex items-center gap-4 text-sm text-slate-700 dark:text-slate-300">
          <label className="flex items-center gap-1.5">
            <input
              type="radio"
              checked={amountMode === "single"}
              onChange={() => setAmountMode("single")}
            />
            One amount column
          </label>
          <label className="flex items-center gap-1.5">
            <input
              type="radio"
              checked={amountMode === "debitCredit"}
              onChange={() => setAmountMode("debitCredit")}
            />
            Separate debit/credit columns
          </label>
        </div>

        {amountMode === "single" ? (
          <label className="mt-2 block sm:w-1/2">
            <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Amount</span>
            <select value={amountColumn} onChange={(e) => setAmountColumn(e.target.value)} className={selectClass}>
              {options}
            </select>
          </label>
        ) : (
          <div className="mt-2 grid gap-4 sm:grid-cols-2">
            <label className="block">
              <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Debit</span>
              <select value={debitColumn} onChange={(e) => setDebitColumn(e.target.value)} className={selectClass}>
                {options}
              </select>
            </label>
            <label className="block">
              <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Credit</span>
              <select value={creditColumn} onChange={(e) => setCreditColumn(e.target.value)} className={selectClass}>
                {options}
              </select>
            </label>
          </div>
        )}
      </div>

      <div className="mt-5 flex items-center gap-3">
        <button
          disabled={!canSubmit || busy}
          onClick={() =>
            onConfirm({
              dateColumn,
              descriptionColumn,
              amountColumn: amountMode === "single" ? amountColumn : undefined,
              debitColumn: amountMode === "debitCredit" ? debitColumn : undefined,
              creditColumn: amountMode === "debitCredit" ? creditColumn : undefined,
              categoryColumn: categoryColumn || undefined,
            })
          }
          className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
        >
          {busy ? "Importing…" : "Confirm and import"}
        </button>
        <button
          onClick={onCancel}
          disabled={busy}
          className="text-sm text-slate-600 hover:underline dark:text-slate-400"
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

export function UploadPage({ accounts, selectedAccountId, onDone, onReviewUncategorized }: Props) {
  const [stage, setStage] = useState<Stage>("idle");
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [result, setResult] = useState<UploadResult | null>(null);
  const [categorization, setCategorization] = useState<CategorizeResult | null>(null);
  const [skipDuplicates, setSkipDuplicates] = useState(true);
  const [targetAccountId, setTargetAccountId] = useState<number>(
    selectedAccountId ?? accounts[0]?.id ?? 1
  );
  const [columnsNeeded, setColumnsNeeded] = useState<ColumnDetectionResult | null>(null);
  const [pendingFile, setPendingFile] = useState<File | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = useCallback(
    async (
      file: File,
      columnMapping?: {
        dateColumn: string;
        descriptionColumn: string;
        amountColumn?: string;
        debitColumn?: string;
        creditColumn?: string;
        categoryColumn?: string;
      }
    ) => {
      setError(null);
      setResult(null);
      setCategorization(null);
      setColumnsNeeded(null);
      setStage("uploading");
      try {
        const uploaded = await uploadFile(file, targetAccountId, skipDuplicates, columnMapping);
        setPendingFile(null);
        setResult(uploaded);

        // Nothing new landed, so there is nothing for the model to categorize.
        if (uploaded.inserted === 0) {
          setStage("done");
          return;
        }

        setStage("categorizing");
        try {
          setCategorization(await runCategorization());
        } catch (categorizeError) {
          // The import succeeded; surface the categorization failure without discarding it.
          setError(
            categorizeError instanceof Error
              ? `Imported, but categorization failed: ${categorizeError.message}`
              : "Imported, but categorization failed."
          );
        }
        setStage("done");
      } catch (err) {
        // A 422 means the file's columns need a human to confirm or correct them -- that's a
        // mapping form to fill in, not a hard failure, so it gets its own state rather than
        // falling into the generic error banner below.
        if (err instanceof ApiError && err.status === 422) {
          setColumnsNeeded(err.body as ColumnDetectionResult);
          setPendingFile(file);
          setStage("idle");
          return;
        }
        setError(err instanceof Error ? err.message : "Something went wrong.");
        setStage("error");
      }
    },
    [targetAccountId, skipDuplicates]
  );

  const onDrop = (e: React.DragEvent) => {
    e.preventDefault();
    setDragOver(false);
    const file = e.dataTransfer.files?.[0];
    if (file) handleFile(file);
  };

  const cancelMapping = () => {
    setColumnsNeeded(null);
    setPendingFile(null);
  };

  const busy = stage === "uploading" || stage === "categorizing";

  return (
    <div className="mx-auto max-w-2xl px-4 py-10">
      <h1 className="text-2xl font-semibold text-slate-900 dark:text-slate-100">Upload your spending</h1>
      <p className="mt-2 text-slate-600 dark:text-slate-400">
        Upload a bank or credit card statement (CSV or Excel). We'll parse it, categorize each
        transaction, and build spending forecasts and savings suggestions — all on this machine,
        nothing is sent anywhere.
      </p>

      <div className="mt-6 grid gap-4 sm:grid-cols-2">
        <label className="block">
          <span className="text-sm font-medium text-slate-700 dark:text-slate-300">Import into</span>
          <select
            value={targetAccountId}
            onChange={(e) => setTargetAccountId(Number(e.target.value))}
            disabled={busy}
            className="mt-1 w-full rounded-md border border-slate-300 bg-white px-3 py-2 text-sm dark:border-slate-700 dark:bg-slate-900"
          >
            {accounts.map((a) => (
              <option key={a.id} value={a.id}>
                {a.name} · {accountTypeLabel(a.type)}
              </option>
            ))}
          </select>
        </label>

        <label className="flex items-start gap-2 sm:pt-6">
          <input
            type="checkbox"
            checked={skipDuplicates}
            onChange={(e) => setSkipDuplicates(e.target.checked)}
            disabled={busy}
            className="mt-0.5"
          />
          <span className="text-sm text-slate-600 dark:text-slate-400">
            Skip transactions already imported into this account
          </span>
        </label>
      </div>

      {columnsNeeded && pendingFile ? (
        <ColumnMappingForm
          detection={columnsNeeded}
          fileName={pendingFile.name}
          busy={busy}
          onConfirm={(mapping) => handleFile(pendingFile, mapping)}
          onCancel={cancelMapping}
        />
      ) : (
        <div
          onDragOver={(e) => {
            e.preventDefault();
            setDragOver(true);
          }}
          onDragLeave={() => setDragOver(false)}
          onDrop={onDrop}
          onClick={() => !busy && inputRef.current?.click()}
          className={`mt-6 flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed p-12 text-center transition-colors ${
            dragOver
              ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-950/30"
              : "border-slate-300 dark:border-slate-700"
          } ${busy ? "pointer-events-none opacity-60" : ""}`}
        >
          <svg className="h-10 w-10 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
            <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
          </svg>
          <p className="mt-3 text-sm font-medium text-slate-700 dark:text-slate-300">
            Drag &amp; drop a .csv or .xlsx file here, or click to browse
          </p>
          <input
            ref={inputRef}
            type="file"
            accept=".csv,.xlsx,.xls"
            className="hidden"
            onChange={(e) => {
              const file = e.target.files?.[0];
              if (file) handleFile(file);
              e.target.value = "";
            }}
          />
        </div>
      )}

      {busy && (
        <div className="mt-6 flex items-center gap-3 text-sm text-slate-600 dark:text-slate-400">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
          {stage === "uploading" ? "Parsing and storing transactions…" : "Categorizing transactions…"}
        </div>
      )}

      {error && (
        <div className="mt-6 rounded-lg bg-red-50 p-4 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}

      {stage === "done" && result && (
        <div className="mt-6 rounded-lg bg-emerald-50 p-4 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          <p>
            Read <strong>{result.parsed}</strong> transactions from the file into{" "}
            <strong>{result.accountName}</strong>.
          </p>
          <ul className="mt-2 space-y-1">
            <li>
              Imported: <strong>{result.inserted}</strong>
              {result.preCategorized > 0 && ` (${result.preCategorized} already had categories)`}
            </li>
            {result.skippedDuplicates > 0 && (
              <li>
                Skipped as already imported: <strong>{result.skippedDuplicates}</strong>
              </li>
            )}
            {categorization && (
              <>
                {categorization.fromMemory > 0 && (
                  <li>
                    Categorized from merchant memory: <strong>{categorization.fromMemory}</strong>
                  </li>
                )}
                {categorization.fromRules > 0 && (
                  <li>
                    Categorized by the built-in rules: <strong>{categorization.fromRules}</strong>
                  </li>
                )}
                {categorization.unmatched > 0 && (
                  <li>
                    Left for you to categorize: <strong>{categorization.unmatched}</strong>
                  </li>
                )}
              </>
            )}
          </ul>

          {categorization && categorization.unmatched > 0 && (
            <p className="mt-2">
              They're grouped by merchant, so one choice covers every transaction from the same
              place — and it's remembered, so the next statement from there is categorized
              automatically.
            </p>
          )}

          {result.inserted === 0 && result.skippedDuplicates > 0 && (
            <p className="mt-2">
              Everything in this file was already in {result.accountName}, so nothing was added.
            </p>
          )}

          <div className="mt-4 flex flex-wrap gap-2">
            {categorization && categorization.unmatched > 0 && (
              <button
                onClick={onReviewUncategorized}
                className="rounded-md bg-amber-600 px-4 py-2 text-sm font-medium text-white hover:bg-amber-700"
              >
                Review {categorization.unmatched} uncategorized →
              </button>
            )}
            <button
              onClick={onDone}
              className="rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700"
            >
              View dashboard →
            </button>
          </div>
        </div>
      )}
    </div>
  );
}

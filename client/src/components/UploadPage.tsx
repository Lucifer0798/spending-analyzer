import { useCallback, useRef, useState } from "react";
import { runCategorization, uploadFile } from "../api";
import type { Account, UploadResult } from "../types";
import { accountTypeLabel } from "../format";

interface Props {
  accounts: Account[];
  selectedAccountId: number | null;
  onDone: () => void;
}

type Stage = "idle" | "uploading" | "categorizing" | "done" | "error";

export function UploadPage({ accounts, selectedAccountId, onDone }: Props) {
  const [stage, setStage] = useState<Stage>("idle");
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [result, setResult] = useState<UploadResult | null>(null);
  const [categorizedCount, setCategorizedCount] = useState<number | null>(null);
  const [skipDuplicates, setSkipDuplicates] = useState(true);
  const [targetAccountId, setTargetAccountId] = useState<number>(
    selectedAccountId ?? accounts[0]?.id ?? 1
  );
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = useCallback(
    async (file: File) => {
      setError(null);
      setResult(null);
      setCategorizedCount(null);
      setStage("uploading");
      try {
        const uploaded = await uploadFile(file, targetAccountId, skipDuplicates);
        setResult(uploaded);

        // Nothing new landed, so there is nothing for the model to categorize.
        if (uploaded.inserted === 0) {
          setStage("done");
          return;
        }

        setStage("categorizing");
        try {
          const catResult = await runCategorization();
          setCategorizedCount(catResult.categorized);
        } catch (categorizeError) {
          // The import succeeded; surface the AI failure without discarding it.
          setError(
            categorizeError instanceof Error
              ? `Imported, but categorization failed: ${categorizeError.message}`
              : "Imported, but categorization failed."
          );
        }
        setStage("done");
      } catch (err) {
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

  const busy = stage === "uploading" || stage === "categorizing";

  return (
    <div className="mx-auto max-w-2xl px-4 py-10">
      <h1 className="text-2xl font-semibold text-slate-900 dark:text-slate-100">Upload your spending</h1>
      <p className="mt-2 text-slate-600 dark:text-slate-400">
        Upload a bank or credit card statement (CSV or Excel). We'll parse it, categorize each
        transaction with AI, and build spending predictions and savings recommendations.
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

      {busy && (
        <div className="mt-6 flex items-center gap-3 text-sm text-slate-600 dark:text-slate-400">
          <span className="h-4 w-4 animate-spin rounded-full border-2 border-indigo-500 border-t-transparent" />
          {stage === "uploading" ? "Parsing and storing transactions…" : "Categorizing transactions with Claude…"}
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
            {categorizedCount !== null && (
              <li>
                AI categorized: <strong>{categorizedCount}</strong>
              </li>
            )}
          </ul>

          {result.inserted === 0 && result.skippedDuplicates > 0 && (
            <p className="mt-2">
              Everything in this file was already in {result.accountName}, so nothing was added.
            </p>
          )}

          <button
            onClick={onDone}
            className="mt-4 rounded-md bg-emerald-600 px-4 py-2 text-sm font-medium text-white hover:bg-emerald-700"
          >
            View dashboard →
          </button>
        </div>
      )}
    </div>
  );
}

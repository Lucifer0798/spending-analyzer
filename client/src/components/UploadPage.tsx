import { useCallback, useRef, useState } from "react";
import { runCategorization, uploadFile } from "../api";

interface Props {
  onDone: () => void;
}

type Stage = "idle" | "uploading" | "categorizing" | "done" | "error";

export function UploadPage({ onDone }: Props) {
  const [stage, setStage] = useState<Stage>("idle");
  const [error, setError] = useState<string | null>(null);
  const [dragOver, setDragOver] = useState(false);
  const [uploadSummary, setUploadSummary] = useState<{ inserted: number; preCategorized: number } | null>(null);
  const [categorizedCount, setCategorizedCount] = useState<number | null>(null);
  const inputRef = useRef<HTMLInputElement>(null);

  const handleFile = useCallback(async (file: File) => {
    setError(null);
    setUploadSummary(null);
    setCategorizedCount(null);
    setStage("uploading");
    try {
      const result = await uploadFile(file);
      setUploadSummary({ inserted: result.inserted, preCategorized: result.preCategorized });

      setStage("categorizing");
      const catResult = await runCategorization();
      setCategorizedCount(catResult.categorized);

      setStage("done");
    } catch (err) {
      setError(err instanceof Error ? err.message : "Something went wrong.");
      setStage("error");
    }
  }, []);

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

      <div
        onDragOver={(e) => {
          e.preventDefault();
          setDragOver(true);
        }}
        onDragLeave={() => setDragOver(false)}
        onDrop={onDrop}
        onClick={() => inputRef.current?.click()}
        className={`mt-8 flex cursor-pointer flex-col items-center justify-center rounded-xl border-2 border-dashed p-12 text-center transition-colors ${
          dragOver
            ? "border-indigo-500 bg-indigo-50 dark:bg-indigo-950/30"
            : "border-slate-300 dark:border-slate-700"
        }`}
      >
        <svg className="h-10 w-10 text-slate-400" fill="none" viewBox="0 0 24 24" stroke="currentColor">
          <path strokeLinecap="round" strokeLinejoin="round" strokeWidth={1.5} d="M3 16.5v2.25A2.25 2.25 0 005.25 21h13.5A2.25 2.25 0 0021 18.75V16.5M16.5 12L12 16.5m0 0L7.5 12m4.5 4.5V3" />
        </svg>
        <p className="mt-3 text-sm font-medium text-slate-700 dark:text-slate-300">
          Drag & drop a .csv or .xlsx file here, or click to browse
        </p>
        <input
          ref={inputRef}
          type="file"
          accept=".csv,.xlsx,.xls"
          className="hidden"
          onChange={(e) => {
            const file = e.target.files?.[0];
            if (file) handleFile(file);
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

      {stage === "done" && uploadSummary && (
        <div className="mt-6 rounded-lg bg-emerald-50 p-4 text-sm text-emerald-800 dark:bg-emerald-950/40 dark:text-emerald-300">
          <p>
            Imported <strong>{uploadSummary.inserted}</strong> transactions
            {uploadSummary.preCategorized > 0 && ` (${uploadSummary.preCategorized} already had categories)`}.
          </p>
          {categorizedCount !== null && <p className="mt-1">AI categorized {categorizedCount} transactions.</p>}
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

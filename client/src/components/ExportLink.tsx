interface Props {
  href: string;
  label: string;
  /** Shown when there is nothing to export, so the control stays visible but inert. */
  disabled?: boolean;
  title?: string;
  /** Text-sized, for sitting beside a chart heading rather than a page heading. */
  compact?: boolean;
}

/**
 * A CSV download, rendered as a link rather than a button because that is what it is: a plain
 * GET the browser can handle itself, with the filename coming from the response.
 */
export function ExportLink({ href, label, disabled = false, title, compact = false }: Props) {
  const base = compact
    ? "text-xs font-medium"
    : "inline-flex items-center gap-1.5 rounded-md border px-3 py-1.5 text-sm font-medium transition-colors";

  const enabled = compact
    ? "text-indigo-600 hover:text-indigo-500 hover:underline dark:text-indigo-400"
    : "border-slate-300 text-slate-700 hover:bg-slate-100 dark:border-slate-700 dark:text-slate-300 dark:hover:bg-slate-800";

  const off = compact
    ? "cursor-not-allowed text-slate-400 dark:text-slate-600"
    : "cursor-not-allowed border-slate-200 text-slate-400 dark:border-slate-800 dark:text-slate-600";

  if (disabled) {
    return (
      <span
        className={`${base} ${off}`}
        title={title ?? "Nothing to export yet"}
        aria-disabled="true"
      >
        {label}
      </span>
    );
  }

  return (
    <a href={href} download title={title} className={`${base} ${enabled}`}>
      {label}
    </a>
  );
}

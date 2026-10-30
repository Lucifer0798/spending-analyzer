import { useEffect, useState } from "react";
import { clearMerchantNames, dismissMerchantSuggestion, fetchMerchantNames, renameMerchants } from "../../api";
import type { MerchantNameEntry, MerchantNamesResponse } from "../../types";
import type { Run } from "./types";

/** Shown before "Show all" when not searching. */
const PREVIEW = 15;

const inputClass =
  "w-full rounded-md border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900";

/**
 * Friendly names for merchants ("AMZN MKTP US" → "Amazon"), and merging look-alikes by giving them
 * one name. Top merchants, the year review, recurring and search all use the name; the descriptions
 * themselves never change.
 */
export function MerchantNamesSection({ run, refreshKey }: { run: Run; refreshKey: unknown }) {
  const [data, setData] = useState<MerchantNamesResponse | null>(null);
  const [search, setSearch] = useState("");
  const [showAll, setShowAll] = useState(false);
  // Typed-but-unsaved names, by key; a key with no entry shows its saved name.
  const [drafts, setDrafts] = useState<Record<string, string>>({});
  const [selected, setSelected] = useState<Set<string>>(new Set());
  const [mergeName, setMergeName] = useState("");
  const [suggestionNames, setSuggestionNames] = useState<Record<string, string>>({});

  const load = () => {
    fetchMerchantNames()
      .then(setData)
      .catch(() => setData(null));
  };

  // Follows the page's own reloads, so every write (all go through `run`) shows up here.
  useEffect(load, [refreshKey]);

  if (!data) return null;

  const term = search.trim().toLowerCase();
  const matching = term
    ? data.merchants.filter((m) =>
        [m.key, m.name ?? "", m.example ?? ""].some((s) => s.toLowerCase().includes(term))
      )
    : data.merchants;
  const shown = term || showAll ? matching : matching.slice(0, PREVIEW);
  const existingNames = new Set(data.merchants.flatMap((m) => (m.name ? [m.name.toLowerCase()] : [])));

  /** `run`, then `after` only if the write went through -- on failure the banner shows why, and
   *  what was typed stays put to try again. */
  const write = async (action: () => Promise<unknown>, message: string | undefined, after?: () => void) => {
    let ok = false;
    await run(async () => {
      await action();
      ok = true;
    }, message);
    if (ok) after?.();
  };

  const draftOf = (m: MerchantNameEntry) => drafts[m.key] ?? m.name ?? "";
  const forget = (keys: string[]) =>
    setDrafts((d) => {
      const next = { ...d };
      for (const k of keys) delete next[k];
      return next;
    });

  const saveName = (m: MerchantNameEntry) => {
    const name = draftOf(m).trim();
    if (!name) {
      if (m.name) write(() => clearMerchantNames([m.key]), `${m.key} shows as itself again.`, () => forget([m.key]));
      return;
    }
    // The server keeps an existing name's spelling when this one only differs in case -- say that one.
    const stored = data.merchants.find((o) => o.key !== m.key && o.name?.toLowerCase() === name.toLowerCase())?.name ?? name;
    write(() => renameMerchants([m.key], name), `${m.key} is now "${stored}".`, () => forget([m.key]));
  };

  const toggle = (key: string) =>
    setSelected((prev) => {
      const next = new Set(prev);
      if (next.has(key)) next.delete(key);
      else next.add(key);
      return next;
    });

  const mergeSelected = () => {
    const name = mergeName.trim();
    if (!name) return;
    const keys = [...selected];
    write(() => renameMerchants(keys, name), `Merged ${keys.length} merchants as "${name}".`, () => {
      setSelected(new Set());
      setMergeName("");
      forget(keys);
    });
  };

  return (
    <section className="mt-10">
      <h2 className="text-sm font-semibold uppercase tracking-wide text-slate-500">Merchant names</h2>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Give a merchant a name you'd recognise, or give several spellings the same name to merge them. Top
        merchants, the year review, recurring and search all use it; the transactions' own descriptions
        don't change.
      </p>

      {data.suggestions.length > 0 && (
        <div className="mt-4 rounded-lg border border-indigo-200 bg-indigo-50/60 p-4 dark:border-indigo-900/50 dark:bg-indigo-950/20">
          <p className="text-sm font-medium text-indigo-900 dark:text-indigo-200">These might each be one merchant</p>
          <ul className="mt-2 divide-y divide-indigo-200/70 dark:divide-indigo-900/50">
            {data.suggestions.map((s) => (
              <li key={s.stem} className="flex flex-wrap items-center gap-x-3 gap-y-2 py-2.5">
                <p className="min-w-0 flex-1 basis-60 text-xs text-slate-700 dark:text-slate-300">
                  {s.keys.join(" · ")}
                  <span className="text-slate-500"> — {s.count} transactions</span>
                </p>
                <input
                  aria-label={`Name for ${s.keys.join(", ")}`}
                  value={suggestionNames[s.stem] ?? s.name}
                  onChange={(e) => setSuggestionNames((d) => ({ ...d, [s.stem]: e.target.value }))}
                  className={`${inputClass} w-40 sm:w-44`}
                />
                <button
                  disabled={!(suggestionNames[s.stem] ?? s.name).trim()}
                  onClick={() => {
                    const name = (suggestionNames[s.stem] ?? s.name).trim();
                    run(() => renameMerchants(s.keys, name), `Merged ${s.keys.length} merchants as "${name}".`);
                  }}
                  className="rounded-md bg-indigo-600 px-3 py-1 text-xs font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
                >
                  Merge
                </button>
                <button
                  onClick={() => run(() => dismissMerchantSuggestion(s.stem))}
                  className="rounded-md px-2 py-1 text-xs text-slate-600 hover:bg-white dark:text-slate-400 dark:hover:bg-slate-800"
                >
                  Not the same
                </button>
              </li>
            ))}
          </ul>
        </div>
      )}

      {data.merchants.length === 0 ? (
        <p className="mt-4 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No merchants yet. Import a statement and every merchant in it shows up here.
        </p>
      ) : (
        <>
          <input
            value={search}
            onChange={(e) => setSearch(e.target.value)}
            placeholder={`Find one of ${data.merchants.length} merchants…`}
            className="mt-4 w-full rounded-md border border-slate-300 bg-white px-3 py-1.5 text-sm sm:w-72 dark:border-slate-700 dark:bg-slate-900"
          />

          {selected.size > 0 && (
            <div className="mt-3 flex flex-wrap items-center gap-2 rounded-lg border border-indigo-200 bg-indigo-50 p-3 text-sm dark:border-indigo-900/50 dark:bg-indigo-950/30">
              <span className="font-medium text-indigo-900 dark:text-indigo-200">{selected.size} selected</span>
              <input
                value={mergeName}
                onChange={(e) => setMergeName(e.target.value)}
                onKeyDown={(e) => {
                  if (e.key === "Enter") mergeSelected();
                }}
                placeholder="One name for all of them"
                className={`${inputClass} w-52`}
              />
              <button
                disabled={selected.size < 2 || !mergeName.trim()}
                onClick={mergeSelected}
                title={selected.size < 2 ? "Tick at least two to merge" : undefined}
                className="rounded-md bg-indigo-600 px-3 py-1 text-sm font-medium text-white hover:bg-indigo-700 disabled:opacity-50"
              >
                Merge
              </button>
              <button
                onClick={() => setSelected(new Set())}
                className="ml-auto rounded-md px-3 py-1 text-sm text-slate-600 hover:bg-slate-200 dark:text-slate-400 dark:hover:bg-slate-800"
              >
                Clear selection
              </button>
            </div>
          )}

          <ul className="mt-3 divide-y divide-slate-100 overflow-hidden rounded-lg border border-slate-200 bg-white dark:divide-slate-800 dark:border-slate-800 dark:bg-slate-950">
            {shown.map((m) => {
              const draft = draftOf(m);
              const changed = draft.trim() !== (m.name ?? "");
              const joins =
                changed && draft.trim() !== "" && existingNames.has(draft.trim().toLowerCase())
                  ? data.merchants.filter((o) => o.key !== m.key && o.name?.toLowerCase() === draft.trim().toLowerCase()).length
                  : 0;
              return (
                <li key={m.key} className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-2.5">
                  <input
                    type="checkbox"
                    aria-label={`Select ${m.key}`}
                    checked={selected.has(m.key)}
                    onChange={() => toggle(m.key)}
                  />
                  <div className="min-w-0 flex-1 basis-48">
                    <p className="truncate text-sm text-slate-800 dark:text-slate-200" title={m.key}>
                      {m.key}
                    </p>
                    <p className="truncate text-xs text-slate-500" title={m.example ?? undefined}>
                      {m.count === 0
                        ? "No transactions now"
                        : `${m.count} transaction${m.count === 1 ? "" : "s"}${m.example && m.example.toUpperCase() !== m.key ? ` · e.g. ${m.example}` : ""}`}
                    </p>
                  </div>
                  <div className="flex w-full items-center gap-1 sm:w-auto">
                    <input
                      aria-label={`Name for ${m.key}`}
                      value={draft}
                      onChange={(e) => setDrafts((d) => ({ ...d, [m.key]: e.target.value }))}
                      onKeyDown={(e) => {
                        if (e.key === "Enter" && changed) saveName(m);
                      }}
                      placeholder="Add a name"
                      className={`${inputClass} sm:w-48`}
                    />
                    {changed && (
                      <button
                        onClick={() => saveName(m)}
                        className="rounded-md bg-indigo-600 px-2.5 py-1 text-xs font-medium text-white hover:bg-indigo-700"
                      >
                        {draft.trim() ? "Save" : "Remove"}
                      </button>
                    )}
                  </div>
                  {joins > 0 && (
                    <p className="w-full text-xs text-indigo-700 sm:text-right dark:text-indigo-400">
                      Merges it with {joins} other merchant{joins === 1 ? "" : "s"} already called that.
                    </p>
                  )}
                </li>
              );
            })}
          </ul>
          {term && matching.length === 0 && <p className="mt-2 text-xs text-slate-500">No merchant matches "{search}".</p>}
          {!term && matching.length > PREVIEW && (
            <button onClick={() => setShowAll((s) => !s)} className="mt-1 text-xs text-indigo-600 hover:underline dark:text-indigo-400">
              {showAll ? "Show fewer" : `Show all ${matching.length}`}
            </button>
          )}
        </>
      )}
    </section>
  );
}

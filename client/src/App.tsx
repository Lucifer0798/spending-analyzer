import { useCallback, useEffect, useState } from "react";
import { UploadPage } from "./components/UploadPage";
import { Dashboard } from "./components/Dashboard";
import { TransactionsTable } from "./components/TransactionsTable";
import { RecurringPage } from "./components/RecurringPage";
import { ManagePage } from "./components/ManagePage";
import { DateRangePicker } from "./components/DateRangePicker";
import { LoginScreen } from "./components/LoginScreen";
import {
  fetchAccounts,
  fetchAuthStatus,
  fetchDateBounds,
  logout,
  resetAllData,
  setUnauthorizedHandler,
} from "./api";
import type { Account, AuthStatus, DateBounds, DateRangeValue } from "./types";
import { ALL_TIME } from "./types";

type Tab = "upload" | "dashboard" | "transactions" | "recurring" | "manage";

const TABS: { id: Tab; label: string }[] = [
  { id: "upload", label: "Upload" },
  { id: "dashboard", label: "Dashboard" },
  { id: "transactions", label: "Transactions" },
  { id: "recurring", label: "Recurring" },
  { id: "manage", label: "Manage" },
];

function App() {
  const [tab, setTab] = useState<Tab>("upload");
  const [accounts, setAccounts] = useState<Account[]>([]);
  // null means "all accounts" — the filter is omitted from requests entirely.
  const [accountId, setAccountId] = useState<number | null>(null);
  const [range, setRange] = useState<DateRangeValue>(ALL_TIME);
  const [bounds, setBounds] = useState<DateBounds | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  // null while we are still asking the server whether there is a password at all.
  const [auth, setAuth] = useState<AuthStatus | null>(null);

  const signedIn = auth?.authenticated ?? false;

  useEffect(() => {
    // A session can expire while the app is open; this sends the user back to the login screen
    // instead of leaving them looking at a page of failed requests.
    setUnauthorizedHandler(() => setAuth({ authRequired: true, authenticated: false }));

    // Also what makes the server issue the CSRF cookie, which every later write needs.
    fetchAuthStatus()
      .then(setAuth)
      .catch(() => setAuth({ authRequired: true, authenticated: false }));

    return () => setUnauthorizedHandler(null);
  }, []);

  const loadAccounts = useCallback(() => {
    if (!signedIn) return;
    fetchAccounts().then((r) => setAccounts(r.accounts)).catch(() => setAccounts([]));
  }, [signedIn]);

  useEffect(() => {
    loadAccounts();
  }, [loadAccounts]);

  // Bounds anchor the date presets, and shift when the account filter changes.
  useEffect(() => {
    if (!signedIn) return;
    fetchDateBounds(accountId).then(setBounds).catch(() => setBounds(null));
  }, [accountId, refreshKey, signedIn]);

  const handleReset = async () => {
    if (!confirm("This will delete all imported transactions. Accounts and categories are kept. Continue?")) {
      return;
    }
    await resetAllData();
    loadAccounts();
    setRange(ALL_TIME);
    setRefreshKey((k) => k + 1);
    setTab("upload");
  };

  const handleSignOut = async () => {
    try {
      await logout();
    } finally {
      // Sign out locally even if the request failed — the session is unusable either way.
      setAuth({ authRequired: true, authenticated: false });
    }
  };

  // The filters only apply to views that show transaction data.
  const showFilters = tab !== "manage" && tab !== "upload";

  if (!auth) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-slate-50 text-sm text-slate-500 dark:bg-slate-950">
        Loading…
      </div>
    );
  }

  if (!auth.authenticated) {
    return <LoginScreen onSignedIn={() => setAuth({ authRequired: true, authenticated: true })} />;
  }

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <span className="text-lg font-semibold text-slate-900 dark:text-slate-100">Spending Analyzer</span>

          <nav className="flex flex-wrap items-center gap-1">
            {showFilters && (
              <>
                <DateRangePicker value={range} bounds={bounds} onChange={setRange} />
                {accounts.length > 0 && (
                  <select
                    value={accountId ?? ""}
                    onChange={(e) => setAccountId(e.target.value === "" ? null : Number(e.target.value))}
                    className="mr-2 rounded-md border border-slate-300 bg-white px-2 py-1.5 text-sm dark:border-slate-700 dark:bg-slate-900"
                    title="Filter by account"
                  >
                    <option value="">All accounts</option>
                    {accounts.map((a) => (
                      <option key={a.id} value={a.id}>
                        {a.name}
                      </option>
                    ))}
                  </select>
                )}
              </>
            )}

            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                  tab === t.id
                    ? "bg-indigo-600 text-white"
                    : "text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                }`}
              >
                {t.label}
              </button>
            ))}

            <button
              onClick={handleReset}
              className="ml-2 rounded-md px-3 py-1.5 text-sm font-medium text-slate-400 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
              title="Delete all transactions"
            >
              Reset
            </button>

            {/* Only meaningful when there is a password; an open instance has nothing to sign out of. */}
            {auth.authRequired && (
              <button
                onClick={handleSignOut}
                className="rounded-md px-3 py-1.5 text-sm font-medium text-slate-400 hover:bg-slate-100 hover:text-slate-700 dark:hover:bg-slate-800"
                title="Sign out"
              >
                Sign out
              </button>
            )}
          </nav>
        </div>
      </header>

      <main key={refreshKey}>
        {tab === "upload" && (
          <UploadPage
            accounts={accounts}
            selectedAccountId={accountId}
            onDone={() => {
              loadAccounts();
              setRefreshKey((k) => k + 1);
              setTab("dashboard");
            }}
          />
        )}
        {tab === "dashboard" && <Dashboard accountId={accountId} range={range} />}
        {tab === "transactions" && <TransactionsTable accountId={accountId} range={range} />}
        {tab === "recurring" && <RecurringPage accountId={accountId} range={range} />}
        {tab === "manage" && <ManagePage onAccountsChanged={loadAccounts} />}
      </main>
    </div>
  );
}

export default App;

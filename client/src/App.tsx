import { useCallback, useEffect, useState } from "react";
import { UploadPage } from "./components/UploadPage";
import { Dashboard } from "./components/Dashboard";
import { TransactionsTable } from "./components/TransactionsTable";
import { RecurringPage } from "./components/RecurringPage";
import { GoalsPage } from "./components/GoalsPage";
import { NetWorthPage } from "./components/NetWorthPage";
import { YearReviewPage } from "./components/YearReviewPage";
import { CategoryDetailPage } from "./components/CategoryDetailPage";
import { ManagePage } from "./components/ManagePage";
import { DateRangePicker } from "./components/DateRangePicker";
import { LoginScreen } from "./components/LoginScreen";
import { ThemeToggle } from "./components/ThemeToggle";
import { promptInstall, useCanInstall, useOnline } from "./pwa";
import {
  fetchAccounts,
  fetchAuthStatus,
  fetchBudgets,
  fetchDateBounds,
  logout,
  resetAllData,
  setUnauthorizedHandler,
  ApiError,
} from "./api";
import type { Account, AuthStatus, BudgetSummary, DateBounds, DateRangeValue } from "./types";
import { ALL_TIME } from "./types";

// "category" is reached by drilling into a Dashboard bar, so it has no button in TABS.
type Tab = "upload" | "dashboard" | "transactions" | "recurring" | "year" | "goals" | "net-worth" | "manage" | "category";

const TABS: { id: Tab; label: string }[] = [
  { id: "upload", label: "Upload" },
  { id: "dashboard", label: "Dashboard" },
  { id: "transactions", label: "Transactions" },
  { id: "recurring", label: "Recurring" },
  { id: "year", label: "Year" },
  { id: "goals", label: "Goals" },
  { id: "net-worth", label: "Net Worth" },
  { id: "manage", label: "Manage" },
];

function App() {
  const [tab, setTab] = useState<Tab>("upload");
  const [accounts, setAccounts] = useState<Account[]>([]);
  // null means "all accounts" — the filter is omitted from requests entirely.
  const [accountId, setAccountId] = useState<number | null>(null);
  const [range, setRange] = useState<DateRangeValue>(ALL_TIME);
  // A search handed to the Transactions page by a merchant link elsewhere. TransactionsTable reads
  // it only when it mounts, and switching tabs remounts it, so this needs no clearing in step --
  // the nav buttons just reset it so opening Transactions directly starts unfiltered.
  const [transactionSearch, setTransactionSearch] = useState("");
  // The category a Dashboard bar drilled into, for the "category" tab.
  const [openedCategory, setOpenedCategory] = useState<string | null>(null);
  // Same hand-off as transactionSearch, for the category filter.
  const [transactionCategory, setTransactionCategory] = useState("");
  // Same hand-off as transactionSearch: read once when Transactions mounts, cleared by the nav.
  const [reviewUncategorized, setReviewUncategorized] = useState(false);
  const [bounds, setBounds] = useState<DateBounds | null>(null);
  const [budgets, setBudgets] = useState<BudgetSummary | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  // null while we are still asking the server whether there is a password at all.
  const [auth, setAuth] = useState<AuthStatus | null>(null);

  const signedIn = auth?.authenticated ?? false;
  const online = useOnline();
  const canInstall = useCanInstall();
  // The device is online but the server didn't answer (down, restarting, or a proxy's 502) --
  // distinct from a "no" to the auth check, which means show the login screen.
  const [unreachable, setUnreachable] = useState(false);

  useEffect(() => {
    // A session can expire while the app is open; this sends the user back to the login screen
    // instead of leaving them looking at a page of failed requests.
    setUnauthorizedHandler(() => setAuth({ authRequired: true, authenticated: false }));
    return () => setUnauthorizedHandler(null);
  }, []);

  // Also what makes the server issue the CSRF cookie, which every later write needs.
  const checkAuth = useCallback(() => {
    fetchAuthStatus()
      .then((status) => {
        setUnreachable(false);
        setAuth(status);
      })
      .catch((e) => {
        // fetch rejects with a TypeError when nothing answered at all.
        if (e instanceof TypeError || (e instanceof ApiError && e.status >= 500)) setUnreachable(true);
        else setAuth({ authRequired: true, authenticated: false });
      });
  }, []);

  useEffect(() => {
    // Opened offline (the installed app can start without a connection), there's no server to ask
    // yet; this runs again when the connection comes back.
    if (online) checkAuth();
  }, [online, checkAuth]);

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

  // Drives the header's over-budget badge, so a blown budget is visible from any tab rather
  // than only when the Dashboard happens to be open. No month is passed — same as the newest-
  // month default the Dashboard's own budget card uses — so this always answers "right now".
  useEffect(() => {
    if (!signedIn) return;
    fetchBudgets(accountId).then(setBudgets).catch(() => setBudgets(null));
  }, [accountId, refreshKey, signedIn]);

  const overBudget = budgets?.budgets.filter((b) => b.status === "over") ?? [];

  const openMerchant = (merchant: string) => {
    setTransactionSearch(merchant);
    setTransactionCategory("");
    setTab("transactions");
  };

  const openCategory = (category: string) => {
    setOpenedCategory(category);
    setTab("category");
  };

  const openCategoryTransactions = (category: string) => {
    setTransactionSearch("");
    setTransactionCategory(category);
    setTab("transactions");
  };

  // A calendar day click: narrow the shared date filter to that day and show its transactions.
  // The filter stays set afterwards, visible in the picker, so getting back is one click on it.
  const openDay = (date: string) => {
    setTransactionSearch("");
    setTransactionCategory("");
    setRange({ from: date, to: date });
    setTab("transactions");
  };

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

  // The filters only apply to views that show transaction data. Goals and net worth aren't
  // transaction-scoped at all -- they're measured from manually-logged amounts, not imports.
  const showFilters = tab !== "manage" && tab !== "upload" && tab !== "goals" && tab !== "net-worth";

  // Nothing is stored on the device -- every figure comes from the server -- so offline there is
  // nothing to show beyond saying so.
  const offlineBanner = !online && (
    <div role="status" className="bg-amber-100 px-4 py-2 text-center text-sm text-amber-900 dark:bg-amber-950/60 dark:text-amber-200">
      You're offline. Your data lives on the server, so nothing can load until the connection is back.
    </div>
  );

  if (!auth) {
    return (
      <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
        {offlineBanner}
        <div className="flex min-h-[50vh] flex-col items-center justify-center gap-3 text-sm text-slate-500">
          {!online ? (
            "Waiting for a connection…"
          ) : unreachable ? (
            <>
              <p>Can't reach the Spending Analyzer server right now.</p>
              <button
                onClick={checkAuth}
                className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-700"
              >
                Try again
              </button>
            </>
          ) : (
            "Loading…"
          )}
        </div>
      </div>
    );
  }

  if (!auth.authenticated) {
    return (
      <>
        {offlineBanner}
        <LoginScreen onSignedIn={() => setAuth({ authRequired: true, authenticated: true })} />
      </>
    );
  }

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      {offlineBanner}
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-5xl flex-wrap items-center justify-between gap-3 px-4 py-3">
          <span className="text-lg font-semibold text-slate-900 dark:text-slate-100">Spending Analyzer</span>

          <nav className="flex flex-wrap items-center gap-1">
            {showFilters && (
              <>
                {/* The year is the range on the Year tab, so the date filter would only mislead there. */}
                {tab !== "year" && <DateRangePicker value={range} bounds={bounds} onChange={setRange} />}
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

            {overBudget.length > 0 && (
              <button
                onClick={() => setTab("dashboard")}
                title={`Over budget: ${overBudget.map((b) => b.category).join(", ")}`}
                className="mr-1 rounded-md bg-red-100 px-2.5 py-1.5 text-sm font-medium text-red-700 hover:bg-red-200 dark:bg-red-950/50 dark:text-red-300 dark:hover:bg-red-950"
              >
                ⚠ {overBudget.length} over budget
              </button>
            )}

            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => {
                  setTransactionSearch("");
                  setTransactionCategory("");
                  setReviewUncategorized(false);
                  setTab(t.id);
                }}
                className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                  tab === t.id
                    ? "bg-indigo-600 text-white"
                    : "text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                }`}
              >
                {t.label}
              </button>
            ))}

            {/* Only where the browser offers installing (Chrome, Edge); Safari is Share → Add to Home Screen. */}
            {canInstall && (
              <button
                onClick={() => promptInstall()}
                title="Install Spending Analyzer as an app"
                className="rounded-md px-3 py-1.5 text-sm font-medium text-indigo-600 hover:bg-indigo-50 dark:text-indigo-400 dark:hover:bg-slate-800"
              >
                Install app
              </button>
            )}

            <ThemeToggle />

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
            onReviewUncategorized={() => {
              loadAccounts();
              setRefreshKey((k) => k + 1);
              setTransactionSearch("");
              setReviewUncategorized(true);
              setTab("transactions");
            }}
          />
        )}
        {tab === "dashboard" && (
          <Dashboard
            accountId={accountId}
            range={range}
            onOpenMerchant={openMerchant}
            onOpenDay={openDay}
            onOpenCategory={openCategory}
          />
        )}
        {tab === "category" && openedCategory && (
          <CategoryDetailPage
            category={openedCategory}
            accountId={accountId}
            range={range}
            onBack={() => setTab("dashboard")}
            onOpenMerchant={openMerchant}
            onOpenTransactions={openCategoryTransactions}
          />
        )}
        {tab === "transactions" && (
          <TransactionsTable
            accountId={accountId}
            range={range}
            onAccountIdChange={setAccountId}
            onRangeChange={setRange}
            initialSearch={transactionSearch}
            initialCategory={transactionCategory}
            initialReviewOpen={reviewUncategorized}
          />
        )}
        {tab === "recurring" && <RecurringPage accountId={accountId} range={range} />}
        {tab === "year" && <YearReviewPage accountId={accountId} onOpenMerchant={openMerchant} />}
        {tab === "goals" && <GoalsPage />}
        {tab === "net-worth" && <NetWorthPage />}
        {tab === "manage" && <ManagePage onAccountsChanged={loadAccounts} />}
      </main>
    </div>
  );
}

export default App;

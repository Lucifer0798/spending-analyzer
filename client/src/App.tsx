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
import { AlertsMenu, type AlertItem } from "./components/AlertsMenu";
import { promptInstall, useCanInstall, useOnline } from "./pwa";
import {
  fetchAccounts,
  fetchAuthStatus,
  fetchBudgets,
  fetchAccountFreshness,
  fetchCashFlow,
  fetchDuplicateCharges,
  fetchDateBounds,
  logout,
  resetAllData,
  setUnauthorizedHandler,
  ApiError,
} from "./api";
import type {
  Account,
  AuthStatus,
  BudgetSummary,
  CashFlowForecast,
  DateBounds,
  DateRangeValue,
  DuplicateChargeGroup,
  FreshnessResponse,
} from "./types";
import { ALL_TIME } from "./types";
import { dayMonth } from "./dates";

/**
 * What a page is handed when another page opens it -- a merchant to search for, a review panel to
 * start open, an account to preselect. The page reads it once, when it mounts. Every navigation goes
 * through `go`, which replaces the whole thing, so nothing handed to one visit leaks into the next
 * and opening a tab from the nav (no hand-off) starts it plain.
 */
interface Handoff {
  /** Transactions: pre-filled description search (a merchant link). */
  search?: string;
  /** Transactions: pre-selected category filter (from a category's page). */
  category?: string;
  /** Transactions: open the uncategorized review (an import summary's "Review uncategorized"). */
  reviewUncategorized?: boolean;
  /** Transactions: open the transfer review (an import summary's "Review transfers"). */
  reviewTransfers?: boolean;
  /** Transactions: open the double-charge review (the alerts menu's "possible double charges"). */
  reviewDuplicates?: boolean;
  /** Category tab: which category a Dashboard bar drilled into. */
  openedCategory?: string;
  /** Net Worth: scroll to the cash-flow forecast (a low-balance alert). */
  showCashFlow?: boolean;
  /** Upload: preselect this account (a statement-due alert). */
  uploadAccountId?: number;
}

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
  const [handoff, setHandoff] = useState<Handoff>({});
  const [bounds, setBounds] = useState<DateBounds | null>(null);
  const [budgets, setBudgets] = useState<BudgetSummary | null>(null);
  const [cashFlow, setCashFlow] = useState<CashFlowForecast | null>(null);
  const [freshness, setFreshness] = useState<FreshnessResponse | null>(null);
  const [refreshKey, setRefreshKey] = useState(0);
  // null while we are still asking the server whether there is a password at all.
  const [auth, setAuth] = useState<AuthStatus | null>(null);

  const signedIn = auth?.authenticated ?? false;

  /** Opens a tab, handing it `handed` (or nothing) -- see {@link Handoff}. */
  const go = (to: Tab, handed: Handoff = {}) => {
    setHandoff(handed);
    setTab(to);
  };
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

  // Drives the header's over-budget alert, so a blown budget is visible from any tab rather
  // than only when the Dashboard happens to be open. No month is passed — same as the newest-
  // month default the Dashboard's own budget card uses — so this always answers "right now".
  useEffect(() => {
    if (!signedIn) return;
    fetchBudgets(accountId).then(setBudgets).catch(() => setBudgets(null));
  }, [accountId, refreshKey, signedIn]);

  const overBudget = budgets?.budgets.filter((b) => b.status === "over") ?? [];

  // Drives the header's low-balance alerts, like the over-budget one. Not account-filtered: a
  // checking account about to run dry matters whichever account is selected. Re-checked on every
  // tab change, so logging a balance on Net Worth updates it by the time you look elsewhere.
  useEffect(() => {
    if (!signedIn) return;
    fetchCashFlow().then(setCashFlow).catch(() => setCashFlow(null));
  }, [refreshKey, signedIn, tab]);

  const lowBalance = cashFlow?.accounts.filter((a) => a.warn && a.firstNegativeDate) ?? [];

  // Drives the "statement due" alerts. Same cadence as the low-balance check: an import on the
  // Upload page or a balance on Net Worth shows up by the next tab change.
  useEffect(() => {
    if (!signedIn) return;
    fetchAccountFreshness().then(setFreshness).catch(() => setFreshness(null));
  }, [refreshKey, signedIn, tab]);

  const statementsDue = freshness?.accounts.filter((a) => a.statementDue) ?? [];

  // Drives the "possible double charges" alert. Follows the account filter, like the budgets alert,
  // so the count matches what the Transactions page's own review lists when it's opened.
  const [duplicates, setDuplicates] = useState<DuplicateChargeGroup[]>([]);
  useEffect(() => {
    if (!signedIn) return;
    fetchDuplicateCharges(accountId).then((r) => setDuplicates(r.groups)).catch(() => setDuplicates([]));
  }, [accountId, refreshKey, signedIn, tab]);

  // Serious first: money already overspent or about to run out; then things to check -- charges
  // that look taken twice, statements to import.
  const alerts: AlertItem[] = [
    ...(overBudget.length > 0
      ? [
          {
            key: "over-budget",
            tone: "serious" as const,
            title: overBudget.length === 1 ? "1 budget overspent" : `${overBudget.length} budgets overspent`,
            detail: overBudget.map((b) => b.category).join(", "),
            onSelect: () => go("dashboard"),
          },
        ]
      : []),
    ...lowBalance.map((a) => ({
      key: `low-${a.accountId}`,
      tone: "serious" as const,
      title: `${a.accountName} running low`,
      detail: `Projected below zero on ${dayMonth(a.firstNegativeDate!)}`,
      onSelect: () => go("net-worth", { showCashFlow: true }),
    })),
    ...(duplicates.length > 0
      ? [
          {
            key: "double-charges",
            tone: "nudge" as const,
            title: duplicates.length === 1 ? "1 possible double charge" : `${duplicates.length} possible double charges`,
            detail: [...new Set(duplicates.map((g) => g.merchant))].join(", "),
            onSelect: () => go("transactions", { reviewDuplicates: true }),
          },
        ]
      : []),
    ...statementsDue.map((a) => ({
      key: `statement-${a.accountId}`,
      tone: "nudge" as const,
      title: `${a.accountName} statement due`,
      detail: `Newest transaction ${a.daysSinceTransaction} days ago`,
      onSelect: () => go("upload", { uploadAccountId: a.accountId }),
    })),
  ];

  const openMerchant = (merchant: string) => go("transactions", { search: merchant });
  const openCategory = (category: string) => go("category", { openedCategory: category });
  const openCategoryTransactions = (category: string) => go("transactions", { category });

  // A calendar day click: narrow the shared date filter to that day and show its transactions.
  // The filter stays set afterwards, visible in the picker, so getting back is one click on it.
  const openDay = (date: string) => {
    setRange({ from: date, to: date });
    go("transactions");
  };

  const handleReset = async () => {
    if (!confirm("This will delete all imported transactions. Accounts and categories are kept. Continue?")) {
      return;
    }
    await resetAllData();
    loadAccounts();
    setRange(ALL_TIME);
    setRefreshKey((k) => k + 1);
    go("upload");
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

            <AlertsMenu items={alerts} />

            {TABS.map((t) => (
              <button
                key={t.id}
                onClick={() => go(t.id)}
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
            // An alert can pick an account while Upload is already open; remounting is what makes
            // the page re-read that hand-off, the same way switching to the tab would.
            key={handoff.uploadAccountId ?? "no-pick"}
            accounts={accounts}
            selectedAccountId={handoff.uploadAccountId ?? accountId}
            onDone={() => {
              loadAccounts();
              setRefreshKey((k) => k + 1);
              go("dashboard");
            }}
            onReviewUncategorized={() => {
              loadAccounts();
              setRefreshKey((k) => k + 1);
              go("transactions", { reviewUncategorized: true });
            }}
            onReviewTransfers={() => {
              loadAccounts();
              setRefreshKey((k) => k + 1);
              go("transactions", { reviewTransfers: true });
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
        {tab === "category" && handoff.openedCategory && (
          <CategoryDetailPage
            category={handoff.openedCategory}
            accountId={accountId}
            range={range}
            onBack={() => go("dashboard")}
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
            initialSearch={handoff.search}
            initialCategory={handoff.category}
            initialReviewOpen={handoff.reviewUncategorized}
            initialTransfersOpen={handoff.reviewTransfers}
            initialDuplicatesOpen={handoff.reviewDuplicates}
          />
        )}
        {tab === "recurring" && <RecurringPage accountId={accountId} range={range} />}
        {tab === "year" && <YearReviewPage accountId={accountId} onOpenMerchant={openMerchant} />}
        {tab === "goals" && <GoalsPage />}
        {tab === "net-worth" && <NetWorthPage showCashFlow={handoff.showCashFlow} />}
        {tab === "manage" && <ManagePage onAccountsChanged={loadAccounts} />}
      </main>
    </div>
  );
}

export default App;

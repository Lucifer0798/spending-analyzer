import type {
  Account,
  AccountType,
  AuthStatus,
  BackupSummary,
  Budget,
  BudgetSummary,
  CategorizeResult,
  CategoryDetail,
  ComparisonResponse,
  DateBounds,
  DateRangeValue,
  Goal,
  GoalContribution,
  GoalProgress,
  MerchantMemory,
  MerchantsResponse,
  PredictionsResponse,
  RecurringAction,
  RecurringOverride,
  RecurringResponse,
  SummaryResponse,
  Tag,
  Transaction,
  TransactionWithTags,
  UploadResult,
} from "./types";
import { ALL_TIME } from "./types";

function readCookie(name: string): string | null {
  const match = document.cookie.match(new RegExp(`(?:^|;\\s*)${name}=([^;]*)`));
  return match ? decodeURIComponent(match[1]) : null;
}

/**
 * Called whenever the server says we are not signed in, including when a session expires
 * mid-use. App registers this so a stale session sends you back to the login screen rather
 * than showing a wall of failed requests.
 */
let onUnauthorized: (() => void) | null = null;

export function setUnauthorizedHandler(handler: (() => void) | null) {
  onUnauthorized = handler;
}

interface RequestOptions extends RequestInit {
  /**
   * Skips the "session ended, go back to the login screen" reaction to a 401. Set for the
   * sign-in call itself, where a 401 means the password was wrong, not that a session lapsed.
   */
  ownsAuthFailure?: boolean;
}

async function request<T>(path: string, options?: RequestOptions): Promise<T> {
  const method = (options?.method ?? "GET").toUpperCase();
  const headers: Record<string, string> = {};

  if (!(options?.body instanceof FormData)) {
    headers["Content-Type"] = "application/json";
  }

  // Spring hands the CSRF token out as a readable cookie; anything that changes state has to
  // echo it back in this header or the request is refused as cross-site. Reads don't need it.
  if (method !== "GET" && method !== "HEAD") {
    const token = readCookie("XSRF-TOKEN");
    if (token) headers["X-XSRF-TOKEN"] = token;
  }

  const res = await fetch(`/api${path}`, {
    ...options,
    headers: { ...headers, ...(options?.headers as Record<string, string> | undefined) },
  });

  if (!res.ok) {
    const body = await res.json().catch(() => ({}) as { error?: string });

    if (res.status === 401 && !options?.ownsAuthFailure) {
      onUnauthorized?.();
      throw new Error("Your session has ended. Please sign in again.");
    }

    // Otherwise pass the server's own wording through — "Incorrect password." is far more use
    // to someone signing in than a generic failure would be.
    throw new Error(body.error || `Request failed: ${res.status}`);
  }
  return res.json() as Promise<T>;
}

/** Builds a query string, omitting null/undefined so "all accounts" means no filter. */
function qs(params: Record<string, string | number | boolean | null | undefined>) {
  const search = new URLSearchParams();
  for (const [key, value] of Object.entries(params)) {
    if (value !== null && value !== undefined && value !== "") {
      search.set(key, String(value));
    }
  }
  const s = search.toString();
  return s ? `?${s}` : "";
}

// --- auth -------------------------------------------------------------------

/**
 * Whether this instance has a password at all, and whether this browser is past it.
 *
 * <p>Call this before anything else: it is also what causes the server to issue the CSRF
 * cookie, which every later write depends on.
 */
export function fetchAuthStatus() {
  return request<AuthStatus>("/auth/status");
}

export async function login(password: string) {
  // Refresh the CSRF token first. A cookie left over from a previous run of the server — a
  // redeploy, or a different instance on the same port — is no longer valid, and because the
  // caller is not signed in yet the rejection comes back as a 401 that reads like a wrong
  // password. One extra GET removes the whole confusing class of failure.
  await fetchAuthStatus();

  return request<{ ok: true }>("/auth/login", {
    method: "POST",
    body: JSON.stringify({ password }),
    ownsAuthFailure: true,
  });
}

export function logout() {
  return request<{ ok: true }>("/auth/logout", { method: "POST" });
}

// --- upload -----------------------------------------------------------------

export function uploadFile(file: File, accountId: number | null, skipDuplicates = true) {
  const form = new FormData();
  form.append("file", file);
  return request<UploadResult>(`/upload${qs({ accountId, skipDuplicates })}`, {
    method: "POST",
    body: form,
  });
}

// --- transactions -----------------------------------------------------------

export function fetchTransactions(params: {
  category?: string;
  month?: string;
  accountId?: number | null;
  range?: DateRangeValue;
  tag?: string;
  limit?: number;
  offset?: number;
} = {}) {
  const { range, ...rest } = params;
  return request<{ transactions: TransactionWithTags[]; total: number }>(
    `/transactions${qs({ ...rest, from: range?.from, to: range?.to })}`
  );
}

export function updateTransactionCategory(id: number, category: string) {
  return updateTransaction(id, { category });
}

/** Any subset of the editable fields; omitted fields are left unchanged. */
export function updateTransaction(
  id: number,
  changes: {
    category?: string;
    date?: string;
    description?: string;
    amount?: number;
    type?: "debit" | "credit";
  }
) {
  return request<{ ok: true; transaction: Transaction; learnedMerchant: string }>(
    `/transactions/${id}`,
    { method: "PATCH", body: JSON.stringify(changes) }
  );
}

export function deleteTransaction(id: number) {
  return request<{ ok: true }>(`/transactions/${id}`, { method: "DELETE" });
}

/** Tags a transaction, creating the tag first if this is the first time it's been used. */
export function addTransactionTag(id: number, name: string) {
  return request<{ ok: true; tags: string[] }>(`/transactions/${id}/tags`, {
    method: "POST",
    body: JSON.stringify({ name }),
  });
}

/** Untags a transaction; the tag itself remains for whatever other transactions carry it. */
export function removeTransactionTag(id: number, name: string) {
  return request<{ ok: true; tags: string[] }>(`/transactions/${id}/tags/${encodeURIComponent(name)}`, {
    method: "DELETE",
  });
}

// --- accounts ---------------------------------------------------------------

export function fetchAccounts(includeArchived = false) {
  return request<{ accounts: Account[]; types: AccountType[]; currencies: string[] }>(
    `/accounts${qs({ includeArchived })}`
  );
}

export function createAccount(name: string, type: AccountType, currency = "USD") {
  return request<Account>("/accounts", {
    method: "POST",
    body: JSON.stringify({ name, type, currency }),
  });
}

export function updateAccount(
  id: number,
  changes: { name?: string; type?: AccountType; archived?: boolean; currency?: string }
) {
  return request<Account>(`/accounts/${id}`, {
    method: "PATCH",
    body: JSON.stringify(changes),
  });
}

export function deleteAccount(id: number) {
  return request<{ ok: true; transactionsMovedToDefault: number }>(`/accounts/${id}`, {
    method: "DELETE",
  });
}

// --- categories -------------------------------------------------------------

export function fetchCategories() {
  return request<{ categories: string[]; detailed: CategoryDetail[] }>("/categories");
}

export function createCategory(name: string, flags: { is_income?: boolean; is_transfer?: boolean } = {}) {
  return request<CategoryDetail>("/categories", {
    method: "POST",
    body: JSON.stringify({ name, ...flags }),
  });
}

export function updateCategory(
  id: number,
  changes: { name?: string; is_income?: boolean; is_transfer?: boolean }
) {
  return request<CategoryDetail>(`/categories/${id}`, {
    method: "PATCH",
    body: JSON.stringify(changes),
  });
}

export function deleteCategory(id: number) {
  return request<{ ok: true; transactionsReassignedTo: string; transactionsReassigned: number }>(
    `/categories/${id}`,
    { method: "DELETE" }
  );
}

// --- tags ---------------------------------------------------------------------

/** Every tag on record with its usage count, for a filter dropdown or autocomplete list. */
export function fetchTags() {
  return request<Tag[]>("/tags");
}

/** Deletes a tag everywhere it's applied — the tag itself, not just one transaction's use of it. */
export function deleteTag(name: string) {
  return request<{ ok: true; transactionsUntagged: number }>(`/tags/${encodeURIComponent(name)}`, {
    method: "DELETE",
  });
}

// --- budgets ----------------------------------------------------------------

/** Omit `month` to get the newest month on record, which is what the dashboard shows. */
export function fetchBudgets(accountId: number | null, month?: string) {
  return request<BudgetSummary>(`/budgets${qs({ accountId, month })}`);
}

/** Upsert: sets the target for a category whether or not one already exists. */
export function setBudget(category: string, monthlyLimit: number) {
  return request<Budget>("/budgets", {
    method: "POST",
    body: JSON.stringify({ category, monthly_limit: monthlyLimit }),
  });
}

export function deleteBudget(id: number) {
  return request<{ ok: true }>(`/budgets/${id}`, { method: "DELETE" });
}

// --- goals --------------------------------------------------------------------

/** Every goal, measured against what has actually been logged toward it. */
export function fetchGoals() {
  return request<GoalProgress[]>("/goals");
}

export function createGoal(goal: { name: string; target_amount: number; target_date?: string; currency?: string }) {
  return request<Goal>("/goals", { method: "POST", body: JSON.stringify(goal) });
}

/** Any subset of the editable fields; omitted fields are left unchanged. Currency can't be changed once set. */
export function updateGoal(id: number, changes: { name?: string; target_amount?: number; target_date?: string }) {
  return request<Goal>(`/goals/${id}`, { method: "PATCH", body: JSON.stringify(changes) });
}

/** Deletes a goal and every contribution logged toward it. */
export function deleteGoal(id: number) {
  return request<{ ok: true }>(`/goals/${id}`, { method: "DELETE" });
}

/** A goal's contribution history, newest first. */
export function fetchGoalContributions(goalId: number) {
  return request<GoalContribution[]>(`/goals/${goalId}/contributions`);
}

/** Logs an amount toward a goal. A negative amount records money taken back out. */
export function addGoalContribution(goalId: number, contribution: { amount: number; date: string; note?: string }) {
  return request<GoalContribution>(`/goals/${goalId}/contributions`, {
    method: "POST",
    body: JSON.stringify(contribution),
  });
}

export function deleteGoalContribution(goalId: number, contributionId: number) {
  return request<{ ok: true }>(`/goals/${goalId}/contributions/${contributionId}`, { method: "DELETE" });
}

// --- insights ---------------------------------------------------------------

export function runCategorization() {
  return request<CategorizeResult>("/categorize", { method: "POST" });
}

// --- merchant memory --------------------------------------------------------

export function fetchMerchants() {
  return request<MerchantsResponse>("/merchants");
}

/**
 * Creates or replaces one memory rule. Omit the bounds for a catch-all; supply them to make the
 * rule apply only within an amount band, which is how one merchant maps to two categories.
 */
export function saveMerchantRule(rule: {
  merchant_key: string;
  category: string;
  min_amount?: number;
  max_amount?: number;
}) {
  return request<{ ok: true; merchants: MerchantMemory[] }>("/merchants", {
    method: "POST",
    body: JSON.stringify(rule),
  });
}

export function forgetMerchant(id: number) {
  return request<{ ok: true }>(`/merchants/${id}`, { method: "DELETE" });
}

export function forgetAllMerchants() {
  return request<{ ok: true; forgotten: number }>("/merchants", { method: "DELETE" });
}

export function fetchSummary(accountId: number | null, range: DateRangeValue = ALL_TIME) {
  return request<SummaryResponse>(`/summary${qs({ accountId, from: range.from, to: range.to })}`);
}

/** applicable is false for "all time" or a half-open range -- see ComparisonResponse. */
export function fetchComparison(accountId: number | null, range: DateRangeValue = ALL_TIME) {
  return request<ComparisonResponse>(`/summary/comparison${qs({ accountId, from: range.from, to: range.to })}`);
}

export function fetchRecurring(accountId: number | null, range: DateRangeValue = ALL_TIME) {
  return request<RecurringResponse>(`/recurring${qs({ accountId, from: range.from, to: range.to })}`);
}

/**
 * Flags a merchant as "cancel" (a reminder shown alongside its series until you clear it) or
 * "exclude" (hides it from recurring detection for good). Set from the Recurring page, in
 * context next to the series it applies to.
 */
export function saveRecurringOverride(merchantKey: string, action: RecurringAction) {
  return request<RecurringOverride>("/recurring/overrides", {
    method: "POST",
    body: JSON.stringify({ merchant_key: merchantKey, action }),
  });
}

/** Every flagged or excluded merchant, for the management view in Manage. */
export function fetchRecurringOverrides() {
  return request<RecurringOverride[]>("/recurring/overrides");
}

/** Clears an override — un-flags a cancellation, or brings an excluded merchant back into view. */
export function clearRecurringOverride(id: number) {
  return request<{ ok: true }>(`/recurring/overrides/${id}`, { method: "DELETE" });
}

/** Earliest and latest dates on record, used to anchor the date-range presets. */
export function fetchDateBounds(accountId: number | null) {
  return request<DateBounds>(`/date-bounds${qs({ accountId })}`);
}

/** The cached forecast for this account, or for every account combined when null. */
export function fetchPredictions(accountId: number | null) {
  return request<PredictionsResponse>(`/predictions${qs({ accountId })}`);
}

export function refreshPredictions(accountId: number | null) {
  return request<PredictionsResponse>(`/predictions/refresh${qs({ accountId })}`, { method: "POST" });
}

export function resetAllData() {
  return request<{ ok: true }>("/reset", { method: "DELETE" });
}

// --- export -----------------------------------------------------------------

export type ExportKind = "transactions" | "categories" | "monthly" | "predictions" | "recommendations";

/**
 * Builds a download URL rather than fetching. The browser handles the response, which keeps
 * the filename the server sets — fetching would give us a blob with that header discarded,
 * and the app would have to invent a filename to hand back.
 */
export function exportUrl(
  kind: ExportKind,
  params: {
    accountId?: number | null;
    range?: DateRangeValue;
    category?: string;
    month?: string;
    tag?: string;
  } = {}
) {
  const { range, ...rest } = params;
  return `/api/export/${kind}.csv${qs({ ...rest, from: range?.from, to: range?.to })}`;
}

// --- backup -------------------------------------------------------------------

/**
 * A download URL rather than a fetch, for the same reason exportUrl is — the browser keeps the
 * filename the server sets. Covers accounts, categories, transactions, merchant memory, budgets,
 * recurring overrides, savings goals, and tags; AI forecasts aren't included, since regenerating
 * one is one click.
 */
export function backupUrl() {
  return "/api/backup";
}

/**
 * Restores from a file this same export produced — replacing everything currently in this
 * instance, not merging with it. `json` should be that file's untouched text.
 */
export function importBackup(json: string) {
  return request<BackupSummary>("/backup/import", {
    method: "POST",
    body: json,
  });
}

import type {
  Account,
  AccountType,
  AuthStatus,
  Budget,
  BudgetSummary,
  CategorizeResult,
  CategoryDetail,
  DateBounds,
  DateRangeValue,
  MerchantsResponse,
  PredictionsResponse,
  RecurringResponse,
  SummaryResponse,
  Transaction,
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
  limit?: number;
  offset?: number;
} = {}) {
  const { range, ...rest } = params;
  return request<{ transactions: Transaction[]; total: number }>(
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

// --- accounts ---------------------------------------------------------------

export function fetchAccounts(includeArchived = false) {
  return request<{ accounts: Account[]; types: AccountType[] }>(`/accounts${qs({ includeArchived })}`);
}

export function createAccount(name: string, type: AccountType) {
  return request<Account>("/accounts", {
    method: "POST",
    body: JSON.stringify({ name, type }),
  });
}

export function updateAccount(id: number, changes: { name?: string; type?: AccountType; archived?: boolean }) {
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

// --- insights ---------------------------------------------------------------

export function runCategorization() {
  return request<CategorizeResult>("/categorize", { method: "POST" });
}

// --- merchant memory --------------------------------------------------------

export function fetchMerchants() {
  return request<MerchantsResponse>("/merchants");
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

export function fetchRecurring(accountId: number | null, range: DateRangeValue = ALL_TIME) {
  return request<RecurringResponse>(`/recurring${qs({ accountId, from: range.from, to: range.to })}`);
}

/** Earliest and latest dates on record, used to anchor the date-range presets. */
export function fetchDateBounds(accountId: number | null) {
  return request<DateBounds>(`/date-bounds${qs({ accountId })}`);
}

export function fetchPredictions() {
  return request<PredictionsResponse>("/predictions");
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
  } = {}
) {
  const { range, ...rest } = params;
  return `/api/export/${kind}.csv${qs({ ...rest, from: range?.from, to: range?.to })}`;
}

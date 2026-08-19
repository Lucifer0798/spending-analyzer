import type {
  Account,
  AccountType,
  CategoryDetail,
  PredictionsResponse,
  RecurringResponse,
  SummaryResponse,
  Transaction,
  UploadResult,
} from "./types";

async function request<T>(path: string, options?: RequestInit): Promise<T> {
  const res = await fetch(`/api${path}`, {
    headers: options?.body instanceof FormData ? undefined : { "Content-Type": "application/json" },
    ...options,
  });
  if (!res.ok) {
    const body = await res.json().catch(() => ({ error: res.statusText }));
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
  limit?: number;
  offset?: number;
} = {}) {
  return request<{ transactions: Transaction[]; total: number }>(`/transactions${qs(params)}`);
}

export function updateTransactionCategory(id: number, category: string) {
  return request<{ ok: true }>(`/transactions/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ category }),
  });
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

// --- insights ---------------------------------------------------------------

export function runCategorization() {
  return request<{ categorized: number; total?: number; message?: string }>("/categorize", {
    method: "POST",
  });
}

export function fetchSummary(accountId: number | null) {
  return request<SummaryResponse>(`/summary${qs({ accountId })}`);
}

export function fetchRecurring(accountId: number | null) {
  return request<RecurringResponse>(`/recurring${qs({ accountId })}`);
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

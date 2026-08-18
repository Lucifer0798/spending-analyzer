import type {
  PredictionsResponse,
  SummaryResponse,
  Transaction,
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

export function uploadFile(file: File) {
  const form = new FormData();
  form.append("file", file);
  return request<{ batchId: string; inserted: number; preCategorized: number }>("/upload", {
    method: "POST",
    body: form,
  });
}

export function fetchTransactions(params: { category?: string; month?: string; limit?: number; offset?: number } = {}) {
  const qs = new URLSearchParams();
  if (params.category) qs.set("category", params.category);
  if (params.month) qs.set("month", params.month);
  if (params.limit) qs.set("limit", String(params.limit));
  if (params.offset) qs.set("offset", String(params.offset));
  return request<{ transactions: Transaction[]; total: number }>(`/transactions?${qs.toString()}`);
}

export function updateTransactionCategory(id: number, category: string) {
  return request<{ ok: true }>(`/transactions/${id}`, {
    method: "PATCH",
    body: JSON.stringify({ category }),
  });
}

export function fetchCategories() {
  return request<{ categories: string[] }>("/categories");
}

export function runCategorization() {
  return request<{ categorized: number; total?: number; message?: string }>("/categorize", {
    method: "POST",
  });
}

export function fetchSummary() {
  return request<SummaryResponse>("/summary");
}

export function fetchPredictions() {
  return request<PredictionsResponse>("/predictions");
}

export function refreshPredictions() {
  return request<PredictionsResponse>("/predictions/refresh", { method: "POST" });
}

export function resetAllData() {
  return request<{ ok: true }>("/reset", { method: "DELETE" });
}

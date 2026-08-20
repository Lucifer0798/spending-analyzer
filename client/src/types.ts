export interface Transaction {
  id: number;
  date: string;
  description: string;
  amount: number;
  type: "debit" | "credit";
  category: string | null;
  category_source: "ai" | "user" | "import" | null;
  upload_batch_id: string;
  created_at: string;
  account_id: number;
  account_name: string | null;
}

export type AccountType =
  | "checking"
  | "savings"
  | "credit_card"
  | "cash"
  | "investment"
  | "other";

export interface Account {
  id: number;
  name: string;
  type: AccountType;
  archived: boolean;
  created_at: string;
  transactionCount: number;
}

export interface CategoryDetail {
  id: number;
  name: string;
  is_builtin: boolean;
  is_income: boolean;
  is_transfer: boolean;
  sort_order: number;
  transactionCount: number;
}

export interface UploadResult {
  batchId: string;
  inserted: number;
  preCategorized: number;
  skippedDuplicates: number;
  parsed: number;
  accountId: number;
  accountName: string;
}

export interface RecurringSeries {
  merchant: string;
  category: string | null;
  cadence: "weekly" | "biweekly" | "monthly" | "quarterly" | "yearly";
  average_amount: number;
  last_amount: number;
  last_date: string;
  next_expected_date: string;
  occurrences: number;
  median_interval_days: number;
  annualized_cost: number;
  confidence: "low" | "medium" | "high";
}

export interface RecurringResponse {
  recurring: RecurringSeries[];
  totalAnnualizedCost: number;
  totalMonthlyEquivalent: number;
}

export interface CategoryTotal {
  category: string;
  total: number;
  count: number;
}

export interface MonthlyTotal {
  month: string;
  total: number;
}

export interface CategoryMonthlySeries {
  category: string;
  months: MonthlyTotal[];
  linearTrendNextMonth: number;
  movingAverage3mo: number;
  overallTotal: number;
  lastMonthTotal: number;
}

export interface SummaryResponse {
  categoryTotals: CategoryTotal[];
  monthlyTotals: MonthlyTotal[];
  monthlyByCategory: CategoryMonthlySeries[];
}

export interface Prediction {
  category: string;
  predicted_next_month: number;
  trend: "increasing" | "decreasing" | "stable";
  confidence: "low" | "medium" | "high";
  rationale: string;
}

export interface Recommendation {
  category: string;
  insight: string;
  suggested_action: string;
  potential_monthly_savings: number;
}

export interface PredictionsPayload {
  summary: string;
  predictions: Prediction[];
  recommendations: Recommendation[];
}

export interface PredictionsResponse {
  predictions: PredictionsPayload | null;
  generatedAt: string | null;
}

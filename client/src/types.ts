export interface Transaction {
  id: number;
  date: string;
  description: string;
  amount: number;
  type: "debit" | "credit";
  category: string | null;
  /** "cache" means it came from merchant memory rather than a fresh model call. */
  category_source: "ai" | "user" | "import" | "cache" | null;
  upload_batch_id: string;
  created_at: string;
  account_id: number;
  account_name: string | null;
  account_currency: string | null;
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
  currency: string;
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

/** Inclusive date window; null on either side means open-ended. */
export interface DateRangeValue {
  from: string | null;
  to: string | null;
}

export const ALL_TIME: DateRangeValue = { from: null, to: null };

export interface DateBounds {
  earliest: string | null;
  latest: string | null;
}

export interface CategorizeResult {
  categorized: number;
  total?: number;
  message?: string;
  /** Answered from merchant memory, costing no model call. */
  fromMemory: number;
  fromModel: number;
  /** Distinct merchants actually sent to the model — what drives cost. */
  merchantsQueried: number;
}

export interface MerchantMemory {
  id: number;
  merchant_key: string;
  category: string;
  /** Inclusive lower bound on the transaction amount. */
  min_amount: number;
  /** Exclusive upper bound; effectively infinite for a catch-all. */
  max_amount: number;
  /** True when the rule covers every amount, which is what a correction writes. */
  is_catch_all: boolean;
  source: "ai" | "user";
  hit_count: number;
  created_at: string;
  updated_at: string;
}

export interface MerchantsResponse {
  merchants: MerchantMemory[];
  count: number;
  totalMemoryHits: number;
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
  flagged_for_cancellation: boolean;
  /** The override's id, for clearing it — null unless flagged_for_cancellation. */
  override_id: number | null;
}

export type RecurringAction = "cancel" | "exclude";

export interface RecurringOverride {
  id: number;
  merchant_key: string;
  action: RecurringAction;
  created_at: string;
}

export interface RecurringResponse {
  recurring: RecurringSeries[];
  totalAnnualizedCost: number;
  totalMonthlyEquivalent: number;
  currency: string;
  /** True when "all accounts" spans more than one currency — recurring is left empty above. */
  mixedCurrencies: boolean;
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

/** One currency's slice of a {@link SummaryResponse} whose accounts don't all share one. */
export interface CurrencyBreakdown {
  currency: string;
  categoryTotals: CategoryTotal[];
  monthlyTotals: MonthlyTotal[];
}

export interface SummaryResponse {
  categoryTotals: CategoryTotal[];
  monthlyTotals: MonthlyTotal[];
  monthlyByCategory: CategoryMonthlySeries[];
  /** Null when "all accounts" spans more than one currency — see `perCurrency` instead. */
  currency: string | null;
  perCurrency: CurrencyBreakdown[] | null;
}

export interface CategoryComparison {
  category: string;
  currentTotal: number;
  previousTotal: number;
  changeAmount: number;
  /** Null when previousTotal is zero -- there is no meaningful percentage change from nothing. */
  changePercent: number | null;
}

export interface PeriodComparison {
  currentRange: DateRangeValue;
  previousRange: DateRangeValue;
  currentTotal: number;
  previousTotal: number;
  changeAmount: number;
  changePercent: number | null;
  /** Biggest increase first, biggest decrease last. */
  categories: CategoryComparison[];
}

/** False for "all time" or a half-open filter -- a comparison needs two well-defined,
 *  equal-length windows, which neither has -- or when "all accounts" spans more than one
 *  currency, since a comparison would mix them. `currency` is null exactly when this is. */
export interface ComparisonResponse {
  applicable: boolean;
  comparison: PeriodComparison | null;
  currency: string | null;
}

export interface AuthStatus {
  /** False when no password is configured, in which case the app is open by design. */
  authRequired: boolean;
  /** Always true when authRequired is false — there is nothing to be signed in to. */
  authenticated: boolean;
}

export interface Budget {
  id: number;
  category: string;
  monthly_limit: number;
  updated_at: string;
}

/** "near" is 80% or more of the target; "over" is past it. */
export type BudgetStatus = "under" | "near" | "over";

export interface BudgetProgress {
  id: number;
  category: string;
  monthlyLimit: number;
  spent: number;
  /** Negative once the budget is blown. */
  remaining: number;
  /** Uncapped, so 140 means 40% over. */
  percentUsed: number;
  status: BudgetStatus;
}

export interface BudgetSummary {
  /** The month actually measured, which the server picks when none was asked for. */
  month: string;
  budgets: BudgetProgress[];
  totalLimit: number;
  totalSpent: number;
  /** Null when "all accounts" spans more than one currency — budgets is empty in that case. */
  currency: string | null;
  mixedCurrencies: boolean;
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

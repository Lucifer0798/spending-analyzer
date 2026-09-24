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
  /** What you're responsible for, out of `amount`. Null means unsplit -- the full amount is yours. */
  split_share: number | null;
  /** Who the rest belongs to; null whenever split_share is. */
  split_note: string | null;
}

/** A transaction as the list endpoint returns it, with its tags attached. */
export interface TransactionWithTags extends Transaction {
  tags: string[];
}

/** A tag and how many transactions currently carry it. */
export interface Tag {
  name: string;
  count: number;
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
  /** Null means ungrouped -- rolls up as its own group of one on the dashboard and in exports. */
  group_name: string | null;
  /** "#rrggbb", or null to fall back to the chart's default color. */
  color: string | null;
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
  /** Days until next_expected_date; negative once that date has passed without a newer charge. */
  due_in_days: number;
  /** True when the last charge differs from what came before it by more than a rounding blip. */
  price_changed: boolean;
  /** What it used to cost, going into the change — null unless price_changed. */
  previous_amount: number | null;
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

/**
 * The mirror of RecurringResponse for the credit side. `totalMonthlyEquivalent` is an average
 * (annualized income / 12), not a prediction for `month` specifically — `actualThisMonth` is
 * what actually came in during `month`, put alongside it rather than compared outright.
 */
export interface RecurringIncomeResponse {
  recurring: RecurringSeries[];
  totalAnnualizedIncome: number;
  totalMonthlyEquivalent: number;
  /** The month `actualThisMonth` was measured over — the newest with income data, by default. */
  month: string;
  actualThisMonth: number;
  currency: string;
  /** True when "all accounts" spans more than one currency — recurring is left empty above. */
  mixedCurrencies: boolean;
}

/** A transaction whose amount stood out against its own category's typical spend. */
export interface SpendingAnomaly {
  transactionId: number;
  date: string;
  description: string;
  category: string;
  amount: number;
  /** The category's median amount — what the transaction is being compared against. */
  typicalAmount: number;
  multiplier: number;
}

export interface AnomaliesResponse {
  anomalies: SpendingAnomaly[];
  currency: string;
  /** True when "all accounts" spans more than one currency — anomalies is left empty above. */
  mixedCurrencies: boolean;
}

/** One manually-logged balance for an account, as of a date. Negative is a liability. */
export interface AccountBalance {
  id: number;
  account_id: number;
  date: string;
  balance: number;
  created_at: string;
}

/** One account's most recently logged balance. */
export interface NetWorthAccount {
  accountId: number;
  accountName: string;
  balance: number;
  asOfDate: string;
}

/** Total net worth as of one date -- a point on the history chart. */
export interface NetWorthPoint {
  date: string;
  total: number;
}

/** One currency's slice of a {@link NetWorthResponse} whose accounts don't all share one. */
export interface CurrencyNetWorth {
  currency: string;
  total: number;
  accounts: NetWorthAccount[];
  history: NetWorthPoint[];
}

/** Null currency (with perCurrency populated instead) once active accounts span more than one. */
export interface NetWorthResponse {
  total: number;
  accounts: NetWorthAccount[];
  history: NetWorthPoint[];
  currency: string | null;
  perCurrency: CurrencyNetWorth[] | null;
}

/** A saved combination of the Transactions page's filters. Every field but the name is optional. */
export interface FilterPreset {
  id: number;
  name: string;
  category: string | null;
  tag: string | null;
  search: string | null;
  account_id: number | null;
  date_from: string | null;
  date_to: string | null;
  created_at: string;
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
  /** True when `previousRange` was picked outright rather than auto-derived from `currentRange`'s length. */
  custom: boolean;
}

/** False for "all time" or a half-open filter -- a comparison needs two well-defined
 *  windows, which neither has -- or when "all accounts" spans more than one currency, since a
 *  comparison would mix them. `currency` is null exactly when this is. */
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

/** "fixed" adds a flat amount per period; "percent" compounds a percentage of the base. */
export type EscalationType = "fixed" | "percent";

export interface Budget {
  id: number;
  category: string;
  monthly_limit: number;
  escalation_type: EscalationType | null;
  escalation_value: number | null;
  escalation_frequency_months: number | null;
  /** YYYY-MM the schedule starts counting periods from. */
  escalation_start_month: string | null;
  /** YYYY-MM rollover starts accumulating from, or null when unused budget doesn't carry forward. */
  rollover_start_month: string | null;
  updated_at: string;
}

/** "near" is 80% or more of the target; "over" is past it. */
export type BudgetStatus = "under" | "near" | "over";

/** Escalation and rollover only ever apply to "monthly" -- both are defined in whole months. */
export type BudgetPeriod = "weekly" | "monthly" | "quarterly";

export interface BudgetProgress {
  id: number;
  category: string;
  /** The limit actually in effect for the measured month -- what `spent` is compared against. */
  monthlyLimit: number;
  /** The raw target as originally set, before escalation or rollover. */
  baseLimit: number;
  spent: number;
  /** Negative once the budget is blown. */
  remaining: number;
  /** Uncapped, so 140 means 40% over. */
  percentUsed: number;
  status: BudgetStatus;
  escalationType: EscalationType | null;
  escalationValue: number | null;
  escalationFrequencyMonths: number | null;
  escalationStartMonth: string | null;
  rolloverStartMonth: string | null;
  /** Unused budget (or overspend, negative) carried in from prior months; zero when off. */
  rolloverCarryIn: number;
  period: BudgetPeriod;
  /** The actual range `spent` was measured over -- a week or a quarter for a non-monthly budget,
   *  since the page as a whole is anchored to a single month. */
  periodStart: string;
  periodEnd: string;
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

/** How many rows of each kind a backup restore loaded. */
export interface BackupSummary {
  accounts: number;
  categories: number;
  transactions: number;
  merchantCategories: number;
  budgets: number;
  recurringOverrides: number;
  goals: number;
  goalContributions: number;
  tags: number;
  accountBalances: number;
  filterPresets: number;
}

export interface Goal {
  id: number;
  name: string;
  target_amount: number;
  /** Null when the goal has no deadline. */
  target_date: string | null;
  currency: string;
  created_at: string;
  updated_at: string;
}

/** Positive is money added, negative is money taken back out. */
export interface GoalContribution {
  id: number;
  goal_id: number;
  amount: number;
  date: string;
  note: string | null;
  created_at: string;
}

/** A goal measured against what has actually been logged toward it. */
export interface GoalProgress {
  id: number;
  name: string;
  targetAmount: number;
  targetDate: string | null;
  currency: string;
  saved: number;
  /** Never negative -- a goal met or exceeded has nothing left to go. */
  remaining: number;
  /** Uncapped, so a goal exceeded still reads as more than 100. */
  percentComplete: number;
  achieved: boolean;
  contributionCount: number;
  /** Average net contribution rate since the first one logged, projected to a 30.44-day month.
   *  Zero with nothing logged yet, and can be negative when withdrawals outpace deposits. */
  monthlyPace: number;
  /** Null when there's no pace to project from -- no contributions, already achieved, or the
   *  pace isn't positive. Otherwise the date this goal would be reached at the current pace. */
  projectedCompletionDate: string | null;
}

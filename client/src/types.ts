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
  months: { month: string; total: number }[];
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

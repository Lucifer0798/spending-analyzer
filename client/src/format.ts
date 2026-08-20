export function currency(n: number, maximumFractionDigits = 0) {
  return n.toLocaleString(undefined, {
    style: "currency",
    currency: "USD",
    maximumFractionDigits,
  });
}

export function currencyPrecise(n: number) {
  return currency(n, 2);
}

const ACCOUNT_TYPE_LABELS: Record<string, string> = {
  checking: "Checking",
  savings: "Savings",
  credit_card: "Credit card",
  cash: "Cash",
  investment: "Investment",
  other: "Other",
};

export function accountTypeLabel(type: string) {
  return ACCOUNT_TYPE_LABELS[type] ?? type;
}

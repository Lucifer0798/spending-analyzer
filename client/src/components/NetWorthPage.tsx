import { useEffect, useState } from "react";
import { CartesianGrid, Line, LineChart, ResponsiveContainer, Tooltip, XAxis, YAxis } from "recharts";
import {
  deleteAccountBalance,
  fetchAccountBalances,
  fetchAccounts,
  fetchNetWorth,
  logAccountBalance,
} from "../api";
import type { Account, AccountBalance, CurrencyNetWorth, NetWorthAccount, NetWorthResponse } from "../types";
import { accountTypeLabel, currency } from "../format";

const BLUE = "#2a78d6";
const MUTED = "#898781";
const GRID = "#e1e0d9";

function todayIsoDate() {
  return new Date().toISOString().slice(0, 10);
}

function dateLabel(iso: string) {
  const [year, month, day] = iso.split("-").map(Number);
  return new Date(year, month - 1, day).toLocaleDateString(undefined, {
    month: "short",
    day: "numeric",
    year: "numeric",
  });
}

function ChartTooltip({
  active,
  payload,
  label,
  currencyCode,
}: {
  active?: boolean;
  payload?: { value: number }[];
  label?: string;
  currencyCode: string;
}) {
  if (!active || !payload?.length) return null;
  return (
    <div className="rounded-md border border-slate-200 bg-white px-3 py-2 text-xs shadow-md dark:border-slate-700 dark:bg-slate-900">
      <p className="font-medium text-slate-700 dark:text-slate-200">{label && dateLabel(label)}</p>
      <p className="text-slate-600 dark:text-slate-400">{currency(payload[0].value, 0, currencyCode)}</p>
    </div>
  );
}

function NetWorthChart({ data, currencyCode }: { data: { date: string; total: number }[]; currencyCode: string }) {
  if (data.length < 2) return null;
  return (
    <div className="mt-6 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <ResponsiveContainer width="100%" height={220}>
        <LineChart data={data}>
          <CartesianGrid stroke={GRID} vertical={false} />
          <XAxis dataKey="date" stroke={MUTED} fontSize={12} tickFormatter={dateLabel} tickLine={false} axisLine={{ stroke: GRID }} />
          <YAxis stroke={MUTED} fontSize={12} tickLine={false} axisLine={false} />
          <Tooltip content={<ChartTooltip currencyCode={currencyCode} />} />
          <Line type="monotone" dataKey="total" stroke={BLUE} strokeWidth={2} dot={{ r: 3, fill: BLUE }} />
        </LineChart>
      </ResponsiveContainer>
    </div>
  );
}

function BalanceHistory({
  accountId,
  currencyCode,
  onChanged,
}: {
  accountId: number;
  currencyCode: string;
  onChanged: () => void;
}) {
  const [history, setHistory] = useState<AccountBalance[] | null>(null);

  useEffect(() => {
    fetchAccountBalances(accountId).then(setHistory).catch(() => setHistory([]));
  }, [accountId]);

  if (!history) return <p className="mt-2 text-xs text-slate-500">Loading…</p>;
  if (history.length === 0) return <p className="mt-2 text-xs text-slate-500">Nothing logged yet.</p>;

  return (
    <ul className="mt-2 space-y-1">
      {history.map((b) => (
        <li key={b.id} className="flex items-center justify-between gap-2 text-xs">
          <span className="text-slate-600 dark:text-slate-400">{dateLabel(b.date)}</span>
          <span className="flex items-center gap-2">
            <span className="text-slate-800 dark:text-slate-200">{currency(b.balance, 0, currencyCode)}</span>
            <button
              onClick={() =>
                deleteAccountBalance(accountId, b.id).then(() => {
                  setHistory((h) => h?.filter((x) => x.id !== b.id) ?? null);
                  onChanged();
                })
              }
              className="text-slate-400 hover:text-red-600"
              title="Remove this entry"
            >
              ×
            </button>
          </span>
        </li>
      ))}
    </ul>
  );
}

function AccountBalanceCard({
  account,
  netWorthAccount,
  onChanged,
}: {
  account: Account;
  netWorthAccount: NetWorthAccount | undefined;
  onChanged: () => void;
}) {
  const [showForm, setShowForm] = useState(false);
  const [showHistory, setShowHistory] = useState(false);
  const [amount, setAmount] = useState("");
  const [date, setDate] = useState(todayIsoDate());
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // A credit card's balance is a bill, not a holding -- asking "how much do you owe" and
  // negating it ourselves means nobody has to remember to type a minus sign.
  const isCreditCard = account.type === "credit_card";

  const submit = async () => {
    const parsed = Number(amount);
    if (!amount.trim() || !Number.isFinite(parsed)) return;

    setBusy(true);
    setError(null);
    try {
      await logAccountBalance(account.id, date, isCreditCard ? -Math.abs(parsed) : parsed);
      setAmount("");
      setShowForm(false);
      setShowHistory(true);
      onChanged();
    } catch (e) {
      setError(e instanceof Error ? e.message : "Failed to log balance.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="flex items-start justify-between gap-2">
        <div>
          <h3 className="font-medium text-slate-800 dark:text-slate-200">{account.name}</h3>
          <p className="text-xs text-slate-500">{accountTypeLabel(account.type)}</p>
        </div>
        {netWorthAccount ? (
          <div className="text-right">
            <p className="font-semibold text-slate-800 dark:text-slate-200">
              {currency(netWorthAccount.balance, 0, account.currency)}
            </p>
            <p className="text-xs text-slate-500">as of {dateLabel(netWorthAccount.asOfDate)}</p>
          </div>
        ) : (
          <p className="text-xs text-slate-500">No balance logged</p>
        )}
      </div>

      <div className="mt-3 flex flex-wrap items-center gap-2">
        <button
          onClick={() => setShowForm((s) => !s)}
          className="rounded-md bg-indigo-600 px-3 py-1.5 text-xs font-medium text-white hover:bg-indigo-700"
        >
          Log balance
        </button>
        <button
          onClick={() => setShowHistory((s) => !s)}
          className="rounded-md px-3 py-1.5 text-xs font-medium text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
        >
          {showHistory ? "Hide" : "Show"} history
        </button>
      </div>

      {showForm && (
        <div className="mt-3 rounded-md border border-dashed border-slate-300 p-3 dark:border-slate-700">
          {error && <p className="mb-2 text-xs text-red-600 dark:text-red-400">{error}</p>}
          <div className="flex flex-wrap items-center gap-2">
            <input
              type="date"
              value={date}
              onChange={(e) => setDate(e.target.value)}
              className="rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <input
              type="number"
              step="0.01"
              value={amount}
              onChange={(e) => setAmount(e.target.value)}
              placeholder={isCreditCard ? "Amount owed" : "Balance"}
              className="w-32 rounded border border-slate-300 bg-white px-2 py-1 text-sm dark:border-slate-700 dark:bg-slate-900"
            />
            <button
              disabled={busy || !amount.trim()}
              onClick={submit}
              className="rounded px-3 py-1 text-sm font-medium text-indigo-600 hover:bg-indigo-50 disabled:opacity-40 dark:text-indigo-400 dark:hover:bg-indigo-950/40"
            >
              Save
            </button>
          </div>
          {isCreditCard && (
            <p className="mt-1 text-xs text-slate-500">
              Enter what you owe as a positive number — it's recorded as a negative balance.
            </p>
          )}
        </div>
      )}

      {showHistory && (
        <BalanceHistory accountId={account.id} currencyCode={account.currency} onChanged={onChanged} />
      )}
    </div>
  );
}

function CurrencySection({ data }: { data: CurrencyNetWorth }) {
  return (
    <div className="mb-8">
      <h2 className="mb-3 text-sm font-semibold uppercase tracking-wide text-slate-500">{data.currency}</h2>
      <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
        <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Net worth</p>
        <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
          {currency(data.total, 0, data.currency)}
        </p>
      </div>
      <NetWorthChart data={data.history} currencyCode={data.currency} />
    </div>
  );
}

export function NetWorthPage() {
  const [netWorth, setNetWorth] = useState<NetWorthResponse | null>(null);
  const [accounts, setAccounts] = useState<Account[]>([]);
  const [error, setError] = useState<string | null>(null);

  const load = () => {
    fetchNetWorth()
      .then(setNetWorth)
      .catch((e) => setError(e instanceof Error ? e.message : "Failed to load net worth."));
    fetchAccounts().then((r) => setAccounts(r.accounts)).catch(() => {});
  };

  useEffect(load, []);

  if (!netWorth) {
    return <div className="px-4 py-10 text-center text-sm text-slate-500">Loading…</div>;
  }

  const mixedCurrencies = netWorth.currency === null;
  const netWorthAccounts = mixedCurrencies
    ? (netWorth.perCurrency ?? []).flatMap((c) => c.accounts)
    : netWorth.accounts;

  return (
    <div className="mx-auto max-w-4xl px-4 py-8">
      <h1 className="text-xl font-semibold text-slate-900 dark:text-slate-100">Net worth</h1>
      <p className="mt-1 text-sm text-slate-600 dark:text-slate-400">
        Log each account's balance whenever you check it — this app tracks categorized spending,
        not live balances, so there's no automatic way to know what an account currently holds.
        Archived accounts are left out.
      </p>

      {error && (
        <div className="mt-4 rounded-lg bg-red-50 p-3 text-sm text-red-700 dark:bg-red-950/40 dark:text-red-300">
          {error}
        </div>
      )}

      {netWorth.currency === null ? (
        <div className="mt-6">
          <p className="mb-6 rounded-lg bg-indigo-50 p-4 text-sm text-indigo-900 dark:bg-indigo-950/30 dark:text-indigo-200">
            These accounts use different currencies, so there's no single combined net worth —
            here's each currency's total on its own.
          </p>
          {(netWorth.perCurrency ?? []).map((c) => (
            <CurrencySection key={c.currency} data={c} />
          ))}
        </div>
      ) : (
        <div className="mt-6">
          <div className="rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
            <p className="text-xs font-medium uppercase tracking-wide text-slate-500">Net worth</p>
            <p className="mt-1 text-2xl font-semibold text-slate-900 dark:text-slate-100">
              {currency(netWorth.total, 0, netWorth.currency)}
            </p>
          </div>
          <NetWorthChart data={netWorth.history} currencyCode={netWorth.currency} />
        </div>
      )}

      {accounts.length === 0 ? (
        <p className="mt-8 rounded-lg bg-slate-50 p-6 text-center text-sm text-slate-500 dark:bg-slate-900">
          No accounts yet. Add one from Manage to start tracking its balance.
        </p>
      ) : (
        <div className="mt-8 grid grid-cols-1 gap-4 sm:grid-cols-2">
          {accounts.map((a) => (
            <AccountBalanceCard
              key={a.id}
              account={a}
              netWorthAccount={netWorthAccounts.find((nw) => nw.accountId === a.id)}
              onChanged={load}
            />
          ))}
        </div>
      )}
    </div>
  );
}

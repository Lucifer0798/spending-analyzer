import { useState } from "react";
import { UploadPage } from "./components/UploadPage";
import { Dashboard } from "./components/Dashboard";
import { TransactionsTable } from "./components/TransactionsTable";
import { resetAllData } from "./api";

type Tab = "upload" | "dashboard" | "transactions";

function App() {
  const [tab, setTab] = useState<Tab>("upload");
  const [refreshKey, setRefreshKey] = useState(0);

  const tabs: { id: Tab; label: string }[] = [
    { id: "upload", label: "Upload" },
    { id: "dashboard", label: "Dashboard" },
    { id: "transactions", label: "Transactions" },
  ];

  const handleReset = async () => {
    if (!confirm("This will delete all imported transactions. Continue?")) return;
    await resetAllData();
    setRefreshKey((k) => k + 1);
    setTab("upload");
  };

  return (
    <div className="min-h-screen bg-slate-50 dark:bg-slate-950">
      <header className="border-b border-slate-200 bg-white dark:border-slate-800 dark:bg-slate-900">
        <div className="mx-auto flex max-w-5xl items-center justify-between px-4 py-3">
          <span className="text-lg font-semibold text-slate-900 dark:text-slate-100">Spending Analyzer</span>
          <nav className="flex items-center gap-1">
            {tabs.map((t) => (
              <button
                key={t.id}
                onClick={() => setTab(t.id)}
                className={`rounded-md px-3 py-1.5 text-sm font-medium transition-colors ${
                  tab === t.id
                    ? "bg-indigo-600 text-white"
                    : "text-slate-600 hover:bg-slate-100 dark:text-slate-400 dark:hover:bg-slate-800"
                }`}
              >
                {t.label}
              </button>
            ))}
            <button
              onClick={handleReset}
              className="ml-2 rounded-md px-3 py-1.5 text-sm font-medium text-slate-400 hover:bg-slate-100 hover:text-red-600 dark:hover:bg-slate-800"
              title="Delete all data"
            >
              Reset
            </button>
          </nav>
        </div>
      </header>

      <main key={refreshKey}>
        {tab === "upload" && <UploadPage onDone={() => setTab("dashboard")} />}
        {tab === "dashboard" && <Dashboard />}
        {tab === "transactions" && <TransactionsTable />}
      </main>
    </div>
  );
}

export default App;

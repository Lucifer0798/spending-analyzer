import { useEffect, useMemo, useRef, useState } from "react";
import type { MouseEvent as ReactMouseEvent } from "react";
import { ResponsiveContainer, Sankey } from "recharts";
import { fetchMoneyFlow } from "../api";
import type { DateRangeValue, MoneyFlow } from "../types";
import { currency, currencyPrecise } from "../format";
import { buildFlowGraph, HUB } from "../moneyFlow";
import type { FlowKind, FlowNode } from "../moneyFlow";

interface Props {
  accountId: number | null;
  range: DateRangeValue;
  colorByCategory: Record<string, string>;
}

const NEUTRAL = "#94a3b8";
const HUB_COLOR = "#64748b";

function nodeColor(node: FlowNode): string {
  const byKind: Record<FlowKind, string> = {
    income: "var(--series-1)",
    saved: "var(--series-1)",
    reserve: NEUTRAL,
    folded: NEUTRAL,
    hub: HUB_COLOR,
    group: "var(--series-2)",
    category: "var(--series-2)",
  };
  return node.color ?? byKind[node.kind];
}

interface Hover {
  text: string;
  x: number;
  y: number;
}

// Recharts hands the node's own fields back on `payload`, alongside its layout.
type LaidOutNode = FlowNode & { value: number; depth: number };

/**
 * Where the range's money came from and where it went, as a Sankey: income categories into one
 * hub, out to category groups and categories, with the rest as savings. Transfers between your own
 * accounts are on neither side. Renders nothing without income or spending, or across mixed
 * currencies.
 */
export function MoneyFlowCard({ accountId, range, colorByCategory }: Props) {
  const [flow, setFlow] = useState<MoneyFlow | null>(null);
  const [hover, setHover] = useState<Hover | null>(null);
  const [showTable, setShowTable] = useState(false);
  const boxRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    fetchMoneyFlow(accountId, range).then(setFlow).catch(() => setFlow(null));
  }, [accountId, range]);

  const graph = useMemo(() => (flow ? buildFlowGraph(flow, colorByCategory) : null), [flow, colorByCategory]);

  if (!flow?.applicable || !flow.currency || !graph || (flow.totalIncome === 0 && flow.totalSpending === 0)) {
    return null;
  }

  const cur = flow.currency;
  const leaves = graph.nodes.filter((_, i) => !graph.links.some((l) => l.source === i)).length;
  const height = Math.min(640, Math.max(280, leaves * 30 + 40));
  const pct = (v: number) => (graph.hubValue > 0 ? `${Math.round((v / graph.hubValue) * 100)}%` : "");

  const showAt = (e: ReactMouseEvent, text: string) => {
    const box = boxRef.current?.getBoundingClientRect();
    if (!box) return;
    setHover({ text, x: e.clientX - box.left, y: e.clientY - box.top });
  };

  const renderNode = (props: { x: number; y: number; width: number; height: number; payload: unknown }) => {
    const node = props.payload as LaidOutNode;
    const labelLeft = node.depth === 0;
    const label = `${node.name} ${currency(node.value, 0, cur)}`;
    return (
      <g
        onMouseMove={(e) => showAt(e, `${node.name}: ${currencyPrecise(node.value, cur)} · ${pct(node.value)}`)}
        onMouseLeave={() => setHover(null)}
      >
        <rect
          x={props.x}
          y={props.y}
          width={props.width}
          height={Math.max(props.height, 1)}
          rx={2}
          style={{ fill: nodeColor(node) }}
        />
        <text
          x={labelLeft ? props.x - 6 : props.x + props.width + 6}
          y={props.y + props.height / 2}
          dy="0.35em"
          textAnchor={labelLeft ? "end" : "start"}
          className="fill-slate-700 stroke-white text-[12px] dark:fill-slate-200 dark:stroke-slate-900"
          style={{ paintOrder: "stroke", strokeWidth: 3, strokeLinejoin: "round" }}
        >
          {label}
        </text>
      </g>
    );
  };

  const renderLink = (props: {
    sourceX: number;
    targetX: number;
    sourceY: number;
    targetY: number;
    sourceControlX: number;
    targetControlX: number;
    linkWidth: number;
    payload: { source: unknown; target: unknown; value: number };
  }) => {
    const source = props.payload.source as LaidOutNode;
    const target = props.payload.target as LaidOutNode;
    // Colored by where the money went, so a category's band matches its node.
    const color = nodeColor(target.kind === "hub" ? source : target);
    return (
      <path
        d={`M${props.sourceX},${props.sourceY} C${props.sourceControlX},${props.sourceY} ${props.targetControlX},${props.targetY} ${props.targetX},${props.targetY}`}
        fill="none"
        strokeWidth={Math.max(props.linkWidth, 1)}
        className="opacity-35 transition-opacity hover:opacity-60"
        style={{ stroke: color }}
        onMouseMove={(e) =>
          showAt(e, `${source.name} → ${target.name}: ${currencyPrecise(props.payload.value, cur)} · ${pct(props.payload.value)}`)
        }
        onMouseLeave={() => setHover(null)}
      />
    );
  };

  const summary =
    flow.shortfall > 0
      ? `Spent ${currency(flow.shortfall, 0, cur)} more than came in`
      : `Kept ${currency(flow.saved, 0, cur)} of ${currency(flow.totalIncome, 0, cur)} (${
          flow.totalIncome > 0 ? Math.round((flow.saved / flow.totalIncome) * 100) : 0
        }%)`;

  return (
    <div className="mt-8 rounded-lg border border-slate-200 bg-white p-4 dark:border-slate-800 dark:bg-slate-900">
      <div className="mb-1 flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-sm font-semibold text-slate-700 dark:text-slate-300">Money flow</h2>
        <span className="text-xs text-slate-500">{summary}</span>
      </div>
      <div className="mb-3 hidden flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-600 sm:flex dark:text-slate-400">
        <span className="inline-flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-sm" style={{ background: "var(--series-1)" }} /> Income &amp; saved
        </span>
        <span className="inline-flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-sm" style={{ background: "var(--series-2)" }} /> Spending (or the category's own color)
        </span>
        {flow.shortfall > 0 && (
          <span className="inline-flex items-center gap-1.5">
            <span className="h-2.5 w-2.5 rounded-sm" style={{ background: NEUTRAL }} /> From savings
          </span>
        )}
      </div>

      {flow.income.length === 0 && (
        <p className="mb-2 text-xs text-slate-500">
          No income in this range — only deposits in a category marked as income count, so
          everything spent shows as coming from savings.
        </p>
      )}

      {/* A phone is too narrow for the labels on both sides, so it gets the table instead. */}
      <div ref={boxRef} className="relative hidden overflow-x-auto sm:block">
        <div className="min-w-[640px]" style={{ height }}>
          <ResponsiveContainer width="100%" height="100%">
            <Sankey
              data={graph}
              nodeWidth={10}
              nodePadding={16}
              sort={false}
              margin={{ top: 8, right: 170, bottom: 8, left: 150 }}
              node={renderNode}
              link={renderLink}
            />
          </ResponsiveContainer>
        </div>
        {hover && (
          <div
            role="tooltip"
            className="pointer-events-none absolute z-10 max-w-xs rounded-md border border-slate-200 bg-white px-2 py-1 text-xs text-slate-800 shadow-sm dark:border-slate-700 dark:bg-slate-800 dark:text-slate-100"
            style={{ left: hover.x + 12, top: hover.y + 12 }}
          >
            {hover.text}
          </div>
        )}
      </div>

      <button
        onClick={() => setShowTable((s) => !s)}
        className="mt-2 hidden text-xs font-medium text-indigo-600 hover:underline sm:inline dark:text-indigo-400"
      >
        {showTable ? "Hide table" : "Show as a table"}
      </button>
      <div className={showTable ? "" : "sm:hidden"}>
        <FlowTable flow={flow} cur={cur} />
      </div>
    </div>
  );
}

function FlowTable({ flow, cur }: { flow: MoneyFlow; cur: string }) {
  const rows: { label: string; detail: string; amount: number }[] = [
    ...flow.income.map((s) => ({ label: s.category, detail: "Income", amount: s.total })),
    ...(flow.shortfall > 0 ? [{ label: "From savings", detail: "Spent beyond income", amount: flow.shortfall }] : []),
    ...flow.spending.map((s) => ({ label: s.category, detail: s.group ? `Spending · ${s.group}` : "Spending", amount: s.total })),
    ...(flow.saved > 0 ? [{ label: "Saved", detail: "Left over", amount: flow.saved }] : []),
  ];
  return (
    <table className="mt-2 w-full text-sm">
      <caption className="sr-only">Money flow: {HUB} by source, and where it went</caption>
      <thead>
        <tr className="border-b border-slate-200 text-left text-xs text-slate-500 dark:border-slate-800">
          <th className="py-1.5 font-medium">Category</th>
          <th className="py-1.5 font-medium">Flow</th>
          <th className="py-1.5 text-right font-medium">Amount</th>
        </tr>
      </thead>
      <tbody>
        {rows.map((r) => (
          <tr key={`${r.detail}|${r.label}`} className="border-b border-slate-100 dark:border-slate-800/60">
            <td className="py-1.5 text-slate-800 dark:text-slate-200">{r.label}</td>
            <td className="py-1.5 text-slate-500">{r.detail}</td>
            <td className="py-1.5 text-right tabular-nums text-slate-800 dark:text-slate-200">{currencyPrecise(r.amount, cur)}</td>
          </tr>
        ))}
      </tbody>
    </table>
  );
}

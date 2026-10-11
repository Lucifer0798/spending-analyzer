import type { MoneyFlow } from "./types";

/** What a node stands for -- decides its color and which side its label sits on. */
export type FlowKind = "income" | "reserve" | "hub" | "group" | "category" | "folded" | "saved";

export interface FlowNode {
  name: string;
  kind: FlowKind;
  /** The category's own color when it has one; otherwise the kind decides. */
  color?: string;
}

export interface FlowLink {
  source: number;
  target: number;
  value: number;
}

export const HUB = "Money in";
export const RESERVE = "From savings";
export const SAVED = "Saved";

/**
 * Below this share of everything flowing through, a spending category is folded into one "smaller
 * categories" node -- a sliver a couple of pixels tall can't carry a readable label. Nothing is
 * lost: the table view lists every category.
 */
const FOLD_SHARE = 0.02;

type Sink = MoneyFlow["spending"][number];

/** Folds the sinks below `threshold` into one, when there are at least two to fold. */
function fold(sinks: Sink[], threshold: number, foldedName: string): { kept: Sink[]; folded: Sink | null } {
  const small = sinks.filter((s) => s.total < threshold);
  if (small.length < 2) return { kept: sinks, folded: null };
  return {
    kept: sinks.filter((s) => s.total >= threshold),
    folded: { category: foldedName, group: null, total: small.reduce((n, s) => n + s.total, 0) },
  };
}

/**
 * Turns a MoneyFlow into Sankey nodes and links: income categories (and, when spending outran
 * income, "From savings") feed one hub; the hub feeds each category group -- which fans out to its
 * categories -- each ungrouped category directly, and whatever was left over as "Saved".
 */
export function buildFlowGraph(flow: MoneyFlow, colorByCategory: Record<string, string>) {
  const nodes: FlowNode[] = [];
  const links: FlowLink[] = [];
  const add = (node: FlowNode) => nodes.push(node) - 1;

  const hubValue = Math.max(flow.totalIncome, flow.totalSpending);
  const threshold = hubValue * FOLD_SHARE;

  const sources = flow.income.map((s) => ({ index: add({ name: s.category, kind: "income" }), value: s.total }));
  if (flow.shortfall > 0) sources.push({ index: add({ name: RESERVE, kind: "reserve" }), value: flow.shortfall });
  const hub = add({ name: HUB, kind: "hub" });
  for (const s of sources) links.push({ source: s.index, target: hub, value: s.value });

  // Saved first, so the headline number sits at the top; then groups, biggest first, each with its
  // categories together; then ungrouped categories in the server's (biggest-first) order. The chart
  // keeps this order rather than re-sorting, which is what stops a group's bands crossing others.
  if (flow.saved > 0) links.push({ source: hub, target: add({ name: SAVED, kind: "saved" }), value: flow.saved });

  const ungrouped = flow.spending.filter((s) => s.group === null);
  const groups = new Map<string, Sink[]>();
  for (const s of flow.spending) {
    if (s.group !== null) groups.set(s.group, [...(groups.get(s.group) ?? []), s]);
  }

  const groupEntries = [...groups.entries()].sort(
    (a, b) => b[1].reduce((n, s) => n + s.total, 0) - a[1].reduce((n, s) => n + s.total, 0)
  );
  for (const [group, members] of groupEntries) {
    const groupNode = add({ name: group, kind: "group" });
    links.push({ source: hub, target: groupNode, value: members.reduce((n, s) => n + s.total, 0) });
    const { kept, folded } = fold(members, threshold, `Other ${group}`);
    for (const s of kept) {
      const node = add({ name: s.category, kind: "category", color: colorByCategory[s.category] });
      links.push({ source: groupNode, target: node, value: s.total });
    }
    if (folded) links.push({ source: groupNode, target: add({ name: folded.category, kind: "folded" }), value: folded.total });
  }

  const { kept, folded } = fold(ungrouped, threshold, "Smaller categories");
  for (const s of kept) {
    const node = add({ name: s.category, kind: "category", color: colorByCategory[s.category] });
    links.push({ source: hub, target: node, value: s.total });
  }
  if (folded) links.push({ source: hub, target: add({ name: folded.category, kind: "folded" }), value: folded.total });

  return { nodes, links, hubValue };
}

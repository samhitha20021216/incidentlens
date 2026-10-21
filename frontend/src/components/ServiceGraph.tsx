import type { Edge } from "../types";

interface Props {
  edges: Edge[];
  affected?: string[];
  rootCause?: string;
  suspected?: string | null;
  unhealthy?: string[];
}

const W = 150;
const H = 34;
const GAP_X = 60;
const GAP_Y = 22;

/** Layered call graph: callers on the left, dependencies to the right. */
export function ServiceGraph({ edges, affected = [], rootCause, suspected, unhealthy = [] }: Props) {
  const nodes = Array.from(new Set(edges.flatMap((e) => [e.from, e.to]))).sort();
  if (nodes.length === 0) return <p className="muted">No traffic seen yet.</p>;

  const layer = new Map<string, number>();
  const callers = (n: string) => edges.filter((e) => e.to === n).map((e) => e.from);
  const depth = (n: string, seen: Set<string>): number => {
    if (layer.has(n)) return layer.get(n)!;
    if (seen.has(n)) return 0;
    seen.add(n);
    const d = Math.max(-1, ...callers(n).map((c) => depth(c, seen))) + 1;
    layer.set(n, d);
    return d;
  };
  nodes.forEach((n) => depth(n, new Set()));

  const columns: string[][] = [];
  nodes.forEach((n) => (columns[layer.get(n)!] ??= []).push(n));
  const pos = new Map<string, { x: number; y: number }>();
  columns.forEach((col, ci) => col.forEach((n, ri) => pos.set(n, { x: ci * (W + GAP_X), y: ri * (H + GAP_Y) })));
  const width = columns.length * (W + GAP_X) - GAP_X;
  const height = Math.max(...columns.map((c) => c.length)) * (H + GAP_Y) - GAP_Y;

  const cls = (n: string) =>
    n === rootCause ? "node root" : n === suspected ? "node suspect" : affected.includes(n) || unhealthy.includes(n)
      ? "node bad" : "node";

  return (
    <svg className="graph" viewBox={`-4 -4 ${width + 8} ${height + 8}`} role="img" aria-label="Service dependency graph">
      <defs>
        <marker id="arrow" viewBox="0 0 10 10" refX="9" refY="5" markerWidth="6" markerHeight="6" orient="auto">
          <path d="M0,0 L10,5 L0,10 z" className="arrowhead" />
        </marker>
      </defs>
      {edges.map((e) => {
        const a = pos.get(e.from)!;
        const b = pos.get(e.to)!;
        const hot = affected.includes(e.from) && (affected.includes(e.to) || e.to === suspected);
        return (
          <path key={`${e.from}-${e.to}`} className={hot ? "edge hot" : "edge"} markerEnd="url(#arrow)"
            d={`M${a.x + W},${a.y + H / 2} C${a.x + W + GAP_X / 2},${a.y + H / 2} ${b.x - GAP_X / 2},${b.y + H / 2} ${b.x},${b.y + H / 2}`} />
        );
      })}
      {nodes.map((n) => {
        const p = pos.get(n)!;
        return (
          <g key={n} className={cls(n)} transform={`translate(${p.x},${p.y})`}>
            <rect width={W} height={H} rx={8} />
            <text x={W / 2} y={H / 2 + 4} textAnchor="middle">{n}</text>
          </g>
        );
      })}
    </svg>
  );
}

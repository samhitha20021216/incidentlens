import type { ReactNode } from "react";

/**
 * Deliberately tiny Markdown renderer for LLM output (headings, bullets, bold, inline code, citations).
 * It builds React elements, never raw HTML, so model output cannot inject markup.
 */
export function Markdown({ text, onCite }: { text: string; onCite?: (id: string) => void }) {
  const blocks: ReactNode[] = [];
  let list: ReactNode[] = [];
  const flush = () => {
    if (list.length) blocks.push(<ul key={`ul${blocks.length}`}>{list}</ul>);
    list = [];
  };
  text.split("\n").forEach((raw, i) => {
    const line = raw.trimEnd();
    if (/^#{1,3} /.test(line)) {
      flush();
      blocks.push(<h4 key={i}>{inline(line.replace(/^#+ /, ""), onCite)}</h4>);
    } else if (/^\s*[-*] /.test(line)) {
      list.push(<li key={i}>{inline(line.replace(/^\s*[-*] /, ""), onCite)}</li>);
    } else if (line.trim() === "") {
      flush();
    } else {
      flush();
      blocks.push(<p key={i}>{inline(line, onCite)}</p>);
    }
  });
  flush();
  return <div className="markdown">{blocks}</div>;
}

function inline(text: string, onCite?: (id: string) => void): ReactNode[] {
  const parts = text.split(/(\*\*[^*]+\*\*|`[^`]+`|_[^_]+_|\[[ETG]\d+\])/g);
  return parts.map((p, i) => {
    if (/^\*\*.+\*\*$/.test(p)) return <strong key={i}>{p.slice(2, -2)}</strong>;
    if (/^`.+`$/.test(p)) return <code key={i}>{p.slice(1, -1)}</code>;
    if (/^_.+_$/.test(p)) return <em key={i}>{p.slice(1, -1)}</em>;
    const cite = p.match(/^\[([ETG]\d+)\]$/);
    if (cite) {
      return (
        <button key={i} className="cite" onClick={() => onCite?.(cite[1])} title="Show this evidence">
          {cite[1]}
        </button>
      );
    }
    return p;
  });
}

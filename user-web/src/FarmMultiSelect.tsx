import { useState } from "react";
import FarmIcon from "./FarmIcon";

export default function FarmMultiSelect({ label, options, selected, onChange }: {
  label: string;
  options: readonly string[];
  selected: readonly string[];
  onChange: (values: string[]) => void;
}) {
  const [query, setQuery] = useState("");
  const results = options.filter((option) => option.replace(/\s/g, "").includes(query.trim().replace(/\s/g, "")));
  return (
    <details className="farm-multi-select">
      <summary aria-label={`${label} 선택`}>
        <span>{label}</span><strong>{selected.length ? `${selected.length}개 선택` : "전체"}</strong>
        <svg className="farm-multi-chevron" viewBox="0 0 12 12" fill="currentColor" aria-hidden="true">
          <path d="M1 1.67h10L6 10.33Z" />
        </svg>
      </summary>
      <div className="farm-multi-options" role="group" aria-label={`${label} 복수 선택`}>
        {options.length > 8 && <div className="farm-multi-find"><FarmIcon name="search" />
          <input type="search" aria-label={`${label} 항목 찾기`} placeholder={`${label} 찾기`} value={query}
            onChange={(event) => setQuery(event.currentTarget.value)} onKeyDown={(event) => { if (event.key === "Enter") event.preventDefault(); }} />
        </div>}
        <label className="farm-multi-all"><input type="checkbox" checked={!selected.length} onChange={() => onChange([])} />전체</label>
        <div className="farm-multi-option-list">
          {results.map((option) => <label key={option}>
            <input type="checkbox" checked={selected.includes(option)} onChange={() => onChange(selected.includes(option)
              ? selected.filter((value) => value !== option) : [...selected, option])} />{option}
          </label>)}
          {!results.length && <p>일치하는 항목이 없어요.</p>}
        </div>
      </div>
    </details>
  );
}

import type { ReactNode } from "react";
import { useState } from "react";
import FarmIcon from "./FarmIcon";
import FarmMultiSelect from "./FarmMultiSelect";
import FarmScheduleFilter from "./FarmScheduleFilter";
import type { FarmJob } from "./FarmDiscovery";
import { getGyeonggiRegion, gyeonggiRegions } from "./gyeonggiRegions";
import { emptyFarmSearchFilters, experienceOptions, farmScheduleError, farmSearchFields, getFarmJobSearchFields, hasFarmFilters, normalizeFarmSearchFilters, normalizeWorkType, updateFarmSearchSelection, workTypes } from "./farmSearch";
import { cropGroups, getCropOptions } from "./farmCropCatalog";
import type { FarmFilters, FarmSearchFilters } from "./farmSearch";

type FilterField = Exclude<keyof FarmFilters, "query">;

const filterPanels = [
  { label: "근무지역", icon: "pin", fields: ["region"] },
  { label: "품목", icon: "sprout", fields: ["group", "crop"] },
  { label: "경력·작업형태", icon: "clipboard", fields: ["experience", "type"] },
  { label: "근무일·시간", icon: "calendar", fields: [] },
] as const;

export function FarmFilterFields({ fields, filters, jobs, onChange }: {
  fields: readonly FilterField[];
  filters: FarmFilters;
  jobs: FarmJob[];
  onChange: (value: FarmFilters) => void;
}) {
  const crops = getCropOptions(filters.group, jobs);
  const allTypes = [...new Set([...workTypes, ...jobs.map((job) => getFarmJobSearchFields(job).type).filter(Boolean)])];
  const choices = {
    region: { label: "근무지역", all: "전체", options: gyeonggiRegions },
    group: { label: "작물 분류", all: "전체", options: cropGroups },
    crop: { label: "품목", all: "전체", options: crops },
    experience: { label: "경력", all: "전체", options: experienceOptions },
    type: { label: "작업형태", all: "전체", options: allTypes },
  };
  return (
    <div className="farm-filter-fields">
      {fields.map((key) => {
        const { label, all, options } = choices[key];
        return (
          <label key={key}>{label}
            <select value={key === "group" && filters[key] === "기타" ? "기타(모니터링)" : filters[key]} onChange={(event) => onChange({
              ...filters,
              [key]: event.currentTarget.value,
              ...(key === "group" ? { crop: "" } : {}),
            })}>
              <option value="">{all}</option>
              {options.map((value) => <option key={value}>{value}</option>)}
            </select>
          </label>
        );
      })}
    </div>
  );
}

export function FarmSearchEditor({ applied, jobs, onApply, regionContent, onPanelChange }: {
  regionContent?: ReactNode;
  onPanelChange?: (panel: number) => void;
  applied: FarmSearchFilters;
  jobs: FarmJob[];
  onApply: (filters: FarmSearchFilters) => void;
}) {
  const active = normalizeFarmSearchFilters(applied);
  const [draft, setDraft] = useState(() => normalizeFarmSearchFilters(applied));
  const [panel, setPanel] = useState(active.dates.length || active.startTime || active.endTime ? 3 : active.crop.length || active.group.length ? 1 : active.experience.length || active.type.length ? 2 : 0);
  const normalizedDraft = normalizeFarmSearchFilters(draft);
  const fieldLabels: Record<keyof FarmFilters, string> = {
    region: "근무지역", group: "작물 분류", crop: "품목", experience: "경력", type: "작업형태", query: "검색어",
  };
  function updateFilters(value: FarmSearchFilters) {
    const next = normalizeFarmSearchFilters(value);
    setDraft(next);
    if (!farmScheduleError(next)) onApply(next);
  }
  const scheduleError = farmScheduleError(normalizedDraft);
  const options = {
    region: gyeonggiRegions,
    group: cropGroups,
    crop: draft.group.length ? [...new Set(draft.group.flatMap((group) => getCropOptions(group, jobs)))] : getCropOptions("", jobs),
    experience: experienceOptions,
    type: [...new Set([...workTypes.map(normalizeWorkType), ...jobs.map((job) => getFarmJobSearchFields(job).type).filter(Boolean)])],
  };
  return (
    <form className="farm-search-editor" role="search" aria-label="일자리 상세검색"
      onSubmit={(event) => { event.preventDefault(); if (scheduleError) return; const next = normalizeFarmSearchFilters(draft); setDraft(next); onApply(next); }}>
      <div className="farm-segmented farm-search-panel-tabs" role="group" aria-label="검색 조건 분류">
        {filterPanels.map(({ label, icon }, index) => (
          <button key={label} type="button" aria-pressed={index === panel} onClick={() => { setPanel(index); onPanelChange?.(index); }}>
            <FarmIcon name={icon} />{label}
          </button>
        ))}
      </div>
      {panel === 0 && regionContent ? regionContent : <>
      {panel === 3 ? <FarmScheduleFilter filters={normalizedDraft} onChange={updateFilters} /> : <div className="farm-multi-fields">
        {filterPanels[panel].fields.map((field) => <FarmMultiSelect key={field} label={fieldLabels[field]}
          options={options[field]} selected={draft[field]}
          onChange={(values) => updateFilters(updateFarmSearchSelection(draft, field, values, jobs))} />)}
      </div>}
      <label className="farm-keyword">검색어
        <input type="search" value={draft.query} placeholder="작업지, 작목, 공고명"
          onChange={(event) => updateFilters({ ...draft, query: event.currentTarget.value })} />
      </label>
      {hasFarmFilters(active) && <div className="farm-active-filters" role="group" aria-label="현재 적용된 검색 조건">
        {farmSearchFields.flatMap((key) => active[key].map((value) => (
          <button type="button" key={`${key}-${value}`} aria-label={`${fieldLabels[key]} ${value} 조건 해제`} onClick={() => {
            const next = updateFarmSearchSelection(active, key, active[key].filter((item) => item !== value), jobs);
            setDraft(next); onApply(next);
          }}>{fieldLabels[key]}: {value} <span aria-hidden="true">×</span></button>
        )))}
        {active.query && <button type="button" aria-label="검색어 조건 해제" onClick={() => {
          const next = { ...active, query: "" }; setDraft(next); onApply(next);
        }}>검색어: {active.query} <span aria-hidden="true">×</span></button>}
        {active.dates.map((date) => <button type="button" key={`date-${date}`} aria-label={`근무일 ${date} 조건 해제`} onClick={() => {
          const next = { ...active, dates: active.dates.filter((value) => value !== date) }; setDraft(next); onApply(next);
        }}>근무일: {date.slice(5).replace("-", ".")} <span aria-hidden="true">×</span></button>)}
        {(active.startTime || active.endTime) && <button type="button" aria-label="근무시간 조건 해제" onClick={() => {
          const next = { ...active, startTime: "", endTime: "" }; setDraft(next); onApply(next);
        }}>근무시간: {active.startTime || "제한 없음"} ~ {active.endTime || "제한 없음"} <span aria-hidden="true">×</span></button>}
      </div>}
      {scheduleError && <p className="farm-schedule-error" role="alert">{scheduleError}</p>}
      <div className="farm-filter-actions">
        <button type="button" className="secondary" onClick={() => { setDraft(emptyFarmSearchFilters); onApply(emptyFarmSearchFilters); }}>조건 초기화</button>
      </div>
      </>}
    </form>
  );
}

export function FarmRegionSummary({ jobs, limit, selected, onChoose }: {
  jobs: FarmJob[];
  limit?: number;
  selected?: string;
  onChoose: (region: string) => void;
}) {
  const counts = new Map<string, number>();
  for (const job of jobs) {
    const region = getGyeonggiRegion(job.place);
    if (region) counts.set(region, (counts.get(region) ?? 0) + 1);
  }
  const busyRegions = gyeonggiRegions.filter((region) => counts.has(region))
    .sort((a, b) => (counts.get(b) ?? 0) - (counts.get(a) ?? 0))
    .slice(0, limit);
  return (
    <div className="farm-region-summary">
      <button type="button" aria-pressed={selected === undefined ? undefined : selected === ""} onClick={() => onChoose("")}>
        경기도 전체 <strong>{jobs.length}건</strong>
      </button>
      {busyRegions.map((region) => (
        <button key={region} type="button" aria-pressed={selected === undefined ? undefined : selected === region} onClick={() => onChoose(region)}>
          {region} <strong>{counts.get(region)}건</strong>
        </button>
      ))}
    </div>
  );
}

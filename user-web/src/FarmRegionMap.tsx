import { useCallback, useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import type { FarmJob } from "./FarmDiscovery";
import { getGyeonggiRegion, gyeonggiRegions } from "./gyeonggiRegions";
import { gyeonggiMapRegions } from "./gyeonggiMapRegions";

export default function FarmRegionMap({ jobs, selected, onChoose, farms, selectedFarm, onChooseFarm }: {
  farms?: readonly { id: string; label: string; region: string }[];
  selectedFarm?: string;
  onChooseFarm?: (id: string) => void;
  jobs: FarmJob[];
  selected: string;
  onChoose: (region: string) => void;
}) {
  const [hoveredRegion, setHoveredRegion] = useState("");
  const [view, setView] = useState("지도");
  const [zoom, setZoom] = useState(1);
  const selectId = useId();
  const mapViewport = useRef<HTMLDivElement>(null);
  const mapSvg = useRef<SVGSVGElement>(null);
  const zoomAnchor = useRef<{ x: number; y: number; mapX: number; mapY: number } | null>(null);
  const changeZoom = useCallback((amount: number, clientX?: number, clientY?: number) => {
    const viewport = mapViewport.current;
    const svg = mapSvg.current;
    if (!viewport || !svg) return;
    const bounds = viewport.getBoundingClientRect();
    const x = clientX === undefined ? viewport.clientWidth / 2 : clientX - bounds.left - viewport.clientLeft;
    const y = clientY === undefined ? viewport.clientHeight / 2 : clientY - bounds.top - viewport.clientTop;
    const size = svg.getBoundingClientRect();
    zoomAnchor.current = { x, y,
      mapX: (viewport.scrollLeft + x) / size.width,
      mapY: (viewport.scrollTop + y) / size.height };
    setZoom((current) => Math.max(1, Math.min(2.5, current + amount)));
  }, []);
  useLayoutEffect(() => {
    const viewport = mapViewport.current;
    const svg = mapSvg.current;
    const anchor = zoomAnchor.current;
    if (!viewport || !svg || !anchor) return;
    const size = svg.getBoundingClientRect();
    viewport.scrollLeft = anchor.mapX * size.width - anchor.x;
    viewport.scrollTop = anchor.mapY * size.height - anchor.y;
    zoomAnchor.current = null;
  }, [zoom]);
  useEffect(() => {
    const viewport = mapViewport.current;
    if (!viewport) return;
    const handleWheel = (event: WheelEvent) => {
      if ((!event.ctrlKey && !event.shiftKey) || event.deltaY === 0) return;
      event.preventDefault();
      event.stopPropagation();
      changeZoom(event.deltaY < 0 ? 0.5 : -0.5, event.clientX, event.clientY);
    };
    viewport.addEventListener("wheel", handleWheel, { passive: false });
    return () => viewport.removeEventListener("wheel", handleWheel);
  }, [view, changeZoom]);
  const drag = useRef<{ id: number; x: number; y: number; left: number; top: number; moved: boolean } | null>(null);
  const counts = new Map<string, number>();
  for (const job of jobs) {
    const region = getGyeonggiRegion(job.place);
    if (region) counts.set(region, (counts.get(region) ?? 0) + 1);
  }
  const infoRegion = hoveredRegion || selected;
  const selectionLabel = `${selected || "경기도 전체"} · 모집 공고 ${selected ? counts.get(selected) ?? 0 : jobs.length}건`;

  return <div className="farm-region-map">
    <div className="farm-region-view-toggle" role="group" aria-label="지역 선택 방식">
      {["근무지역 선택", "지도"].map((value) => <button key={value} type="button" aria-pressed={view === value}
        onClick={() => setView(value)}>{value}</button>)}
    </div>
    {view === "근무지역 선택" ? <label className="farm-region-select" htmlFor={selectId}>
      시·군 선택
      <select id={selectId} value={selected} onChange={(event) => onChoose(event.currentTarget.value)}>
        <option value="">경기도 전체 ({jobs.length}건)</option>
        {gyeonggiRegions.map((region) => <option key={region} value={region}>{region} ({counts.get(region) ?? 0}건)</option>)}
      </select>
    </label> : <>
      <div className="farm-region-map-viewport" onPointerLeave={() => setHoveredRegion("")}><div className="farm-region-map-zoom" role="group" aria-label="지도 크기">
        <button type="button" aria-label="지도 축소" disabled={zoom === 1} onClick={() => changeZoom(-0.5)}>−</button>
        <span>{Math.round(zoom * 100)}%</span>
        <button type="button" aria-label="지도 확대" disabled={zoom === 2.5} onClick={() => changeZoom(0.5)}>＋</button>
      </div>
      <div ref={mapViewport} className="farm-region-boundary-scroll" tabIndex={0} role="region" aria-label="경기도 지도, 끌어서 이동"
        onPointerDown={(event) => {
          if (!event.isPrimary || event.button !== 0) return;
          drag.current = { id: event.pointerId, x: event.clientX, y: event.clientY,
            left: event.currentTarget.scrollLeft, top: event.currentTarget.scrollTop, moved: false };
        }}
        onPointerMove={(event) => {
          const start = drag.current;
          if (!start || start.id !== event.pointerId || event.buttons === 0) return;
          const dx = event.clientX - start.x;
          const dy = event.clientY - start.y;
          if (!start.moved && Math.hypot(dx, dy) < 6) return;
          start.moved = true;
          event.currentTarget.setPointerCapture(event.pointerId);
          event.currentTarget.scrollLeft = start.left - dx;
          event.currentTarget.scrollTop = start.top - dy;
        }}
        onPointerUp={(event) => {
          if (event.currentTarget.hasPointerCapture(event.pointerId)) event.currentTarget.releasePointerCapture(event.pointerId);
        }}
        onPointerCancel={() => { drag.current = null; }}
        onClickCapture={(event) => {
          if (event.detail !== 0 && drag.current?.moved) { event.preventDefault(); event.stopPropagation(); }
        }}>
        <svg ref={mapSvg} className="farm-region-boundary-map" viewBox="0 0 400 460" style={{ width: `${zoom * 100}%` }}
          role="group" aria-label="경기도 시·군 선택 지도">
          {gyeonggiMapRegions.map(({ name, path }) => <path key={name} d={path} fillRule="evenodd"
            className={(counts.get(name) ? "has-jobs " : "") + (selected === name ? "is-selected" : "")}
            role="button" tabIndex={0} aria-pressed={selected === name} aria-label={`${name} 모집 공고 ${counts.get(name) ?? 0}건`}
            onPointerEnter={() => setHoveredRegion(name)} onPointerLeave={() => setHoveredRegion("")}
            onFocus={() => setHoveredRegion(name)} onBlur={() => setHoveredRegion("")}
            onClick={() => onChoose(name)} onKeyDown={(event) => {
              if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onChoose(name); }
            }} />)}

          {zoom > 1 && gyeonggiMapRegions.map(({ name, label }) => <text key={name} x={label[0]} y={label[1]}
            className={selected === name ? "is-selected" : ""} aria-hidden="true">{name}</text>)}
          {farms?.map((farm, index) => {
            const region = gyeonggiMapRegions.find((item) => item.name === farm.region);
            if (!region) return null;
            const siblings = farms.filter((item) => item.region === farm.region);
            const position = siblings.findIndex((item) => item.id === farm.id);
            const x = region.label[0] + (position % 3 - (Math.min(siblings.length, 3) - 1) / 2) * 26;
            const y = region.label[1] - 19 - Math.floor(position / 3) * 30;
            return <g key={farm.id} className="farm-management-pin" transform={`translate(${x} ${y})`}
              role="button" tabIndex={0} aria-label={`농장 ${index + 1}: ${farm.label}`} aria-pressed={selectedFarm === farm.id}
              onPointerEnter={() => setHoveredRegion(farm.region)} onPointerLeave={() => setHoveredRegion("")}
              onFocus={() => setHoveredRegion(farm.region)} onBlur={() => setHoveredRegion("")}
              onClick={() => onChooseFarm?.(farm.id)} onKeyDown={(event) => {
                if (event.key === "Enter" || event.key === " ") { event.preventDefault(); onChooseFarm?.(farm.id); }
              }}>
              <circle r="13" /><text textAnchor="middle" dominantBaseline="central" aria-hidden="true">{index + 1}</text>
            </g>;
          })}
        </svg>
      </div>
      {infoRegion && <div className="farm-region-hover-info" role="status" aria-live="polite"><strong>{infoRegion}</strong><span>{farms ? "등록 공고" : "모집 공고"} <b>{counts.get(infoRegion) ?? 0}</b>건</span></div>}
      </div>
      <details className="farm-region-map-source"><summary>지도 정보</summary>
        <p>통계청 2018년 행정경계를 시·군 단위로 합쳐 표시했어요.</p>
        <a href="https://github.com/southkorea/southkorea-maps/tree/master/kostat/2018" target="_blank" rel="noreferrer">경계 데이터 출처</a>
      </details>
    </>}
    <p className="farm-region-map-selection" role="status">{selectionLabel}</p>
  </div>;
}
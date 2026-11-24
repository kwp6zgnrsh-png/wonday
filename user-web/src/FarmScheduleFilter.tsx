import { useState } from "react";
import FarmIcon from "./FarmIcon";
import { shiftWorkMonth, workCalendarCells } from "./farmJobCalendar";
import type { FarmSearchFilters } from "./farmSearch";

export default function FarmScheduleFilter({ filters, onChange }: {
  filters: FarmSearchFilters;
  onChange: (filters: FarmSearchFilters) => void;
}) {
  const [month, setMonth] = useState(() => (filters.dates[0] || new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date())).slice(0, 7));
  const toggle = (date: string) => onChange({ ...filters, dates: filters.dates.includes(date)
    ? filters.dates.filter((value) => value !== date) : [...filters.dates, date].sort() });
  return (
    <div className="farm-search-schedule">
      <div className="farm-schedule-title"><strong>일할 수 있는 날짜</strong><span>{filters.dates.length ? `${filters.dates.length}일 선택` : "모든 날짜"}</span></div>
      <div className="farm-home-calendar-heading">
        <button type="button" aria-label="근무일 이전 달" onClick={() => setMonth(shiftWorkMonth(month, -1))}><FarmIcon name="back" /></button>
        <strong>{Number(month.slice(0, 4))}년 {Number(month.slice(5))}월</strong>
        <button type="button" aria-label="근무일 다음 달" onClick={() => setMonth(shiftWorkMonth(month, 1))}><FarmIcon name="arrow" /></button>
      </div>
      <div className="farm-home-calendar-grid" role="group" aria-label="일할 수 있는 날짜 복수 선택">
        {["일", "월", "화", "수", "목", "금", "토"].map((day) => <span key={day} className="farm-calendar-weekday">{day}</span>)}
        {workCalendarCells(month).map((date, index) => date ? <button type="button" key={date}
          aria-label={`${date} 근무일`} aria-pressed={filters.dates.includes(date)} onClick={() => toggle(date)}>{Number(date.slice(8))}</button>
          : <span key={`blank-${index}`} aria-hidden="true" />)}
      </div>
      <p>날짜는 여러 개 고를 수 있고, 선택한 날짜 중 모집 중인 공고를 찾아요.</p>
      {!!filters.dates.length && <div className="farm-schedule-picked">
        {filters.dates.map((date) => <button type="button" key={date} aria-label={`${date} 근무일 선택 해제`} onClick={() => toggle(date)}>
          {date.slice(5).replace("-", ".")} <span aria-hidden="true">×</span>
        </button>)}
        <button type="button" onClick={() => onChange({ ...filters, dates: [] })}>날짜 전체 해제</button>
      </div>}
      <fieldset className="farm-schedule-times">
        <legend>일할 수 있는 시간</legend>
        <div className="form-grid">
          <label>가능한 시작 시각<input type="time" value={filters.startTime}
            onInput={(event) => onChange({ ...filters, startTime: event.currentTarget.value })}
            onChange={(event) => onChange({ ...filters, startTime: event.currentTarget.value })} /></label>
          <label>가능한 종료 시각<input type="time" value={filters.endTime}
            onInput={(event) => onChange({ ...filters, endTime: event.currentTarget.value })}
            onChange={(event) => onChange({ ...filters, endTime: event.currentTarget.value })} /></label>
        </div>
        <p>설정한 시간 안에 시작하고 끝나는 공고를 찾아요. 비워 두면 시간 제한 없이 검색해요.</p>
        {(filters.startTime || filters.endTime) && <button type="button" className="farm-schedule-time-reset" onClick={() => onChange({ ...filters, startTime: "", endTime: "" })}>시간 제한 해제</button>}
      </fieldset>
    </div>
  );
}

import { useId, useState } from "react";
import FarmIcon from "./FarmIcon";
import { shiftWorkMonth, workCalendarCells } from "./farmJobCalendar";

export default function FarmWorkdayPicker() {
  const titleId = useId();
  const [today] = useState(() => new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date()));
  const [month, setMonth] = useState(() => today.slice(0, 7));
  const [dates, setDates] = useState<string[]>([]);
  const toggle = (date: string) => setDates((previous) => previous.includes(date)
    ? previous.filter((value) => value !== date) : [...previous, date].sort());
  return <section className="farm-workday-picker" aria-labelledby={titleId}>
    <h3 id={titleId}>근무일 <span>{dates.length}일 선택</span></h3>
    <p className="farm-workday-help">일할 날짜를 모두 선택해 주세요. 다시 누르면 해제돼요.</p>
    <input type="hidden" name="dates" value={dates.join(",")} />
    <div className="farm-home-calendar-heading">
      <button type="button" aria-label="근무일 이전 달" disabled={month <= today.slice(0, 7)}
        onClick={() => setMonth(shiftWorkMonth(month, -1))}><FarmIcon name="back" /></button>
      <strong aria-live="polite">{Number(month.slice(0, 4))}년 {Number(month.slice(5))}월</strong>
      <button type="button" aria-label="근무일 다음 달" onClick={() => setMonth(shiftWorkMonth(month, 1))}><FarmIcon name="arrow" /></button>
    </div>
    <div className="farm-home-calendar-grid" role="group" aria-label="근무일 선택 달력">
      {["일", "월", "화", "수", "목", "금", "토"].map((day) => <span key={day} className="farm-calendar-weekday">{day}</span>)}
      {workCalendarCells(month).map((date, index) => date ? <button type="button" key={date}
        disabled={date < today} aria-label={`${date} 근무일`} aria-pressed={dates.includes(date)}
        aria-current={date === today ? "date" : undefined} onClick={() => toggle(date)}>{Number(date.slice(8))}</button>
        : <span key={`blank-${index}`} aria-hidden="true" />)}
    </div>
    <div className="farm-workday-selection" aria-live="polite">
      {dates.length ? <>
        <div className="farm-workday-selection-heading"><span>선택한 근무일</span>
          <button type="button" className="farm-calendar-reset" onClick={() => setDates([])}>전체 해제</button></div>
        <div className="farm-workday-chips">{dates.map((date) => <button type="button" key={date}
          aria-label={`${date} 근무일 해제`} onClick={() => toggle(date)}>{Number(date.slice(0, 4))}.{date.slice(5).replace("-", ".")} <span aria-hidden="true">×</span></button>)}</div>
      </> : <p>선택한 근무일이 없어요.</p>}
    </div>
  </section>;
}
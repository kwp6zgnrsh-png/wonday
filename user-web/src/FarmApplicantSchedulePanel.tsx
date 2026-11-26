import { useId, useState } from "react";
import type { ReactNode } from "react";
import type { FarmWork } from "./farmWorkActions";
import FarmIcon from "./FarmIcon";
import { shiftWorkMonth, workCalendarCells } from "./farmJobCalendar";
import { groupScheduleApplicants, scheduleWeek, shiftScheduleDate } from "./farmApplicantSchedule";

const shortDate = (date: string) => `${Number(date.slice(5, 7))}.${Number(date.slice(8))}`;
export default function FarmApplicantSchedule({ dates, applications, renderDate, renderApplication, controls }: {
  controls?: ReactNode;
  dates: readonly string[];
  applications: readonly FarmWork[];
  renderDate: (date: string) => ReactNode;
  renderApplication: (entry: FarmWork) => ReactNode;
}) {
  const calendarId = useId();
  const allDates = [...new Set([...dates, ...applications.map((entry) => entry.date)])].sort();
  const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date());
  const [anchor, setAnchor] = useState(() => allDates.find((date) => date >= today) ?? allDates[0] ?? today);
  const [selectedDate, setSelectedDate] = useState("");
  const [mode, setMode] = useState("날짜별");
  const [expanded, setExpanded] = useState(false);
  const [month, setMonth] = useState(() => anchor.slice(0, 7));
  const week = scheduleWeek(anchor);
  const visibleDates = allDates.filter((date) => selectedDate ? date === selectedDate : date >= week[0] && date <= week[6]);
  const visibleApplications = applications.filter((entry) => visibleDates.includes(entry.date));
  const people = groupScheduleApplicants(visibleApplications);
  function changeWeek(offset: number) {
    const next = shiftScheduleDate(week[0], offset * 7);
    setAnchor(next); setMonth(next.slice(0, 7)); setSelectedDate("");
  }
  return <div className="farm-applicant-schedule">
    <div className="farm-segmented farm-applicant-modes" role="group" aria-label="지원자 보기 방식">
      {["날짜별", "지원자별"].map((item) => <button type="button" key={item} aria-pressed={mode === item}
        onClick={() => setMode(item)}><FarmIcon name={item === "날짜별" ? "calendar" : "users"} />{item}</button>)}
    </div>
    <div className="farm-schedule-panel">
      <div className="farm-schedule-toolbar">
        <button type="button" className="farm-schedule-month" aria-expanded={expanded} aria-controls={calendarId}
          onClick={() => { setExpanded(!expanded); setMonth(anchor.slice(0, 7)); }}>
          <FarmIcon name="calendar" />{Number(anchor.slice(0, 4))}년 {Number(anchor.slice(5, 7))}월
          <span aria-hidden="true" className={expanded ? "is-open" : ""}>▼</span>
        </button>
        <div className="farm-schedule-week-nav"><button type="button" aria-label="이전 주" onClick={() => changeWeek(-1)}><FarmIcon name="back" /></button>
          <button type="button" aria-label="다음 주" onClick={() => changeWeek(1)}><FarmIcon name="arrow" /></button></div>
      </div>
      {expanded && <div id={calendarId} className="farm-schedule-month-calendar" role="group" aria-label="월간 일정 달력">
        <div className="farm-home-calendar-heading"><button type="button" aria-label="일정 이전 달" onClick={() => setMonth(shiftWorkMonth(month, -1))}><FarmIcon name="back" /></button>
          <strong>{Number(month.slice(0, 4))}년 {Number(month.slice(5))}월</strong>
          <button type="button" aria-label="일정 다음 달" onClick={() => setMonth(shiftWorkMonth(month, 1))}><FarmIcon name="arrow" /></button></div>
        <div className="farm-home-calendar-grid">{["일", "월", "화", "수", "목", "금", "토"].map((day) => <span key={day} className="farm-calendar-weekday">{day}</span>)}
          {workCalendarCells(month).map((date, index) => date ? <button type="button" key={date} aria-label={`${date} 일정`}
            aria-pressed={selectedDate === date} className={allDates.includes(date) ? "has-schedule" : ""}
            onClick={() => { setAnchor(date); setSelectedDate(date); setExpanded(false); }}>{Number(date.slice(8))}{allDates.includes(date) && <i />}</button>
            : <span key={`blank-${index}`} aria-hidden="true" />)}</div>
      </div>}
      <div className="farm-schedule-week" role="group" aria-label="주간 일정">
        {week.map((date, index) => {
          const count = applications.filter((entry) => entry.date === date).length;
          return <button type="button" key={date} aria-label={`${date} 지원 ${count}건`} aria-pressed={selectedDate === date}
            aria-current={date === today ? "date" : undefined} onClick={() => setSelectedDate(selectedDate === date ? "" : date)}>
            <span>{["월", "화", "수", "목", "금", "토", "일"][index]}</span><strong>{Number(date.slice(8))}</strong>
            <small className={count ? "farm-schedule-count" : "farm-schedule-count is-empty"} aria-hidden="true">{count || ""}</small>
          </button>;
        })}
      </div>
      <div className="farm-schedule-range"><span>{shortDate(week[0])}–{shortDate(week[6])}</span>
        <button type="button" aria-pressed={!selectedDate} onClick={() => setSelectedDate("")}>주간 전체</button></div>
    </div>
    {controls}
    <p className="farm-schedule-summary" role="status">{selectedDate ? `${shortDate(selectedDate)} · ` : "이번 주 · "}{mode === "날짜별" ? `근무일 ${visibleDates.length}일` : `지원자 ${people.length}명`} · 지원 {visibleApplications.length}건</p>
    {mode === "날짜별" ? visibleDates.map((date) => <div key={date} className="farm-schedule-day">{renderDate(date)}</div>)
      : people.map((person) => <section className="farm-schedule-person" key={person.name} aria-label={`${person.name} 지원 일정`}>
        <h4><span>{person.name.slice(0, 1)}</span>{person.name}<small>{person.entries.length}건</small></h4>
        {person.entries.map((entry) => <div className="farm-schedule-person-entry" key={entry.id}><span className="farm-schedule-entry-date">{shortDate(entry.date)}</span>{renderApplication(entry)}</div>)}
      </section>)}
    {!(mode === "날짜별" ? visibleDates.length : people.length) && <p className="farm-empty">{mode === "날짜별" ? "선택한 기간에 근무 일정이 없어요." : "선택한 기간에 지원한 사람이 없어요."}</p>}
  </div>;
}
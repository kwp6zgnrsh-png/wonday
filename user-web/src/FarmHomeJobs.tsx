import FarmFavoriteButton from "./FarmFavoriteButton";
import { useState } from "react";
import FarmCursorList from "./FarmCursorList";
import type { FarmJob } from "./FarmDiscovery";
import FarmIcon from "./FarmIcon";
import FarmLocationDisclosure from "./FarmLocationDisclosure";
import FarmRegionMap from "./FarmRegionMap";
import { emptyFarmFilters, hasFarmFilters, searchFarmJobs } from "./farmSearch";
import type { FarmFilters } from "./farmSearch";
import { calendarJobHeat, shiftWorkMonth, workCalendarCells } from "./farmJobCalendar";

const categories = ["전체 일자리", "맞춤 일자리", "지역별 일자리"] as const;
const dateLabel = (value: string) => value.slice(5).replace("-", ".");
const won = new Intl.NumberFormat("ko-KR");

export default function FarmHomeJobs({ employer = false, jobs, preferences, availableDates, onSettings, onOpen, favoriteIds = [], onToggleFavorite }: {
  employer?: boolean;

  jobs: FarmJob[];
  favoriteIds?: readonly number[];
  onToggleFavorite?: (job: FarmJob) => void;
  preferences: FarmFilters;
  availableDates: (job: FarmJob) => string[];
  onSettings: () => void;
  onOpen: (job: FarmJob) => void;
}) {
  const [category, setCategory] = useState<(typeof categories)[number]>("전체 일자리");
  const [view, setView] = useState("목록형");
  const [region, setRegion] = useState("");
  const [month, setMonth] = useState(() => {
    const dates = jobs.flatMap(availableDates).sort();
    const today = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(new Date());
    return (dates.find((date) => date >= today) ?? dates[0] ?? today).slice(0, 7);
  });
  const [selectedDate, setSelectedDate] = useState("");
  const needsPreferences = !employer && category === "맞춤 일자리" && !hasFarmFilters(preferences);
  const sourceJobs = employer && category === "맞춤 일자리" ? jobs.filter((job) => availableDates(job).length > 0) : jobs;
  const filtered = needsPreferences ? [] : searchFarmJobs(sourceJobs, !employer && category === "맞춤 일자리"
    ? preferences : { ...emptyFarmFilters, region: category === "지역별 일자리" ? region : "" });
  const jobDates = new Map(filtered.map((job) => [job.id, [...new Set(availableDates(job))].sort()]));
  const dayCounts = new Map<string, number>();
  for (const dates of jobDates.values()) {
    for (const date of dates) dayCounts.set(date, (dayCounts.get(date) ?? 0) + 1);
  }
  const monthMaximum = Math.max(0, ...[...dayCounts].filter(([date]) => date.startsWith(month)).map(([, count]) => count));
  const results = view === "목록형" ? filtered : filtered.filter((job) =>
    jobDates.get(job.id)?.some((date) => selectedDate ? date === selectedDate : date.startsWith(month)));

  return (
    <section className="farm-home-jobs" aria-label={employer ? "등록한 공고" : "경기도 일자리"}>
      <div className="farm-home-job-tabs" role="group" aria-label="홈 일자리 분류">
        {categories.map((item) => <button key={item} type="button" aria-pressed={category === item}
          onClick={() => { setCategory(item); setSelectedDate(""); }}>{employer ? item === "전체 일자리" ? "전체 공고" : item === "맞춤 일자리" ? "모집 중" : "지역별 공고" : item}</button>)}
      </div>
      <div className="farm-home-job-controls">
        <div className="farm-home-list-toolbar">
        <div className="farm-home-view-toggle" role="group" aria-label="일자리 보기 방식">
          {["목록형", "캘린더형"].map((item) => <button key={item} type="button" aria-pressed={view === item}
            onClick={() => { setView(item); }}>
            <FarmIcon name={item === "목록형" ? "clipboard" : "calendar"} />{item}
          </button>)}
        </div>
        <p className="farm-home-results" role="status">{view === "캘린더형" && (selectedDate ? `${dateLabel(selectedDate)} · ` : `${Number(month.slice(5))}월 · `)}{employer ? "등록 공고" : "모집 공고"} <strong>{results.length}건</strong></p>
        </div>

        {category === "지역별 일자리" && <FarmRegionMap jobs={jobs} selected={region} onChoose={(value) => {
            setRegion(value); setSelectedDate("");
          }} />}
        {!employer && category === "맞춤 일자리" && !needsPreferences && <div className="farm-home-match-note">
          <span>{Object.values(preferences).filter(Boolean).join(" · ")}</span>
          <button type="button" onClick={onSettings}>조건 변경</button>
        </div>}
      </div>
      {needsPreferences ? <div className="farm-empty">
        <p>관심 조건을 설정하면 맞춤 일자리를 볼 수 있어요.</p>
        <button type="button" className="secondary" onClick={onSettings}>맞춤 조건 설정</button>
      </div> : <>
        {view === "캘린더형" && <div className="farm-home-calendar" aria-label="모집 중인 근무일 달력">
          <div className="farm-home-calendar-heading">
            <button type="button" aria-label="이전 달" onClick={() => {
              setMonth(shiftWorkMonth(month, -1)); setSelectedDate("");
            }}><FarmIcon name="back" /></button>
            <strong>{Number(month.slice(0, 4))}년 {Number(month.slice(5))}월</strong>
            <button type="button" aria-label="다음 달" onClick={() => {
              setMonth(shiftWorkMonth(month, 1)); setSelectedDate("");
            }}><FarmIcon name="arrow" /></button>
          </div>
          <div className="farm-home-calendar-grid">
            {["일", "월", "화", "수", "목", "금", "토"].map((day) => <span key={day} className="farm-calendar-weekday">{day}</span>)}
            {workCalendarCells(month).map((date, index) => {
              if (!date) return <span key={`blank-${index}`} aria-hidden="true" />;
              const count = dayCounts.get(date) ?? 0;
              return <button key={date} type="button" className="farm-calendar-heat-day"
                style={calendarJobHeat(count, monthMaximum)} disabled={!count} aria-pressed={selectedDate === date}
                aria-label={`${date} 모집 공고 ${count}건`}
                onClick={() => { setSelectedDate(selectedDate === date ? "" : date); }}>
                <span>{Number(date.slice(8))}</span>
                {count ? <small>{count}건</small> : null}
              </button>;
            })}
          </div>
          <div className="farm-calendar-density-legend" aria-label="이 달 기준, 색이 진할수록 모집 공고가 많아요">
            <span>공고 수</span><span>적음</span><span className="farm-calendar-density-scale" aria-hidden="true" /><span>많음</span>
          </div>
          <p>공고가 있는 날짜를 눌러 일자리를 확인해 보세요.</p>
          {selectedDate && <button type="button" className="farm-calendar-reset" onClick={() => { setSelectedDate(""); }}>이 달 전체 보기</button>}
        </div>}
        <div className="farm-home-job-rows">
          <FarmCursorList items={results} pageSize={4} queryKey={JSON.stringify([category, view, region, month, selectedDate, preferences])} renderItem={(job) => {
            const dates = jobDates.get(job.id) ?? [];
            return <article key={job.id} className="farm-home-job-item">
              <button type="button" className="farm-home-job-row" onClick={() => onOpen(job)}>
              <span className="farm-home-job-place"><FarmIcon name="pin" />{job.place}</span>
              <span className="farm-home-job-title"><strong>{job.title}</strong><FarmIcon name="arrow" /></span>
              <span className="farm-home-job-badges"><span>{dates.length ? "모집 중" : "모집 마감"}</span>{job.demoPosting && <small>예시 공고</small>}</span>
              <span className="farm-home-job-facts"><span>근무일</span><span>{dates.slice(0, 3).map(dateLabel).join(" · ")}{dates.length > 3 && ` 외 ${dates.length - 3}일`}</span></span>
              <span className="farm-home-job-facts"><span>작업품목</span><span>{job.crop} · {job.type}</span></span>
              <span className="farm-home-job-facts"><span>근무시간</span><span>{job.start}–{job.end} · 하루 {job.count}명</span></span>
              <span className="farm-home-job-wage">일급 <strong>{won.format(job.wage)}원</strong></span>
              </button>
              <div className="farm-job-list-actions">
                <FarmLocationDisclosure job={job} />
                {onToggleFavorite && <FarmFavoriteButton title={job.title} selected={favoriteIds.includes(job.id)} onClick={() => onToggleFavorite(job)} />}
              </div>
            </article>;
          }} />
        </div>
        {!results.length && <p className="farm-empty">{view === "캘린더형" ? "이 달에 모집 중인 근무가 없어요. 다른 달을 확인해 주세요." : employer ? "조건에 맞는 등록 공고가 없어요." : "조건에 맞는 모집 공고가 없어요."}</p>}
      </>}
    </section>
  );
}

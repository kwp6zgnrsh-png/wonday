import FarmFavoriteButton from "./FarmFavoriteButton";
import { useRef } from "react";
import FarmCursorList from "./FarmCursorList";
import type { ReactNode } from "react";
import FarmIcon from "./FarmIcon";
import type { FarmIconName } from "./FarmIcon";
import FarmCropIcon from "./FarmCropIcon";
import FarmHomeJobs from "./FarmHomeJobs";
import FarmLocationDisclosure from "./FarmLocationDisclosure";
import FarmRegionMap from "./FarmRegionMap";
import { FarmSearchEditor } from "./FarmSearchControls";
import { browseModes, getFarmJobSearchFields, hasFarmFilters, searchFarmJobs } from "./farmSearch";
import type { FarmBrowseMode, FarmFilters, FarmSearchFilters } from "./farmSearch";
import type { WorkplacePin } from "./farmMapLocation";

export type FarmJob = {
  id: number;
  title: string;
  crop: string;
  cropGroup?: string;
  type: string;
  place: string;
  workplacePin?: WorkplacePin;
  wage: number;
  count: number;
  dates: string[];
  start: string;
  end: string;
  rest: string;
  description: string;
  experience?: string;
  benefits?: string;
  extraDemoDatesAdded?: true;
  demoPosting?: true;
};

const won = new Intl.NumberFormat("ko-KR");
const workDay = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", timeZone: "Asia/Seoul" });

export function FarmJobCard({ job, openDays, onOpen, favorite = false, onFavoriteChange }: {
  job: FarmJob;
  openDays: number;
  favorite?: boolean;
  onFavoriteChange?: () => void;
  onOpen: () => void;
}) {
  const fields = getFarmJobSearchFields(job);
  return (
    <article className="farm-job-item">
    <button type="button" className="farm-job-card" onClick={onOpen}>
      <FarmCropIcon crop={job.crop} />
      <span className="farm-job-copy">
        <span className="farm-job-type">{fields.group} &gt; {fields.crop} · {fields.type}{job.demoPosting && " · 예시"}</span>
        <strong>{job.title}</strong>
        <span className="farm-job-place"><FarmIcon name="pin" />{job.place}</span>
        <span className="farm-job-schedule">{job.start}–{job.end} · 하루 {job.count}명</span>
        <span className="farm-job-experience">경력 · {fields.experience || "조건 미기재"}</span>
        <span className="farm-job-meta">
          <span>{openDays > 0 ? `${openDays}일 모집` : "모집 마감"}</span>
          <b>{won.format(job.wage)}원 <small>/ 일</small></b>
        </span>
      </span>
      <span className="farm-job-arrow"><FarmIcon name="arrow" /></span>
    </button>
    <div className="farm-job-list-actions">
      <FarmLocationDisclosure job={job} />
      {onFavoriteChange && <FarmFavoriteButton title={job.title} selected={favorite} onClick={onFavoriteChange} />}
    </div>
    </article>
  );
}

function FarmShortcuts({ items }: { items: ReadonlyArray<readonly [string, FarmIconName, () => void]> }) {
  return (
    <div className="farm-shortcuts">
      {items.map(([label, icon, action]) => (
        <button key={label} onClick={action}><span><FarmIcon name={icon} /></span>{label}</button>
      ))}
    </div>
  );
}

function FarmCropBackground() {
  return (<div className="farm-worker-crop-pattern" aria-hidden="true">
          <FarmCropIcon crop="배추" />
          <FarmCropIcon crop="당근" />
          <FarmCropIcon crop="무" />
          <FarmCropIcon crop="딸기" />
          <FarmCropIcon crop="오이" />
          <FarmCropIcon crop="토마토" />
          <FarmCropIcon crop="상추" />
          <FarmCropIcon crop="대파" />
          <FarmCropIcon crop="사과" />
          <FarmCropIcon crop="시금치" />
        </div>);
}

export function FarmHome({ displayName, employer, jobs, preview, preferences, availableDates, onSettings, onOpen, applicationCount, favoriteCount, onBrowse, onManage, onInsurance, onCreate, onProfile, onBookmarks, favoriteIds = [], onToggleFavorite }: {
  employer: boolean;
  displayName?: string;
  jobs: FarmJob[];
  favoriteIds?: readonly number[];
  onToggleFavorite?: (job: FarmJob) => void;
  preview: ReactNode;
  preferences: FarmFilters;
  availableDates: (job: FarmJob) => string[];
  onSettings: () => void;
  onOpen: (job: FarmJob) => void;
  applicationCount: number;
  favoriteCount: number;
  onBrowse: (mode?: FarmBrowseMode, filters?: FarmFilters) => void;
  onManage: () => void;
  onInsurance: () => void;
  onCreate: () => void;
  onProfile: () => void;
  onBookmarks: () => void;
}) {
  return (
    <div className="farm-home">
      <section className={employer ? "farm-home-spotlight" : "farm-home-spotlight farm-worker-spotlight"} aria-labelledby="farm-home-heading">
        {!employer && <FarmCropBackground />}
        <div className="farm-home-heading">
          <h2 id="farm-home-heading">{displayName || (employer ? "이온농장" : "김하루")}님, 반가워요.</h2>
          <span className="farm-location"><FarmIcon name="pin" />경기도</span>
        </div>
        {employer ? <button type="button" className="farm-home-banner" onClick={onCreate}>
          <span className="farm-banner-copy">
            <strong>함께 일할 분을 찾아요</strong>
            <span>공고 등록<FarmIcon name="arrow" /></span>
          </span>
          <span className="farm-banner-crops" aria-hidden="true">
            <FarmCropIcon crop="배추" />
            <FarmCropIcon crop="사과" />
            <FarmCropIcon crop="딸기" />
          </span>
        </button> : <>
          <div className="farm-worker-intro">
            <div className="farm-worker-intro-copy">
              <strong>농작업 일자리를 찾아요</strong>
            </div>
          </div>
          <div className="farm-home-actions" role="group" aria-label="내 일자리 현황">
            <button type="button" className="farm-home-stat-card" onClick={onManage}>
              <span>지원 현황</span>
              <strong>{applicationCount}건</strong>
              <FarmIcon name="arrow" />
            </button>
            <button type="button" className="farm-home-stat-card" onClick={onBookmarks}>
              <span>찜한 일자리</span>
              <strong>{favoriteCount}건</strong>
              <FarmIcon name="arrow" />
            </button>
          </div>
        </>}
      </section>
      {employer && <section className="farm-home-services" aria-labelledby="farm-home-services-title">
        <h3 id="farm-home-services-title">자주 찾는 서비스</h3>
        <FarmShortcuts items={[
          ["내 공고", "grid", onManage],
          ["WONDAY 보험", "shield", onInsurance],
          ["마이페이지", "profile", onProfile],
        ]} />
      </section>}
      <div className={employer ? "farm-home-content" : "farm-home-content farm-worker-content"}>
        {employer ? <section className="farm-job-section" aria-labelledby="farm-home-jobs">
          <div className="farm-section-heading">
            <h3 id="farm-home-jobs">등록한 공고</h3>
            <button onClick={() => onBrowse()}>전체 보기 <FarmIcon name="arrow" /></button>
          </div>
          {preview || <p className="farm-empty">표시할 공고가 없어요.</p>}
        </section> : <FarmHomeJobs jobs={jobs} preferences={preferences} availableDates={availableDates}
          onSettings={onSettings} onOpen={onOpen} favoriteIds={favoriteIds} onToggleFavorite={onToggleFavorite} />}
      </div>
    </div>
  );
}

export function FarmJobList({ employer, jobs, mode, onModeChange, filters, onFilterChange, searchFilters, onSearchFilterChange, preferences, onSettings, onOpen, openDays, availableDates, favoriteIds = [], onToggleFavorite }: {
  employer: boolean;
  displayName?: string;
  jobs: FarmJob[];
  favoriteIds?: readonly number[];
  onToggleFavorite?: (job: FarmJob) => void;
  mode: FarmBrowseMode;
  onModeChange: (mode: FarmBrowseMode) => void;
  filters: FarmFilters;
  onFilterChange: (filters: FarmFilters) => void;
  searchFilters: FarmSearchFilters;
  onSearchFilterChange: (filters: FarmSearchFilters) => void;
  preferences: FarmFilters;
  onSettings: () => void;
  onOpen: (job: FarmJob) => void;
  openDays: (job: FarmJob) => number;
  availableDates: (job: FarmJob) => string[];
}) {
  const openJobs = jobs.filter((job) => openDays(job) > 0);
  const hasPreferences = hasFarmFilters(preferences);
  const results = mode === "맞춤 일자리"
    ? hasPreferences ? searchFarmJobs(openJobs, preferences) : []
    : mode === "일자리 상세검색" ? searchFarmJobs(jobs, searchFilters, availableDates) : searchFarmJobs(openJobs, filters);
  return (
    <section className="farm-list" aria-labelledby="farm-list-title">
      <div className="farm-list-intro">
        <h2 id="farm-list-title">{employer ? "내 공고 관리" : "일자리 정보"}</h2>
      </div>
      {!employer && <div className="farm-segmented farm-browse-modes" role="group" aria-label="일자리 메뉴">
        {browseModes.map((value) => (
          <button key={value} type="button" aria-pressed={value === mode} onClick={() => { if (value !== mode) onModeChange(value); }}>{value}</button>
        ))}
      </div>}
      {mode === "일자리 상세검색" && <FarmSearchEditor applied={searchFilters} jobs={jobs} onApply={onSearchFilterChange} />}
      {mode === "맞춤 일자리" && (
        <section className="farm-search-note" aria-label="맞춤 조건">
          <p>{hasPreferences ? Object.values(preferences).filter(Boolean).join(" · ") : "관심 조건을 저장하면 맞춤 공고를 볼 수 있어요."}</p>
          <button className="secondary" onClick={onSettings}>맞춤 설정</button>
        </section>
      )}
      {mode === "지역별 일자리" && (
        <section className="farm-search-note" aria-label="시·군별 모집 현황">
          <FarmRegionMap jobs={openJobs} selected={filters.region} onChoose={(region) => onFilterChange({ ...filters, region })} />
        </section>
      )}
      <div className="farm-list-toolbar">
        <p role="status">공고 <strong>{results.length}건</strong></p>
      </div>
      {results.length === 0 && (mode !== "맞춤 일자리" || hasPreferences) && <p className="farm-empty">조건에 맞는 공고가 없어요.</p>}
      <FarmCursorList items={results} queryKey={JSON.stringify([mode, filters, searchFilters, preferences])}
        renderItem={(job) => <FarmJobCard key={job.id} job={job} openDays={openDays(job)} onOpen={() => onOpen(job)} favorite={favoriteIds.includes(job.id)}
          onFavoriteChange={onToggleFavorite ? () => onToggleFavorite(job) : undefined} />} />
    </section>
  );
}

export function FarmProfile({ displayName, employer, metrics, children, activeMetric, onMetricSelect, onManage, onCreate, onInsurance, onLogout }: {
  employer: boolean;
  displayName?: string;
  metrics: ReadonlyArray<readonly [string, number]>;
  children?: ReactNode;
  activeMetric?: string;
  onMetricSelect?: (index: number) => void;
  onManage: () => void;
  onCreate: () => void;
  onInsurance: () => void;
  onLogout: () => void;
}) {
  const jobSection = useRef<HTMLDivElement>(null);
  return (
    <div className="farm-profile">
      <section className="farm-profile-hero" aria-labelledby="farm-profile-title">
        <FarmCropBackground />
        <div className="farm-profile-account">
          <span className="farm-profile-avatar" aria-hidden="true">{displayName?.slice(0, 1) || (employer ? "온" : "하")}</span>
          <div>
            <p>{employer ? "고용주" : "근로자"} 계정</p>
            <strong>{displayName || (employer ? "이온농장" : "김하루")}님</strong>
          </div>
        </div>
        <div className="farm-profile-metrics" role="group" aria-label="내 일자리 현황">
          {metrics.map(([label, count], index) => (
            <button key={label} type="button" aria-pressed={onMetricSelect ? activeMetric === label : undefined}
              aria-controls={!employer ? "farm-profile-content" : undefined}
              onClick={() => {
                if (onMetricSelect) {
                  onMetricSelect(index);
                  jobSection.current?.scrollIntoView({ block: "start" });
                } else onManage();
              }}>
              <strong>{count}건</strong><span>{label}</span>
            </button>
          ))}
        </div>
      </section>
      {employer ? <section className="farm-profile-section" aria-labelledby="farm-profile-work">
        <h3 id="farm-profile-work">공고 관리</h3>
        <FarmShortcuts items={[
          ["내 공고", "grid", onManage],
          ["공고 등록", "plus", onCreate],
        ]} />
      </section> : <div ref={jobSection} id="farm-profile-content">{children}</div>}
      <section className="farm-profile-section" aria-labelledby="farm-profile-services">
        <h3 id="farm-profile-services">서비스 및 계정</h3>
        <div className="farm-profile-links">
          <button className="farm-profile-insurance-link" onClick={onInsurance}><FarmIcon name="shield" />WONDAY 보험<FarmIcon name="arrow" /></button>
          </div>
        <button type="button" className="farm-profile-logout" onClick={onLogout}>로그아웃</button>
      </section>
    </div>
  );
}

export function FarmJobOverview({ job, openDays, favorite, onFavoriteChange }: {
  job: FarmJob;
  openDays: number;
  favorite?: boolean;
  onFavoriteChange?: () => void;
}) {
  const dates = [...new Set(job.dates)].sort();
  const first = dates[0];
  const last = dates.at(-1);
  const workPeriod = first && last
    ? [first, ...(first === last ? [] : [last])].map((date) => workDay.format(new Date(date + "T12:00:00+09:00"))).join(" ~ ")
    : "등록된 날짜 없음";
  return (
    <>
      <div className="farm-detail-hero">
        {onFavoriteChange && <div className="farm-detail-topline">
          <button className="farm-favorite-button" aria-pressed={favorite} onClick={onFavoriteChange}>
            <FarmIcon name="bookmark" />찜하기
          </button>
        </div>}
        <div className="farm-detail-title">
          <div className="farm-detail-intro">
            <div className="farm-detail-labels">
              <span className="farm-detail-recruiting">{openDays > 0 ? "모집 중" : "모집 마감"}</span>
              <span className="farm-detail-type">{job.crop}{job.demoPosting && " · 예시 공고"}</span>
            </div>
            <div className="farm-detail-title-row"><h2>{job.title}</h2><FarmCropIcon crop={job.crop} /></div>
            <p><FarmIcon name="pin" />{job.place}</p>
          </div>
        </div>
        <div className="farm-detail-pay">
          <span>일급 <strong>{won.format(job.wage)}원</strong></span>
          <span>{openDays > 0 ? `${openDays}개 근무일 모집` : "현재 모집이 마감되었어요"}</span>
        </div>
      </div>
      <section className="farm-detail-conditions" aria-labelledby="farm-conditions">
        <div className="farm-condition-card">
          <h3 id="farm-conditions">한눈에 보는 근무 조건</h3>
          <div className="farm-condition-period">
            <span><FarmIcon name="calendar" />작업 기간</span>
            <strong>{workPeriod}</strong>
            <small>등록된 근무일 {dates.length}일 · 아래에서 날짜를 선택해요</small>
          </div>
          <dl className="farm-condition-grid">
            {([
              ["근무시간", `${job.start}–${job.end}`, "clock", "green"],
              ["무급 휴게", job.rest, "cup", "amber"],
              ["하루 모집", `${job.count}명`, "users", "blue"],
              ["필요 경력", job.experience || "공고에 별도 안내 없음", "clipboard", "violet"],
            ] as const).map(([label, value, icon, color]) => (
              <div key={label} className="farm-condition-item">
                <dt><span className={`farm-condition-icon ${color}`}><FarmIcon name={icon} /></span>{label}</dt>
                <dd>{value}</dd>
              </div>
            ))}
          </dl>
        </div>
        <div className="farm-task-card">
          <h3><FarmIcon name="sprout" />이런 일을 해요</h3>
          <div className="farm-task-tags"><span>{job.crop}</span><span>{job.type}</span></div>
          <p>{job.description || "자세한 작업 내용은 고용주에게 확인해 주세요."}</p>
          {job.benefits && <div className="farm-task-benefits"><strong>복리후생·교통</strong><p>{job.benefits}</p></div>}
        </div>
      </section>
    </>
  );
}


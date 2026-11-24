import { useState } from "react";
import type { ReactNode } from "react";
import { FarmFilterFields } from "./FarmSearchControls";
import { emptyFarmFilters, hasFarmFilters, profileJobTabs } from "./farmSearch";
import type { FarmFilters, FarmProfilePage, FarmResume } from "./farmSearch";
import type { FarmJob } from "./FarmDiscovery";

export default function FarmProfileJobs({ page, onPageChange, applied, saved, matched, jobs, preferences, onSavePreferences, resume, onSaveResume, onBrowse }: {
  page: FarmProfilePage;
  onPageChange: (page: FarmProfilePage) => void;
  applied: ReactNode;
  saved: ReactNode;
  matched: ReactNode;
  jobs: FarmJob[];
  preferences: FarmFilters;
  onSavePreferences: (filters: FarmFilters) => void;
  resume: FarmResume;
  onSaveResume: (value: FarmResume) => void;
  onBrowse: () => void;
}) {
  const [draft, setDraft] = useState(preferences);
  return (
    <section className="farm-profile-section farm-profile-jobs" aria-labelledby="farm-profile-work">
      <h3 id="farm-profile-work">일자리 관리</h3>
      <div className="farm-segmented farm-profile-tabs" role="group" aria-label="마이페이지 일자리 메뉴">
        {profileJobTabs.map((item) => (
          <button key={item} type="button" aria-pressed={page === item} onClick={() => onPageChange(item)}>{item}</button>
        ))}
      </div>
      <div className="farm-profile-tools">
        <button type="button" aria-pressed={page === "맞춤 설정"} onClick={() => { setDraft(preferences); onPageChange("맞춤 설정"); }}>맞춤 설정</button>
        <button type="button" aria-pressed={page === "이력서·경력"} onClick={() => onPageChange("이력서·경력")}>이력서·경력</button>
      </div>
      {page === "지원한 일자리" && (applied || <p className="farm-empty">지원한 일자리가 없어요.</p>)}
      {page === "제안받은 일자리" && <p className="farm-empty">받은 일자리 제안이 없어요.</p>}
      {page === "찜한 일자리" && (saved || (
        <div className="farm-empty">
          <p>찜한 일자리가 없어요.</p>
          <button className="secondary" onClick={onBrowse}>일자리 둘러보기</button>
        </div>
      ))}
      {page === "맞춤 일자리" && (matched || (
        <div className="farm-empty">
          <p>{hasFarmFilters(preferences) ? "저장한 조건에 맞는 공고가 없어요." : "관심 조건을 설정하면 맞춤 공고를 볼 수 있어요."}</p>
          <button className="secondary" onClick={() => { setDraft(preferences); onPageChange("맞춤 설정"); }}>관심 조건 설정</button>
        </div>
      ))}
      {page === "맞춤 설정" && (
        <form className="farm-profile-form" onSubmit={(event) => { event.preventDefault(); onSavePreferences(draft); }}>
          <h3>맞춤 설정</h3>
          <FarmFilterFields fields={["region", "group", "crop", "experience", "type"]} filters={draft} jobs={jobs} onChange={setDraft} />
          <div className="farm-filter-actions">
            <button type="button" className="secondary" onClick={() => setDraft(emptyFarmFilters)}>조건 초기화</button>
            <button type="submit" className="primary">관심 조건 저장</button>
          </div>
        </form>
      )}
      {page === "이력서·경력" && (
        <form className="farm-profile-form" onSubmit={(event) => {
          event.preventDefault();
          const form = new FormData(event.currentTarget);
          onSaveResume({
            summary: String(form.get("summary") ?? "").trim(),
            experience: String(form.get("experience") ?? "").trim(),
          });
        }}>
          <h3>이력서·경력</h3>
          <label>자기소개 <textarea name="summary" defaultValue={resume.summary} maxLength={800} /></label>
          <label>농작업 경험 <textarea name="experience" defaultValue={resume.experience} maxLength={800} /></label>
          <button type="submit" className="primary">이력서 저장</button>
        </form>
      )}
    </section>
  );
}

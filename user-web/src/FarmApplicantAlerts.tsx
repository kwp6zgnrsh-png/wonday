
import { useState } from "react";
import type { FarmJob } from "./FarmDiscovery";
import type { FarmWork } from "./farmWorkActions";
import FarmIcon from "./FarmIcon";
import FarmCursorList from "./FarmCursorList";

export default function FarmApplicantAlerts({ jobs, works, onOpen, onOpenWorker }: {
  onOpenWorker: (entry: FarmWork) => void;
  jobs: FarmJob[]; works: FarmWork[]; onOpen: (job: FarmJob) => void;
}) {
  const [filter, setFilter] = useState("지원 중");
  const jobIds = new Set(jobs.map((job) => job.id));
  const applications = works.filter((entry) => jobIds.has(entry.job.id));
  const results = applications.filter((entry) => filter === "전체" || entry.status === filter)
    .sort((a, b) => b.date.localeCompare(a.date));
  return <section className="farm-applicant-alerts" aria-labelledby="farm-alerts-title">
    <h2 id="farm-alerts-title">이력서 알림</h2>
    <p>내 공고에 들어온 지원 소식을 확인하세요.</p>
    <div className="farm-segmented" role="group" aria-label="지원 알림 분류">
      {["지원 중", "근무 확정", "전체"].map((item) => <button type="button" key={item} aria-pressed={filter === item}
        onClick={() => setFilter(item)}>{item === "지원 중" ? "지원 대기" : item}</button>)}
    </div>
    <p className="fine" role="status">지원 내역 {results.length}건</p>
    <FarmCursorList items={results} queryKey={filter} renderItem={(entry) => <button type="button" key={entry.id}
      className="farm-management-applicant" onClick={() => {
        if (entry.status === "근무 확정") return onOpenWorker(entry);
        const job = jobs.find((item) => item.id === entry.job.id);
        if (job) onOpen(job);
      }}><span><strong>{entry.worker}</strong><small>{entry.job.title}</small><small>근무일 {entry.date} · {entry.job.start}–{entry.job.end}</small></span>
      <span className="badge farm-applicant-status" data-status={entry.status}>{entry.status}</span><FarmIcon name="arrow" /></button>} />
    {!results.length && <p className="farm-empty">{filter === "지원 중" ? "새로 확인할 지원 내역이 없어요." : "해당하는 지원 내역이 없어요."}</p>}
  </section>;
}
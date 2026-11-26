
import FarmApplicantSchedule from "./FarmApplicantSchedulePanel";
import { useEffect, useRef, useState } from "react";
import type { FarmJob } from "./FarmDiscovery";
import { FarmJobCard } from "./FarmDiscovery";
import type { FarmWork } from "./farmWorkActions";
import FarmIcon from "./FarmIcon";
import FarmRegionMap from "./FarmRegionMap";
import FarmCursorList from "./FarmCursorList";
import { getGyeonggiRegion } from "./gyeonggiRegions";
import { groupEmployerFarms } from "./farmEmployerJobs";

export default function FarmWorkManagement({ jobs, works, selectedFarmId, onChooseFarm, onOpen, openDays, onCreate, onOpenWorker }: {
  onOpenWorker: (entry: FarmWork) => void;
  jobs: FarmJob[];
  works: FarmWork[];
  selectedFarmId: string;
  onChooseFarm: (id: string) => void;
  onOpen: (job: FarmJob) => void;
  openDays: (job: FarmJob) => number;
  onCreate: () => void;
}) {
  const detailRef = useRef<HTMLElement>(null);
  useEffect(() => {
    if (selectedFarmId) detailRef.current?.scrollIntoView({ block: "start" });
  }, [selectedFarmId]);
  const farms = groupEmployerFarms(jobs);
  const [region, setRegion] = useState("");
  const [view, setView] = useState("등록 공고");
  const [status, setStatus] = useState("전체");
  const selected = farms.find((farm) => farm.id === selectedFarmId);
  const visibleFarms = farms.filter((farm) => !region || getGyeonggiRegion(farm.address) === region);
  const jobIds = new Set(selected?.jobs.map((job) => job.id));
  const applications = works.filter((entry) => jobIds.has(entry.job.id));
  const results = applications.filter((entry) => status === "전체" || entry.status === status);
  function renderApplicant(entry: FarmWork) {
    return <button type="button" className="farm-management-applicant" key={entry.id} onClick={() => {
      if (entry.status === "근무 확정") return onOpenWorker(entry);
      const current = selected?.jobs.find((job) => job.id === entry.job.id);
      if (current) onOpen(current);
    }}><span><strong>{entry.worker}</strong><small>{entry.job.title}</small><small>{entry.date} · {entry.job.start}–{entry.job.end}</small></span>
      <span className="badge farm-applicant-status" data-status={entry.status}>{entry.status}</span><FarmIcon name="arrow" /></button>;
  }  function chooseFarm(id: string) {
    onChooseFarm(id);
    setRegion(getGyeonggiRegion(farms.find((farm) => farm.id === id)?.address ?? "") ?? "");
    setStatus("전체");
  }
  const controls = <div className="farm-management-controls">
        <div className="farm-segmented farm-management-menu" role="group" aria-label="농장 관리 메뉴">
          {["등록 공고", "지원 현황"].map((item) => <button type="button" key={item} aria-pressed={view === item} onClick={() => setView(item)}>
            {item} {item === "등록 공고" ? (selected?.jobs.length ?? 0) : applications.length}건</button>)}
        </div>
{view === "지원 현황" && (          <div className="farm-management-status" role="group" aria-label="지원 상태">
            {["전체", "지원 중", "근무 확정"].map((item) => <button type="button" key={item} aria-pressed={status === item} onClick={() => setStatus(item)}>{item === "지원 중" ? "지원 대기" : item}</button>)}
          </div>)}</div>;
  return <section className="farm-work-management" aria-label="농장별 공고 및 지원 관리">
    {!farms.length ? <div className="farm-empty"><p>등록한 농장이 없어요. 공고를 등록하면 작업지가 지도에 표시돼요.</p>
      <button type="button" className="primary" onClick={onCreate}>공고 등록</button></div> : <>
      <div className="farm-management-map">
        <FarmRegionMap jobs={jobs} selected={region} onChoose={(value) => { setRegion(value); onChooseFarm(""); }}
          farms={farms.map((farm) => ({ id: farm.id, label: farm.address, region: getGyeonggiRegion(farm.address) ?? "" }))}
          selectedFarm={selectedFarmId} onChooseFarm={chooseFarm} />
        <p className="fine">농장 번호는 작업지의 시·군 중심을 기준으로 표시돼요.</p>
      </div>
      <div className="farm-management-farms" role="group" aria-label="내 농장 선택">
        {visibleFarms.map((farm) => <button type="button" key={farm.id} aria-pressed={selectedFarmId === farm.id}
          onClick={() => chooseFarm(farm.id)}><span className="farm-management-number">{farms.indexOf(farm) + 1}</span>
          <span><strong>{farm.address}</strong><small>등록 공고 {farm.jobs.length}건 · 지원 대기 {works.filter((entry) => entry.status === "지원 중" && farm.jobs.some((job) => job.id === entry.job.id)).length}건</small></span>
          <FarmIcon name="arrow" /></button>)}
      </div>
      {selected && <section ref={detailRef} className="farm-management-detail" aria-label={`${selected.address} 관리`}>
        <h3><FarmIcon name="pin" />{selected.address}</h3>

        {view === "등록 공고" && controls}
        {view === "등록 공고" ? <FarmCursorList items={selected.jobs} queryKey={selected.id}
          renderItem={(job) => <FarmJobCard key={job.id} job={job} openDays={openDays(job)} onOpen={() => onOpen(job)} />} /> : <>

          <FarmApplicantSchedule controls={controls} key={selected.id} dates={[...new Set(selected.jobs.flatMap((job) => job.dates))]} applications={results}
            renderDate={(date) => <section className="farm-schedule-farm-day"><h4>{date.replaceAll("-", ".")}</h4>
              {selected.jobs.filter((job) => job.dates.includes(date)).map((job) => <button type="button" key={job.id}
                className="farm-schedule-posting" onClick={() => onOpen(job)}><span><strong>{job.title}</strong><small>{job.start}–{job.end}</small></span><FarmIcon name="arrow" /></button>)}
              {results.filter((entry) => entry.date === date).map(renderApplicant)}
              {!results.some((entry) => entry.date === date) && <p className="fine">지원 내역이 없어요.</p>}
            </section>}
            renderApplication={renderApplicant} />        </>}
      </section>}
    </>}
  </section>;
}
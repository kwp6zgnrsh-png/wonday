import type { FarmWork } from "./farmWorkActions";
export default function FarmConfirmedWorkerCard({ entry, onAction }: {
  entry: FarmWork;
  onAction: (entry: FarmWork, action: "근무 완료" | "근무 취소") => void;
}) {
  return <article className="farm-confirmed-worker-card" aria-label={`${entry.worker} ${entry.job.title} 근무 처리`}>
    <div><strong>{entry.worker}</strong><span className="badge farm-applicant-status" data-status={entry.status}>{entry.status}</span></div>
    <p>{entry.job.title}</p>
    <div className="farm-confirmed-actions">
      <button type="button" onClick={() => onAction(entry, "근무 완료")}>근무 완료</button>
      <button type="button" onClick={() => onAction(entry, "근무 취소")}>근무 취소</button>
    </div>
  </article>;
}
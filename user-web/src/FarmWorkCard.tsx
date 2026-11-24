import FarmIcon from "./FarmIcon";
import FarmLocationDisclosure from "./FarmLocationDisclosure";
import { workerCancellationBlockedReason } from "./farmWorkActions";
import type { FarmWork } from "./farmWorkActions";

const day = new Intl.DateTimeFormat("ko-KR", { month: "long", day: "numeric", weekday: "short", timeZone: "Asia/Seoul" });
const money = new Intl.NumberFormat("ko-KR");

export default function FarmWorkCard({ entry, onOpen, onCancel }: {
  entry: FarmWork;
  onOpen: () => void;
  onCancel: () => void;
}) {
  const active = ["지원 중", "근무 확정"].includes(entry.status);
  const blocked = workerCancellationBlockedReason(entry);
  return (
    <article className="work-card farm-work-card" aria-label={`${entry.job.title} ${entry.date}`}>
      <button type="button" className="farm-work-summary" onClick={onOpen}>
        <span className="farm-work-topline"><span className="badge">{entry.status}{entry.demo ? " · 체험" : ""}</span><FarmIcon name="arrow" /></span>
        <h3>{day.format(new Date(entry.date + "T12:00:00+09:00"))}</h3>
        <b>{entry.job.title}</b>
        <p>{entry.job.place} · {entry.job.start}–{entry.job.end}</p>
        <strong>{money.format(entry.job.wage)}원</strong>
      </button>
      <div className="farm-work-actions">
        <FarmLocationDisclosure job={entry.job} />
        {active && <button type="button" className="farm-cancel-action" disabled={!!blocked}
          title={blocked || undefined} onClick={onCancel}>{entry.status === "지원 중" ? "지원 취소" : "근무 취소"}</button>}
      </div>
      {active && blocked && <p className="farm-work-cancel-note">{blocked}</p>}
      {entry.cancelledBy === "근로자" && <p className="farm-work-cancel-note">본인 취소 · {entry.reason}</p>}
    </article>
  );
}

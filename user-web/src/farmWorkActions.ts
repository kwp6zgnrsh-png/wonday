import type { FarmJob } from "./FarmDiscovery";

export type FarmWork = {
  id: string;
  job: FarmJob;
  date: string;
  worker: string;
  status: string;
  reason?: string;
  category?: string;
  at?: string;
  demo?: boolean;
  cancelledBy?: "근로자" | "고용주";
};

export function workerCancellationBlockedReason(work: FarmWork, now = Date.now()) {
  if (!["지원 중", "근무 확정"].includes(work.status)) return "이미 종료되거나 취소된 내역이에요.";
  const start = Date.parse(`${work.date}T${work.job.start}:00+09:00`);
  if (!Number.isFinite(start) || now >= start) return "예정 시작 시각이 지난 근무는 취소할 수 없어요.";
  return "";
}

export function cancelWorkerWork(work: FarmWork, reason: string, now = new Date()): FarmWork | null {
  if (workerCancellationBlockedReason(work, now.getTime()) || !reason.trim()) return null;
  return {
    ...work,
    status: work.status === "지원 중" ? "지원 취소" : "근무 취소",
    category: "근로자 취소",
    cancelledBy: "근로자",
    reason: reason.trim(),
    at: new Intl.DateTimeFormat("sv-SE", {
      timeZone: "Asia/Seoul", year: "numeric", month: "2-digit", day: "2-digit",
      hour: "2-digit", minute: "2-digit", second: "2-digit", hour12: false,
    }).format(now).replace(" ", "T"),
  };
}

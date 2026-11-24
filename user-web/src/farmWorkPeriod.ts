export const workPeriods = ["1일", "7일", "30일", "직접 설정"] as const;
export type WorkPeriod = (typeof workPeriods)[number];
export type WorkPeriodDirection = "upcoming" | "past";
export type WorkDateRange = { from: string; to: string };

function validDate(date: string) {
  return /^\d{4}-\d{2}-\d{2}$/.test(date) && Number.isFinite(Date.parse(date)) && new Date(date).toISOString().slice(0, 10) === date;
}

function shiftDate(date: string, offset: number) {
  const day = new Date(date + "T12:00:00Z");
  day.setUTCDate(day.getUTCDate() + offset);
  return day.toISOString().slice(0, 10);
}

export function workDateRange(period: WorkPeriod, custom: WorkDateRange, today: string, direction: WorkPeriodDirection): WorkDateRange {
  if (period === "직접 설정") return custom;
  const days = Number.parseInt(period, 10);
  return direction === "past" ? { from: shiftDate(today, 1 - days), to: today }
    : { from: today, to: shiftDate(today, days - 1) };
}

export function workDateRangeError(range: WorkDateRange) {
  if (!validDate(range.from) || !validDate(range.to)) return "조회 시작일과 종료일을 선택해 주세요.";
  if (range.from > range.to) return "종료일은 시작일과 같거나 이후로 선택해 주세요.";
  return "";
}

export function filterWorkDates<T extends { date: string; status: string }>(works: T[], range: WorkDateRange, status: string) {
  if (workDateRangeError(range)) return [];
  return works.filter((work) => work.date >= range.from && work.date <= range.to && (status === "전체" || work.status === status));
}

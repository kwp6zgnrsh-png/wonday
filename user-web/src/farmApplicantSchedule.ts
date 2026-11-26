export function shiftScheduleDate(date: string, days: number) {
  const value = new Date(`${date}T12:00:00Z`);
  value.setUTCDate(value.getUTCDate() + days);
  return value.toISOString().slice(0, 10);
}

export function scheduleWeek(date: string) {
  const weekday = new Date(`${date}T12:00:00Z`).getUTCDay();
  const monday = shiftScheduleDate(date, -(weekday + 6) % 7);
  return Array.from({ length: 7 }, (_, index) => shiftScheduleDate(monday, index));
}

export function groupScheduleApplicants<T extends { worker: string; date: string }>(entries: readonly T[]) {
  const groups = new Map<string, T[]>();
  for (const entry of entries) groups.set(entry.worker, [...(groups.get(entry.worker) ?? []), entry]);
  return [...groups].map(([name, items]) => ({ name, entries: [...items].sort((a, b) => a.date.localeCompare(b.date)) }));
}
export function shiftWorkMonth(month: string, offset: number) {
  const [year, number] = month.split("-").map(Number);
  return new Date(Date.UTC(year, number - 1 + offset, 1)).toISOString().slice(0, 7);
}

export function workCalendarCells(month: string): (string | null)[] {
  const [year, number] = month.split("-").map(Number);
  const firstWeekday = new Date(Date.UTC(year, number - 1, 1)).getUTCDay();
  const days = new Date(Date.UTC(year, number, 0)).getUTCDate();
  return Array.from({ length: Math.ceil((firstWeekday + days) / 7) * 7 }, (_, index) => {
    const day = index - firstWeekday + 1;
    return day > 0 && day <= days ? `${month}-${String(day).padStart(2, "0")}` : null;
  });
}

export function calendarJobHeat(count: number, maximum: number) {
  if (count <= 0) return { backgroundColor: "#f8faf6", color: "#86958b" };
  const intensity = Math.log1p(count) / Math.log1p(Math.max(count, maximum, 1));
  const light = [238, 247, 233];
  const dark = [36, 104, 71];
  const rgb = light.map((channel, index) => Math.round(channel + (dark[index] - channel) * intensity));
  const linear = rgb.map((channel) => {
    const value = channel / 255;
    return value <= .04045 ? value / 12.92 : ((value + .055) / 1.055) ** 2.4;
  });
  const luminance = linear[0] * .2126 + linear[1] * .7152 + linear[2] * .0722;
  return { backgroundColor: `rgb(${rgb.join(", ")})`, color: luminance > .179 ? "#000000" : "#ffffff" };
}

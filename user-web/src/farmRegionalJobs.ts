import type { FarmJob } from "./FarmDiscovery";
import { getGyeonggiRegion, gyeonggiRegions } from "./gyeonggiRegions";
import type { GyeonggiRegion } from "./gyeonggiRegions";

const fieldCrops = ["배추", "무", "대파", "시금치", "당근"] as const;
const greenhouseCrops = ["딸기", "토마토", "상추", "오이", "쪽파"] as const;

// Demo targets based on the 2026 brokerage-center list and 2025 rice acreage.
// https://www.mafra.go.kr/bbs/home/792/597082/download.do
// https://sri.kostat.go.kr/boardDownload.es?bid=229&list_no=438232&seq=3
const regionalTargets: Partial<Record<GyeonggiRegion, number>> = {
  화성시: 9,
  평택시: 8,
  이천시: 6,
  안성시: 6,
  여주시: 6,
  파주시: 6,
  김포시: 5,
  양평군: 4,
  연천군: 4,
  포천시: 4,
  용인시: 3,
};

const baseline = 2;
const addedDatePairs = [
  ["2026-11-29", "2026-12-02"],
  ["2026-11-30", "2026-12-03"],
  ["2026-12-01", "2026-12-04"],
  ["2026-12-02", "2026-12-05"],
] as const;

export function normalizeRegionalDemoJob(job: FarmJob): FarmJob {
  if (job.id < 0 && job.demoPosting && job.title === `${job.crop} 수확·선별 함께하실 분` &&
    ["노지 수확", "시설 수확"].includes(job.type)) {
    return { ...job, cropGroup: job.cropGroup ?? "채소", type: "수확 및 선별" };
  }
  return job;
}

export function addRegionalDemoJobs(existingJobs: FarmJob[], includeBaseJobs = true): FarmJob[] {
  const existingCounts = new Map<string, number>();
  const existingIds = new Set(existingJobs.map(({ id }) => id));
  for (const job of existingJobs) {
    const region = getGyeonggiRegion(job.place);
    if (region && job.dates.length) existingCounts.set(region, (existingCounts.get(region) ?? 0) + 1);
  }

  const demoJobs = gyeonggiRegions.flatMap((region, index) => {
    const count = existingCounts.get(region) ?? 0;
    const baseSlots = includeBaseJobs ? Math.max(0, baseline - count) : 0;
    const extraSlots = Math.max(0, (regionalTargets[region] ?? baseline) - Math.max(baseline, count));
    const slots = [
      ...Array.from({ length: baseSlots }, (_, slot) => ({ slot, id: -(index * 2 + slot + 1) })),
      ...Array.from({ length: extraSlots }, (_, slot) => ({ slot: slot + baseline, id: -(1000 + index * 10 + slot) })),
    ];
    return slots.filter(({ id }) => !existingIds.has(id)).map(({ slot, id }): FarmJob => {
      const greenhouse = (index + slot) % 2 === 0;
      const crops = greenhouse ? greenhouseCrops : fieldCrops;
      const crop = crops[(index + slot * 2) % crops.length];
      return {
        id,
        title: `${crop} 수확·선별 함께하실 분`,
        crop,
        cropGroup: "채소",
        type: "수확 및 선별",
        place: `경기 ${region}`,
        wage: (11 + (index + slot) % 3) * 10000,
        count: 2 + (index + slot) % 3,
        dates: [...addedDatePairs[slot % addedDatePairs.length]],
        start: greenhouse ? "09:00" : "08:30",
        end: greenhouse ? "17:00" : "16:30",
        rest: "12:00–13:00",
        description: greenhouse
          ? `시설하우스에서 ${crop} 수확과 선별, 상자 포장을 합니다.`
          : `${crop} 수확 후 흙을 털고 출하용 상자에 정리합니다. 장갑과 작업화를 준비해 주세요.`,
        experience: greenhouse ? "신입·초보 가능" : "경력 무관",
        demoPosting: true,
      };
    });
  });

  return [...existingJobs, ...demoJobs];
}

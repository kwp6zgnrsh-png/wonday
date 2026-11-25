import type { FarmJob } from "./FarmDiscovery";
import { getGyeonggiRegion } from "./gyeonggiRegions";
import { getCropOptions, jobCropGroup, normalizeCrop, normalizeCropGroup } from "./farmCropCatalog";

export const experienceOptions = ["경력 무관", "신입·초보 가능", "경력 필요"] as const;
export const workTypes = [
  "건조",
  "계사관리",
  "골목관리",
  "객토 및 퇴비",
  "급료 및 생육관리",
  "꿀벌, 말벌응애 방제",
  "논갈이",
  "농업용드론관리",
  "농업용드론운용",
  "달걀선별 및 부화지원",
  "답압유기물 덮기",
  "돈사관리",
  "돼지선별",
  "모내기",
  "묘목정식",
  "묘사관리",
  "무밀기먹이공급",
  "배수관리",
  "볏짚썰기",
  "병충해방제",
  "병해충방제",
  "보행관리기관리",
  "보행관리기운용",
  "봉군축소, 물 및 화분공급",
  "봉지씌우기",
  "분만 및 포유지원",
  "비료시비",
  "사육관리",
  "산란유도",
  "상토준비 및 멀칭",
  "새끼칠거름",
  "소선별 및 번식지원",
  "솎음작업",
  "수확 및 선별",
  "순자르기 및 지주세우기",
  "시설하우스설치지원",
  "압사관리",
  "야외퇴적",
  "양사관리",
  "여왕벌 양성",
  "염소선별 및 번식지원",
  "오리알선별 및 부화지원",
  "우사관리",
  "원목구매 및 준비",
  "원목벌채 및 건조",
  "월동포장",
  "유인",
  "이식",
  "이양기관리",
  "이양기운용",
  "인공수분",
  "입모",
  "잡초제거",
  "저장관리 지원",
  "적과 및 적뢰",
  "적미병 방제",
  "절화 및 선별",
  "점등관리",
  "정지 및 전정",
  "젖소선별 및 번식지원",
  "조제",
  "종균준비 및 접종",
  "종자 자율교환",
  "종자소독",
  "종자처리",
  "질병관리지원",
  "착유지원",
  "채밀(자운영, 유채, 벚꽃)",
  "출하지원",
  "탈곡 및 정선",
  "토끼선별 및 번식지원",
  "토입답압",
  "퇴비 및 발효",
  "퇴비살포기관리",
  "퇴비살포기운용",
  "트렉터운용",
  "트렉터정비관리",
  "파종 또는 정식",
  "포장 및 판매",
  "품종선택 및 종균접종",
  "파종",
  "정식",
  "수분",
  "전정",
  "순지르기",
  "수확",
  "적과",
  "알솎기",
  "방제 관리",
  "제초 작업",
  "포장 관리",
  "기타",
] as const;

export type FarmFilters = {
  region: string;
  group: string;
  crop: string;
  experience: string;
  type: string;
  query: string;
};

export type FarmSearchField = Exclude<keyof FarmFilters, "query">;
export type FarmSearchFilters = Record<FarmSearchField, string[]> & { query: string; dates: string[]; startTime: string; endTime: string };
export const farmSearchFields: FarmSearchField[] = ["region", "group", "crop", "experience", "type"];
export const emptyFarmSearchFilters: FarmSearchFilters = { region: [], group: [], crop: [], experience: [], type: [], query: "", dates: [], startTime: "", endTime: "" };

export const emptyFarmFilters: FarmFilters = { region: "", group: "", crop: "", experience: "", type: "", query: "" };
export const browseModes = ["일자리 상세검색", "맞춤 일자리", "지역별 일자리"] as const;
export type FarmBrowseMode = (typeof browseModes)[number];
export const profileJobTabs = ["지원한 일자리", "제안받은 일자리", "찜한 일자리", "맞춤 일자리"] as const;
export type FarmProfilePage = (typeof profileJobTabs)[number] | "맞춤 설정" | "이력서·경력";
export type FarmResume = { summary: string; experience: string; vehicleOwnership?: "예" | "아니오" };

function clean(value: string = "") {
  return value.normalize("NFKC").trim().replace(/\s+/g, " ");
}

function normalizeExperience(value?: string) {
  const text = clean(value).replace(/\s+/g, "");
  if (/무관|제한없|불필요|필요없/.test(text)) return "경력 무관";
  if (/신입|초보|비경험|미경험/.test(text)) return /불가|제외/.test(text) ? "경력 필요" : "신입·초보 가능";
  if (/필요|필수|이상|요구|^경력$/.test(text)) return "경력 필요";
  return clean(value);
}

const workTypeAliases: Readonly<Record<string, string>> = {
  노지수확: "수확", 시설수확: "수확", 하우스수확: "수확",
  노지정식: "정식", 시설정식: "정식", 하우스정식: "정식",
  노지파종: "파종", 시설파종: "파종",
  "수확·선별": "수확 및 선별", "수확/선별": "수확 및 선별",
  병충해방제: "병해충방제", 제초작업: "잡초제거",
  트랙터운용: "트렉터운용", 트랙터정비관리: "트렉터정비관리",
};
const workTypeLabels = new Map(workTypes.map((type) => [type.replace(/\s+/g, ""), type]));

export function normalizeWorkType(value: string) {
  const text = clean(value);
  const key = text.replace(/\s+/g, "");
  return workTypeAliases[key] ?? workTypeLabels.get(key) ?? text;
}

function workTypeTerms(type: string) {
  const canonical = normalizeWorkType(type);
  const terms = [canonical];
  // Combined operations also belong to their individual/broader search categories.
  if (canonical === "수확 및 선별") terms.push("수확");
  if (canonical === "파종" || canonical === "정식") terms.push("파종 또는 정식");
  if (canonical === "병해충방제") terms.push("방제 관리");
  if (canonical === "전정") terms.push("정지 및 전정");
  return terms;
}

export function getFarmJobSearchFields(job: FarmJob) {
  return {
    region: getGyeonggiRegion(job.place) ?? "",
    group: jobCropGroup(job),
    crop: normalizeCrop(job.crop),
    experience: normalizeExperience(job.experience),
    type: normalizeWorkType(job.type),
  };
}

export function normalizeFarmFilters(filters: FarmFilters): FarmFilters {
  const all = (value: string) => clean(value) === "전체" ? "" : clean(value);
  const region = all(filters.region);
  return {
    region: region === "경기도 전체" ? "" : getGyeonggiRegion(region) ?? region,
    group: normalizeCropGroup(all(filters.group)),
    crop: normalizeCrop(all(filters.crop)),
    experience: normalizeExperience(all(filters.experience)),
    type: normalizeWorkType(all(filters.type)),
    query: clean(filters.query),
  };
}

export function normalizeFarmSearchFilters(filters: FarmFilters | FarmSearchFilters): FarmSearchFilters {
  const values = (key: FarmSearchField) => {
    const source = filters[key];
    const items = Array.isArray(source) ? source : [source];
    return [...new Set(items.map((value) => normalizeFarmFilters({ ...emptyFarmFilters, [key]: value })[key]).filter(Boolean))];
  };
  return {
    region: values("region"), group: values("group"), crop: values("crop"), experience: values("experience"), type: values("type"), query: clean(filters.query),
    dates: "dates" in filters ? [...new Set(filters.dates.map(clean))].filter(Boolean).sort() : [],
    startTime: "startTime" in filters ? clean(filters.startTime) : "",
    endTime: "endTime" in filters ? clean(filters.endTime) : "",
  };
}

function timeMinutes(value: string) {
  if (!/^(?:[01]\d|2[0-3]):[0-5]\d$/.test(value)) return undefined;
  const [hour, minute] = value.split(":").map(Number);
  return hour * 60 + minute;
}

export function farmScheduleError(filters: FarmSearchFilters) {
  if (filters.dates.some((date) => !/^\d{4}-\d{2}-\d{2}$/.test(date) ||
    !Number.isFinite(Date.parse(date)) || new Date(date).toISOString().slice(0, 10) !== date))
    return "올바른 근무일을 선택해 주세요.";
  const start = filters.startTime ? timeMinutes(filters.startTime) : undefined;
  const end = filters.endTime ? timeMinutes(filters.endTime) : undefined;
  if ((filters.startTime && start === undefined) || (filters.endTime && end === undefined))
    return "시간을 올바르게 입력해 주세요.";
  if (start !== undefined && end !== undefined && start >= end)
    return "종료 시각은 시작 시각보다 늦게 설정해 주세요.";
  return "";
}

export function updateFarmSearchSelection(filters: FarmSearchFilters, field: FarmSearchField, values: string[], jobs: FarmJob[] = []): FarmSearchFilters {
  const next = normalizeFarmSearchFilters({ ...filters, [field]: values });
  if (field === "group" && next.group.length) {
    const available = new Set(next.group.flatMap((group) => getCropOptions(group, jobs)));
    next.crop = next.crop.filter((crop) => available.has(crop));
  }
  return next;
}

export function searchFarmJobs(jobs: FarmJob[], filters: FarmFilters | FarmSearchFilters, availableDates: (job: FarmJob) => string[] = (job) => job.dates) {
  const selected = normalizeFarmSearchFilters(filters);
  if (farmScheduleError(selected)) return [];
  const start = selected.startTime ? timeMinutes(selected.startTime) : undefined;
  const end = selected.endTime ? timeMinutes(selected.endTime) : undefined;
  const tokens = selected.query.toLocaleLowerCase("ko-KR").split(/\s+/).filter(Boolean);
  return jobs.filter((job) => {
    const fields = getFarmJobSearchFields(job);
    const searchable = clean([job.title, job.place, job.type, ...Object.values(fields)].join(" "))
      .toLocaleLowerCase("ko-KR").replace(/\s+/g, "");
    return (!selected.region.length || selected.region.includes(fields.region)) &&
      (!selected.group.length || selected.group.includes(fields.group)) &&
      (!selected.crop.length || selected.crop.includes(fields.crop)) &&
      (!selected.experience.length || selected.experience.includes(fields.experience)) &&
      (!selected.type.length || selected.type.some((type) => workTypeTerms(job.type).includes(type))) &&
      (!selected.dates.length || availableDates(job).some((date) => selected.dates.includes(date))) &&
      (start === undefined || (timeMinutes(job.start) ?? -1) >= start) &&
      (end === undefined || (timeMinutes(job.end) ?? Infinity) <= end) &&
      tokens.every((token) => searchable.includes(token));
  });
}

export function hasFarmFilters(filters: FarmFilters | FarmSearchFilters) {
  return Object.values(normalizeFarmSearchFilters(filters)).some((value) => value.length > 0);
}

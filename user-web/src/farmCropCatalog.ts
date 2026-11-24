const foodCrops = [
  "감자", "강낭콩", "고구마", "녹두", "들깨", "땅콩", "맥주보리", "메밀", "밀", "수수",
  "옥수수", "완두", "유채", "일반보리", "조", "참깨", "콩", "팥", "쌀", "기타",
] as const;

export const farmCropCatalog: Readonly<Record<string, readonly string[]>> = {
  식량작물: foodCrops,
  "기타(모니터링)": ["기타 과일과채류", "기타 엽경채류", "기타 특용작물류", "기타"],
  채소: foodCrops,
  과수: [
    "감귤", "단감", "매실", "무화과", "호두", "올리브", "체리", "블루베리", "대추", "오디",
    "석류", "밤", "아로니아", "앵두", "은행", "배", "복숭아", "사과", "살구", "양앵두",
    "유자", "자두", "참다래", "포도", "한라봉", "기타",
  ],
  화훼: [
    "거베라", "국화", "글라디올러스", "금어초", "꽃도라지", "꽃해바라기", "덴드로비움",
    "산호수", "선인장", "수국", "스타티스", "시클라멘", "심비디움", "아이리스", "안개초",
    "오리엔탈나리", "장미", "철쭉", "카네이션", "칼라", "칼랑코에", "튤립", "팔레놉시스",
    "포인세티아", "프리지아", "기타",
  ],
  특용작물: [
    "느타리버섯", "양송이버섯", "영지버섯", "팽이버섯", "강활", "결명자", "구기자", "도라지",
    "더덕", "독활", "두충", "맥문동", "백하수오", "산수유", "마", "시호", "오미자", "율무",
    "작약", "지황", "차", "참당귀", "천궁", "천마", "택사", "향부자", "호프", "황금", "황기",
    "표고버섯", "인삼", "강황", "기타",
  ],
  축산: ["꿀벌", "돼지", "한우", "염소", "닭", "오리", "젖소", "토끼", "곤충", "기타"],
  농기계작업: ["트렉터", "보행관리기", "농업용드론", "이양기", "퇴비살포기", "기타"],
};

export const cropGroups = Object.keys(farmCropCatalog);

const previousCrops: Readonly<Record<string, readonly string[]>> = {
  식량작물: ["벼", "보리"],
  채소: ["배추", "상추", "고추", "무", "양파", "당근", "오이", "토마토", "마늘", "호박", "딸기", "대파", "쪽파", "시금치"],
  과수: ["감"],
  화훼: ["난"],
};

function catalogItems(group: string) {
  return Object.hasOwn(farmCropCatalog, group) ? farmCropCatalog[group] : [];
}

export function normalizeCrop(crop: string) {
  const value = crop.normalize("NFKC").trim().replace(/\s+/g, " ");
  return value === "벼" ? "쌀" : value === "트랙터" ? "트렉터" : value;
}

export function normalizeCropGroup(group: string) {
  const value = group.normalize("NFKC").trim();
  return value === "기타" ? "기타(모니터링)" : value;
}

export function getCropGroup(crop: string) {
  const name = normalizeCrop(crop);
  return cropGroups.find((group) => [...catalogItems(group), ...(previousCrops[group] ?? [])].some((item) => normalizeCrop(item) === name)) ?? "기타(모니터링)";
}

export function jobCropGroup(job: { crop: string; cropGroup?: string }) {
  const explicit = normalizeCropGroup(job.cropGroup ?? "");
  return explicit && cropGroups.includes(explicit) ? explicit : getCropGroup(job.crop);
}

export function getCropOptions(group: string, jobs: ReadonlyArray<{ crop: string; cropGroup?: string }> = []) {
  const selectedGroup = normalizeCropGroup(group);
  const catalog = selectedGroup ? catalogItems(selectedGroup) : Object.values(farmCropCatalog).flat();
  const previous = selectedGroup ? previousCrops[selectedGroup] ?? [] : Object.values(previousCrops).flat();
  const existing = jobs.filter((job) => !selectedGroup || jobCropGroup(job) === selectedGroup)
    .map((job) => job.crop.trim()).filter(Boolean);
  return Array.from(new Set([...catalog, ...previous, ...existing].map(normalizeCrop)));
}

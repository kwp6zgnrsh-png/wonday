import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";

const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { searchFarmJobs, emptyFarmFilters, normalizeFarmFilters, normalizeFarmSearchFilters, updateFarmSearchSelection, farmScheduleError } = await vite.ssrLoadModule("/src/farmSearch.ts");
const { getCropOptions, jobCropGroup } = await vite.ssrLoadModule("/src/farmCropCatalog.ts");
const { addRegionalDemoJobs, normalizeRegionalDemoJob } = await vite.ssrLoadModule("/src/farmRegionalJobs.ts");

const common = {
  wage: 120000, count: 2, dates: ["2026-11-30"], start: "08:00", end: "17:00", rest: "12:00–13:00", description: "농작업",
};
const jobs = [
  { ...common, id: 1, title: "사과 수확", place: "경기 이천시 장호원읍", crop: "사과", experience: "경력무관", type: "노지 수확" },
  { ...common, id: 2, title: "딸기 작업", place: "경기도평택시진위면", crop: " 딸기 ", experience: "신입(비경험자)", type: "시설 수확" },
  { ...common, id: 3, title: "배추 심기", place: "이천시 부발읍", crop: "배추", experience: "경력 1년 이상", type: "노지 정식" },
  { ...common, id: 4, title: "채소 감자", place: "경기도 평택시", cropGroup: "채소", crop: "감자", type: "정식" },
  { ...common, id: 5, title: "식량 감자", place: "대한민국 경기도 평택시", cropGroup: "식량작물", crop: "감자", experience: "경력 필요 없음", type: "수확 및 선별" },
  { ...common, id: 6, title: "벼 수확", place: "경기 화성시", crop: "벼", experience: "경력", type: "수확" },
];
const ids = (filters) => searchFarmJobs(jobs, { ...emptyFarmFilters, ...filters }).map(({ id }) => id);

test("근무지역은 도 표기와 띄어쓰기, 상세 주소에 관계없이 시·군에 연결된다", () => {
  assert.deepEqual(ids({ region: "이천시" }), [1, 3]);
  assert.deepEqual(ids({ region: "경기도 평택시" }), [2, 4, 5]);
  assert.deepEqual(ids({ region: "화성시" }), [6]);
});

test("작물 분류와 품목은 정확히 일치하고 명시된 분류를 우선한다", () => {
  assert.deepEqual(ids({ group: "채소" }), [2, 3, 4]);
  assert.deepEqual(ids({ crop: "감자" }), [4, 5]);
  assert.deepEqual(ids({ group: "식량작물", crop: "감자" }), [5]);
  assert.deepEqual(ids({ group: "과수", crop: "배추" }), []);
  assert.deepEqual(ids({ crop: "배" }), []);
});

test("경력 표기를 통일하되 조건 미기재와 경력 불필요를 구분한다", () => {
  assert.deepEqual(ids({ experience: "경력 무관" }), [1, 5]);
  assert.deepEqual(ids({ experience: "신입·초보 가능" }), [2]);
  assert.deepEqual(ids({ experience: "경력 필요" }), [3, 6]);
});

test("수확과 정식은 예전 노지·시설 표기에도 연결된다", () => {
  assert.deepEqual(ids({ type: "수확" }), [1, 2, 5, 6]);
  assert.deepEqual(ids({ type: "정식" }), [3, 4]);
  assert.deepEqual(ids({ type: "파종 또는 정식" }), [3, 4]);
  assert.deepEqual(ids({ type: "수확 및 선별" }), [5]);
  assert.deepEqual(ids({ type: "건조" }), []);
});

test("선택한 다섯 조건은 동시에 만족해야 한다", () => {
  assert.deepEqual(ids({ region: "평택시", group: "채소", crop: "딸기", experience: "신입·초보 가능", type: "수확" }), [2]);
  assert.deepEqual(ids({ region: "평택시", group: "채소", crop: "딸기", experience: "경력 필요", type: "수확" }), []);
});

test("전체와 초기화는 필터를 해제하고 검색어도 공고의 분류·경력에 연결된다", () => {
  assert.deepEqual(ids({ region: "전체", group: "전체", crop: "전체", experience: "전체", type: "전체" }), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(ids(emptyFarmFilters), [1, 2, 3, 4, 5, 6]);
  assert.deepEqual(ids({ query: "평택 딸기" }), [2]);
  assert.deepEqual(ids({ query: "과수 경력무관" }), [1]);
});

test("예전 벼·기타 표기는 현재 품목·분류와 연결된다", () => {
  assert.deepEqual(ids({ group: "식량작물", crop: "쌀" }), [6]);
  assert.equal(jobCropGroup({ cropGroup: "기타", crop: "기타" }), "기타(모니터링)");
  assert.equal(normalizeFarmFilters({ ...emptyFarmFilters, group: "기타" }).group, "기타(모니터링)");
});

test("새 공고 등록과 검색 모두 기존 채소 품목을 선택할 수 있다", () => {
  for (const crop of ["배추", "딸기", "토마토", "대파", "시금치"]) {
    assert.ok(getCropOptions("채소").includes(crop), crop);
  }
  assert.ok(!getCropOptions("과수").includes("배추"));
});

test("경기도 예시 공고는 실제 작업형태와 분류로 검색된다", () => {
  const demoJobs = addRegionalDemoJobs([]);
  const found = searchFarmJobs(demoJobs, { ...emptyFarmFilters, region: "평택시", group: "채소", type: "수확 및 선별" });
  assert.equal(found.length, 8);
  assert.ok(found.every(({ cropGroup, type }) => cropGroup === "채소" && type === "수확 및 선별"));
  const legacy = { ...demoJobs[0], type: "시설 수확", cropGroup: undefined };
  assert.equal(normalizeRegionalDemoJob(legacy).type, "수확 및 선별");
  const ownPosting = { ...legacy, id: 100, demoPosting: undefined };
  assert.equal(normalizeRegionalDemoJob(ownPosting), ownPosting);
});

test("한 항목에서 여러 지역·경력·작업형태를 고르면 선택값 중 하나에 해당하는 공고를 보여준다", () => {
  assert.deepEqual(ids({ region: ["이천시", "평택시"] }), [1, 2, 3, 4, 5]);
  assert.deepEqual(ids({ experience: ["신입·초보 가능", "경력 필요"] }), [2, 3, 6]);
  assert.deepEqual(ids({ type: ["수확", "정식"] }), [1, 2, 3, 4, 5, 6]);
});

test("분류·품목도 복수 선택하며 서로 다른 항목은 모두 만족해야 한다", () => {
  assert.deepEqual(ids({
    region: ["이천시", "평택시"], group: ["과수", "채소"], crop: ["사과", "딸기"],
    experience: ["경력 무관", "신입·초보 가능"], type: ["수확", "정식"],
  }), [1, 2]);
  assert.deepEqual(ids({ region: ["화성시"], crop: ["사과", "딸기"] }), []);
});

test("분류 선택을 줄이면 남은 분류에 없는 품목만 해제한다", () => {
  const selected = normalizeFarmSearchFilters({ ...emptyFarmFilters, group: ["과수", "채소"], crop: ["사과", "딸기"] });
  const next = updateFarmSearchSelection(selected, "group", ["채소"], jobs);
  assert.deepEqual(next.group, ["채소"]);
  assert.deepEqual(next.crop, ["딸기"]);
  assert.deepEqual(selected.crop, ["사과", "딸기"]);
  assert.deepEqual(updateFarmSearchSelection(next, "group", [], jobs).crop, ["딸기"]);
});

test("복수 선택의 같은 뜻 표기는 한 번만 적용한다", () => {
  const selected = normalizeFarmSearchFilters({ ...emptyFarmFilters,
    region: ["경기도 평택시", "평택시"], crop: ["벼", "쌀"], type: ["노지 수확", "시설 수확", "수확"],
  });
  assert.deepEqual(selected.region, ["평택시"]);
  assert.deepEqual(selected.crop, ["쌀"]);
  assert.deepEqual(selected.type, ["수확"]);
  assert.deepEqual(ids({ region: [], group: [], crop: [], experience: [], type: [] }), [1, 2, 3, 4, 5, 6]);
});

const scheduledJobs = [
  { ...jobs[0], id: 21, dates: ["2026-11-28", "2026-11-30"], start: "08:00", end: "17:00" },
  { ...jobs[1], id: 22, dates: ["2026-11-28"], start: "09:00", end: "16:00" },
  { ...jobs[2], id: 23, dates: ["2026-11-29"], start: "07:00", end: "18:00" },
  { ...jobs[3], id: 24, dates: ["2026-11-28"], start: "10:00", end: "14:00" },
];
const scheduleIds = (filters, availableDates) => searchFarmJobs(scheduledJobs, normalizeFarmSearchFilters({ ...emptyFarmFilters, ...filters }), availableDates).map(({ id }) => id);

test("복수 근무일은 선택한 날짜 중 하나라도 모집하는 공고와 연결된다", () => {
  assert.deepEqual(scheduleIds({ dates: ["2026-11-29", "2026-11-30"] }), [21, 23]);
  assert.deepEqual(scheduleIds({ dates: ["2026-12-01"] }), []);
  assert.deepEqual(scheduleIds({ dates: [] }), [21, 22, 23, 24]);
});

test("가능 시간 안에 근무 전체가 들어오는 공고만 검색하며 경계 시각을 포함한다", () => {
  assert.deepEqual(scheduleIds({ startTime: "09:00", endTime: "17:00" }), [22, 24]);
  assert.deepEqual(scheduleIds({ startTime: "08:00", endTime: "17:00" }), [21, 22, 24]);
});

test("시작 또는 종료 시각만 지정할 수도 있다", () => {
  assert.deepEqual(scheduleIds({ startTime: "09:00" }), [22, 24]);
  assert.deepEqual(scheduleIds({ endTime: "16:00" }), [22, 24]);
});

test("날짜·시간과 기존 지역·품목 조건을 함께 적용한다", () => {
  assert.deepEqual(scheduleIds({ dates: ["2026-11-28"], startTime: "09:00", endTime: "17:00", region: ["평택시"], crop: ["딸기"] }), [22]);
});

test("다른 날 모집 중이어도 선택일이 마감·정원 충족이면 검색에서 제외한다", () => {
  const availableDates = (job) => job.id === 21 ? ["2026-11-30"] : job.dates;
  assert.deepEqual(scheduleIds({ dates: ["2026-11-28"] }, availableDates), [22, 24]);
  assert.deepEqual(scheduleIds({ dates: ["2026-11-30"] }, () => []), []);
});

test("잘못된 날짜·시간이나 역전된 시간 범위는 전체 검색으로 바뀌지 않는다", () => {
  for (const filters of [{ dates: ["2026-02-30"] }, { startTime: "24:00" }, { startTime: "17:00", endTime: "08:00" }, { startTime: "08:00", endTime: "08:00" }]) {
    const selected = normalizeFarmSearchFilters({ ...emptyFarmFilters, ...filters });
    assert.ok(farmScheduleError(selected));
    assert.deepEqual(searchFarmJobs(scheduledJobs, selected), []);
  }
});

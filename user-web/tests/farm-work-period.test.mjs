import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";

const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { workDateRange, workDateRangeError, filterWorkDates } = await vite.ssrLoadModule("/src/farmWorkPeriod.ts");
const custom = { from: "2026-11-01", to: "2026-12-15" };

test("1일은 오늘 하루이고 7일·30일은 오늘을 포함한다", () => {
  assert.deepEqual(workDateRange("1일", custom, "2026-11-24", "upcoming"), { from: "2026-11-24", to: "2026-11-24" });
  assert.deepEqual(workDateRange("7일", custom, "2026-11-24", "upcoming"), { from: "2026-11-24", to: "2026-11-30" });
  assert.deepEqual(workDateRange("30일", custom, "2026-11-24", "upcoming"), { from: "2026-11-24", to: "2026-12-23" });
});

test("지난 근무 기준도 오늘을 포함한 정확한 기간을 계산한다", () => {
  assert.deepEqual(workDateRange("7일", custom, "2026-11-24", "past"), { from: "2026-11-18", to: "2026-11-24" });
  assert.deepEqual(workDateRange("30일", custom, "2026-11-24", "past"), { from: "2026-10-26", to: "2026-11-24" });
});

test("월말·연말·윤년 경계를 정확히 처리한다", () => {
  assert.deepEqual(workDateRange("7일", custom, "2026-12-30", "upcoming"), { from: "2026-12-30", to: "2027-01-05" });
  assert.deepEqual(workDateRange("7일", custom, "2028-02-28", "upcoming"), { from: "2028-02-28", to: "2028-03-05" });
});

test("직접 설정은 지정한 과거·미래 날짜를 유지한다", () => {
  assert.deepEqual(workDateRange("직접 설정", custom, "2026-11-24", "upcoming"), custom);
});

test("기간의 양 끝 날짜를 포함하고 상태 필터를 함께 적용한다", () => {
  const works = [
    { id: 1, date: "2026-11-23", status: "지원 중" },
    { id: 2, date: "2026-11-24", status: "지원 중" },
    { id: 3, date: "2026-11-27", status: "근무 취소" },
    { id: 4, date: "2026-11-30", status: "지원 중" },
    { id: 5, date: "2026-12-01", status: "지원 중" },
  ];
  const range = workDateRange("7일", custom, "2026-11-24", "upcoming");
  assert.deepEqual(filterWorkDates(works, range, "전체").map(({ id }) => id), [2, 3, 4]);
  assert.deepEqual(filterWorkDates(works, range, "지원 중").map(({ id }) => id), [2, 4]);
  assert.deepEqual(filterWorkDates(works, range, "근무 취소").map(({ id }) => id), [3]);
  assert.equal(works.length, 5);
});

test("날짜 누락·역전·존재하지 않는 날짜는 전체 조회로 바뀌지 않는다", () => {
  for (const range of [{ from: "", to: "2026-11-24" }, { from: "2026-11-30", to: "2026-11-24" }, { from: "2026-02-30", to: "2026-03-10" }]) {
    assert.ok(workDateRangeError(range));
    assert.deepEqual(filterWorkDates([{ date: "2026-11-24", status: "지원 중" }], range, "전체"), []);
  }
  assert.equal(workDateRangeError({ from: "2026-11-24", to: "2026-11-24" }), "");
});

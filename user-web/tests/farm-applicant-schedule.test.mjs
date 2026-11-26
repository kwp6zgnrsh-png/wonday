import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";
const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { scheduleWeek, shiftScheduleDate, groupScheduleApplicants } = await vite.ssrLoadModule("/src/farmApplicantSchedule.ts");
test("weeks run Monday through Sunday across year and leap-day boundaries", () => {
  assert.deepEqual(scheduleWeek("2027-01-01"), ["2026-12-28", "2026-12-29", "2026-12-30", "2026-12-31", "2027-01-01", "2027-01-02", "2027-01-03"]);
  assert.equal(scheduleWeek("2026-11-29")[0], "2026-11-23");
  assert.equal(shiftScheduleDate("2028-02-28", 1), "2028-02-29");
  assert.equal(shiftScheduleDate("2028-03-01", -1), "2028-02-29");
});
test("applicants keep all their dates sorted without mutating source entries", () => {
  const entries = [{ worker: "김하루", date: "2026-11-28" }, { worker: "박민수", date: "2026-11-23" }, { worker: "김하루", date: "2026-11-24" }];
  const groups = groupScheduleApplicants(entries);
  assert.deepEqual(groups[0].entries.map(x => x.date), ["2026-11-24", "2026-11-28"]);
  assert.equal(groups[1].name, "박민수");
  assert.equal(entries[0].date, "2026-11-28");
  assert.deepEqual(groupScheduleApplicants([]), []);
});
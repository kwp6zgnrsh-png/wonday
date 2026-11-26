import assert from "node:assert/strict";
import { after, test } from "node:test";
import { createServer } from "vite";
const vite = await createServer({ server: { middlewareMode: true, ws: false } });
after(() => vite.close());
const { employerJobs, pendingEmployerApplications, groupEmployerFarms } = await vite.ssrLoadModule("/src/farmEmployerJobs.ts");
const jobs = [
  { id: 1, employerId: "farm-a" }, { id: 2, employerId: "farm-b" },
  { id: 3, demoPosting: true }, { id: 4 },
  { id: 5, employerId: "farm-b", demoPosting: true },
];
const works = [
  { id: "a", job: jobs[0], status: "지원 중" },
  { id: "b", job: jobs[1], status: "지원 중" },
  { id: "c", job: jobs[0], status: "근무 확정" },
  { id: "d", job: jobs[2], status: "지원 중" },
  { id: "e", job: { id: 999, employerId: "farm-a" }, status: "지원 중" },
];
test("employers only see pending applications for their own current postings", () => {
  assert.deepEqual(pendingEmployerApplications(jobs, works, "farm-a").map(x => x.id), ["a"]);
  assert.deepEqual(pendingEmployerApplications(jobs, works, "farm-b").map(x => x.id), ["b"]);
  assert.deepEqual(pendingEmployerApplications(jobs, works, "new-farm"), []);
});
test("unowned demo postings are limited to the demo employer", () => {
  assert.deepEqual(employerJobs(jobs, "farmer.demo").map(x => x.id), [3]);
  assert.deepEqual(pendingEmployerApplications(jobs, works, "farmer.demo").map(x => x.id), ["d"]);
  assert.deepEqual(employerJobs(jobs, "farm-b").map(x => x.id), [2, 5]);
});
test("farm grouping combines the same normalized address while keeping different workplaces separate", () => {
  const farms = groupEmployerFarms([
    { id: 1, place: "경기도 이천시  장호원읍" },
    { id: 2, place: " 경기 이천시 장호원읍 " },
    { id: 3, place: "경기 이천시 다른농장" },
  ]);
  assert.equal(farms.length, 2);
  assert.deepEqual(farms[0].jobs.map(x => x.id), [1, 2]);
  assert.deepEqual(farms[1].jobs.map(x => x.id), [3]);
  assert.deepEqual(groupEmployerFarms([]), []);
});
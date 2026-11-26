import type { FarmJob } from "./FarmDiscovery";
import type { FarmWork } from "./farmWorkActions";

export function employerJobs(jobs: readonly FarmJob[], employerId: string) {
  return jobs.filter((job) => job.employerId
    ? job.employerId === employerId
    : employerId === "farmer.demo" && job.demoPosting === true);
}

export function pendingEmployerApplications(jobs: readonly FarmJob[], works: readonly FarmWork[], employerId: string) {
  const jobIds = new Set(employerJobs(jobs, employerId).map((job) => job.id));
  return works.filter((entry) => entry.status === "지원 중" && jobIds.has(entry.job.id));
}
export function groupEmployerFarms(jobs: readonly FarmJob[]) {
  const groups = new Map<string, { id: string; address: string; jobs: FarmJob[] }>();
  for (const job of jobs) {
    const address = job.place.trim().replace(/\s+/g, " ").replace(/^경기도\s/, "경기 ");
    const id = address;
    const farm = groups.get(id) ?? { id, address, jobs: [] };
    farm.jobs.push(job);
    groups.set(id, farm);
  }
  return [...groups.values()];
}
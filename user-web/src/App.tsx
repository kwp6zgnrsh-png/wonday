import IphoneDock from "./IphoneDock";
import IphoneHomeWidgets from "./IphoneHomeWidgets";
import WooriBankingApp from "./WooriBankingApp";
import wonBankingIcon from "./assets/won-banking-icon.png";
import wonGlobalIcon from "./assets/won-global-icon.png";
import FarmApplicantAlerts from "./FarmApplicantAlerts";
import FarmApplicantSchedule from "./FarmApplicantSchedulePanel";
import FarmWorkManagement from "./FarmWorkManagement";
import FarmWorkdayPicker from "./FarmWorkdayPicker";
import { employerJobs, pendingEmployerApplications } from "./farmEmployerJobs";
import type { FarmSalaryAccount } from "./farmSalaryAccount";
import FarmSignup from "./FarmSignup";
import { hashFarmPassword } from "./farmRegistration";
import type { FarmMember } from "./farmRegistration";
import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import "./App.css";
import wooriSymbol from "./assets/woori-financial-symbol.ico";
import agriworkLogo from "./assets/agriwork-logo.svg";
import agriworkSymbol from "./assets/agriwork-symbol.png";
import { agriworkName } from "./agriworkBrand";
import { PlatformConsent, ConnectionScreen, IdentityFields } from "./EnrollmentFields";
import EnrollmentProgress from "./EnrollmentProgress";
import { enrollmentSteps } from "./enrollmentSteps";
import { dialogDescriptions, farmScreenDescriptions, insuranceScreenDescriptions } from "./screenGuide";
import { PolicyPaymentCard } from "./PolicyPaymentCard";
import PremiumPaymentForm from "./PremiumPaymentForm";
import ClaimGuide from "./ClaimGuide";
import type { ClaimCategory } from "./ClaimGuide";
import InsuranceIcon from "./InsuranceIcon";
import InsuranceProducts from "./InsuranceProducts";
import TodayWeather, { WorkplaceWeather } from "./TodayWeather";
import { defaultWeatherRegion, gyeonggiRegions, isGyeonggiRegion, isGyeonggiWorkplace } from "./gyeonggiRegions";
import type { GyeonggiRegion } from "./gyeonggiRegions";
import FarmIcon from "./FarmIcon";
import { FarmHome, FarmJobCard, FarmJobList, FarmJobOverview, FarmProfile } from "./FarmDiscovery";
import type { FarmJob as Job } from "./FarmDiscovery";
import FarmProfileJobs from "./FarmProfileJobs";
import FarmApplicationBanner from "./FarmApplicationBanner";
import FarmWorkCard from "./FarmWorkCard";
import FarmWorkPeriodFilter from "./FarmWorkPeriodFilter";
import FarmCursorList from "./FarmCursorList";
import FarmScrollTop from "./FarmScrollTop";
import { filterWorkDates, workDateRange, workDateRangeError } from "./farmWorkPeriod";
import type { WorkDateRange, WorkPeriod, WorkPeriodDirection } from "./farmWorkPeriod";
import { cancelWorkerWork, workerCancellationBlockedReason } from "./farmWorkActions";
import type { FarmWork } from "./farmWorkActions";
import FarmCropSelection from "./FarmCropSelection";
import { cropGroups, getCropOptions } from "./farmCropCatalog";
import FarmWorkplaceMap from "./FarmWorkplaceMap";
import { parseWorkplacePin } from "./farmMapLocation";
import { emptyFarmFilters, emptyFarmSearchFilters, experienceOptions, hasFarmFilters, normalizeFarmFilters, normalizeFarmSearchFilters, normalizeWorkType, searchFarmJobs, workTypes } from "./farmSearch";
import type { FarmBrowseMode, FarmFilters, FarmProfilePage, FarmResume, FarmSearchFilters } from "./farmSearch";
import { addRegionalDemoJobs, normalizeRegionalDemoJob } from "./farmRegionalJobs";
import "./Farm.css";
import { initialInstallment, paymentMethods, samplePolicies } from "./insurancePolicies";
import type { PaymentMethod, Policy } from "./insurancePolicies";

type Work = FarmWork;
type FarmScreenState = {
  tab: string;
  detailId: number | null;
  workId: string | null;
  selected: string[];
  mode: FarmBrowseMode;
  filters: FarmFilters;
  searchFilters: FarmSearchFilters;
  profilePage: FarmProfilePage;
  statusFilter: string;
};
type Claim = {
  id: string;
  work: Work;
  status: string;
  risk?: number;
  amount?: number;
  decision?: string;
  supplement?: string;
};
const seed: Job[] = [
  {
    id: 1,
    title: "햇살 아래, 사과 수확 함께해요",
    crop: "사과",
    cropGroup: "과수",
    type: "노지 수확",
    place: "경기 이천시 장호원읍",
    wage: 120000,
    count: 3,
    dates: ["2026-11-23", "2026-11-24", "2026-11-25"],
    start: "08:00",
    end: "17:00",
    rest: "12:00–13:00",
    experience: "경력 무관",
    description:
      "잘 익은 사과를 수확하고 선별하는 작업입니다. 장갑과 편안한 작업화를 준비해 주세요.",
  },
  {
    id: 2,
    title: "싱그러운 딸기 농장의 하루",
    crop: "딸기",
    cropGroup: "채소",
    type: "시설 수확",
    place: "경기 평택시 진위면",
    wage: 110000,
    count: 2,
    dates: ["2026-11-24", "2026-11-26"],
    start: "09:00",
    end: "17:00",
    rest: "12:00–13:00",
    experience: "신입·초보 가능",
    description: "하우스 안에서 딸기 수확과 포장을 함께합니다.",
  },
  {
    id: 3,
    title: "우리 밭에 새싹을 심는 일",
    crop: "배추",
    cropGroup: "채소",
    type: "노지 정식",
    place: "경기 양평군 양서면",
    wage: 130000,
    count: 4,
    dates: ["2026-11-25", "2026-11-27"],
    start: "08:00",
    end: "16:00",
    rest: "12:00–13:00",
    experience: "경력 필요",
    description: "배추 모종을 심고 주변을 정리하는 작업입니다.",
  },
];
const previousDemoPlaces: Partial<Record<number, string>> = {
  1: "경북 영주시 풍기읍",
  2: "충남 논산시 연무읍",
  3: "전남 해남군 삼산면",
};

function updateDemoJobPlace(job: Job): Job {
  const example = seed.find((item) => item.id === job.id);
  if (example && job.place === previousDemoPlaces[job.id])
    return { ...job, place: example.place };
  return job;
}

function withAvailableDemoDates(job: Job): Job {
  const normalized = updateDemoJobPlace(job);
  const example = seed.find((item) => item.id === job.id);
  if (!example || normalized.title !== example.title || normalized.place !== example.place) return normalized;
  const sample: Job = { ...normalized, cropGroup: normalized.cropGroup ?? example.cropGroup, experience: normalized.experience ?? example.experience, demoPosting: true };
  if (
    sample.extraDemoDatesAdded ||
    sample.dates.length !== example.dates.length ||
    !example.dates.every((date) => sample.dates.includes(date))
  ) {
    return sample;
  }

  return {
    ...sample,
    dates: [...sample.dates, "2026-11-28", "2026-11-30"].sort(),
    extraDemoDatesAdded: true,
  };
}

function updateDemoWorkPlace(work: Work): Work {
  return { ...work, job: updateDemoJobPlace(work.job) };
}

const initialWork: Work[] = [
  {
    id: "AS-101",
    job: seed[0],
    date: "2026-11-23",
    worker: "김하루",
    status: "근무 확정",
  },
  {
    id: "AS-102",
    job: seed[0],
    date: "2026-11-25",
    worker: "김하루",
    status: "지원 중",
  },
  {
    id: "AS-103",
    job: seed[0],
    date: "2026-11-23",
    worker: "박민수",
    status: "지원 중",
  },
];
const demoCancellations: Work[] = [
  {
    id: "AS-DEMO-WEATHER",
    job: seed[0],
    date: "2026-11-23",
    worker: "김하루",
    status: "근무 취소",
    category: "기상",
    reason: "호우와 돌풍으로 작업 시작 전에 확정 근무를 취소했어요.",
    at: "2026-11-23T07:10",
    demo: true,
  },
  {
    id: "AS-DEMO-FARM",
    job: seed[1],
    date: "2026-11-22",
    worker: "김하루",
    status: "근무 취소",
    category: "농장 사정",
    reason: "농장 장비 정비로 작업 시작 전에 확정 근무를 취소했어요.",
    at: "2026-11-22T07:20",
    demo: true,
  },
];
function useSaved<T>(key: string, initial: T, normalize?: (value: T) => T) {
  const [value, setValue] = useState<T>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem(key) || "null") as T | null;
      if (saved === null) return initial;
      return normalize ? normalize(saved) : saved;
    } catch {
      return initial;
    }
  });
  function save(next: T) {
    setValue(next);
    localStorage.setItem(key, JSON.stringify(next));
  }
  return [value, save] as const;
}
const farmJobsStorage = "onday-jobs-v4";
function initialFarmJobs() {
  for (const key of [farmJobsStorage, "onday-jobs-v3", "onday-jobs-v2"]) {
    try {
      const saved = JSON.parse(localStorage.getItem(key) || "null") as Job[] | null;
      if (Array.isArray(saved)) {
        const normalized = saved.map(withAvailableDemoDates).map(normalizeRegionalDemoJob);
        return key === farmJobsStorage ? normalized : addRegionalDemoJobs(normalized, key === "onday-jobs-v2");
      }
    } catch {
      // Try the previous format if this cache is unreadable.
    }
  }
  return addRegionalDemoJobs(seed.map(withAvailableDemoDates));
}
function useFarmJobs() {
  const [jobs, setJobs] = useState(initialFarmJobs);
  function saveJobs(next: Job[]) {
    setJobs(next);
    localStorage.setItem(farmJobsStorage, JSON.stringify(next));
  }
  return [jobs, saveJobs] as const;
}
const money = (n: number) => n.toLocaleString("ko-KR") + "원";
const day = (d: string) =>
  new Date(d + "T12:00:00+09:00").toLocaleDateString("ko-KR", {
    month: "long",
    day: "numeric",
    weekday: "short",
    timeZone: "Asia/Seoul",
  });
const today = () =>
  new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(
    new Date(),
  );
function Mark({ purple = false }: { purple?: boolean }) {
  return (
    <span className={purple ? "mark insurance-mark" : "mark"} aria-hidden="true">
      {purple ? <img className="woori-symbol" src={wooriSymbol} alt="" /> : "✦"}
    </span>
  );
}
function Card({ children, tone = "" }: { children: ReactNode; tone?: string }) {
  return <section className={"card " + tone}>{children}</section>;
}
function Badge({ children }: { children: ReactNode }) {
  return <span className="badge">{children}</span>;
}
function App() {
  const [app, setApp] = useState("home");
  const [tab, setTab] = useState("홈");
  const [role, setRole] = useSaved("onday-role", "근로자");
  const [farmMembers, setFarmMembers] = useSaved<FarmMember[]>("wonday-farm-members-v1", []);
  const [farmMemberId, setFarmMemberId] = useSaved("wonday-farm-member-id", "");
  const [demoSalaryAccounts, setDemoSalaryAccounts] = useSaved<Record<string, FarmSalaryAccount | undefined>>("wonday-demo-salary-accounts", {});
  const farmMember = farmMembers.find((member) => member.id === farmMemberId);
  const [farmLogin, setFarmLogin] = useSaved("onday-farm-login", false);
  const [insLogin, setInsLogin] = useSaved("onday-ins-login", false);
  const [largeText, setLargeText] = useSaved("wonday-large-text-v2", false);
  const [weatherRegion, setWeatherRegion] = useSaved<GyeonggiRegion>("wonday-weather-region", defaultWeatherRegion);
  const [account, setAccount] = useSaved("wonday-demo-account", "haru.demo");
  const [insuranceNames, setInsuranceNames] = useSaved<Record<string, string>>("wonday-display-names", {});
  const insuranceName = insuranceNames[account] ?? (account === "haru.demo" ? "이하루" : account);
  const [legacyLinked] = useSaved("onday-linked", false);
  const [connections, saveConnections] = useSaved<Record<string, "found" | "linked">>("wonday-demo-connections", {
    "haru.demo": legacyLinked ? "linked" : "found",
  });
  const linked = connections[account] === "linked";
  const platformRecord = linked || connections[account] === "found";
  const [jobs, setJobs] = useFarmJobs();
  const [works, setWorks] = useSaved("onday-works-v2", initialWork, (saved) => saved.map(updateDemoWorkPlace));
  const [claims, setClaims] = useSaved<Claim[]>("onday-claims-v3", [
    {
      id: "CL-DEMO-04",
      work: {
...initialWork[0],
id: "AS-DEMO-1",
        date: "2026-11-23",
        status: "근무 취소",
        category: "기상",
        reason: "강풍으로 작업 취소",
        at: "2026-11-23T07:15",
      },
      status: "지급 대상",
      risk: 82,
      amount: 72000,
      decision: "약정 기준 초과 · 지급 준비 중",
    },
    {
      id: "CL-DEMO-05",
      work: {
...initialWork[0],
id: "AS-DEMO-2",
        date: "2026-11-23",
        status: "근무 취소",
        category: "기상",
        reason: "기상 악화로 작업 취소",
        at: "2026-11-23T07:20",
      },
      status: "심사 중",
      decision: "관측 자료 및 약정 조건 확인 중",
    },
    {
      id: "CL-DEMO-01",
      work: {
...initialWork[0],
id: "AS-DEMO-3",
        date: "2026-11-20",
        status: "근무 취소",
        category: "기상",
        reason: "강수로 인한 수확 취소",
        at: "2026-11-20T07:15",
      },
      status: "지급 완료",
      risk: 82,
      amount: 72000,
      decision: "약정 기상지수 기준 초과 · 모의 지급 완료",
    },
    {
      id: "CL-DEMO-02",
      work: {
...initialWork[0],
id: "AS-DEMO-4",
        date: "2026-11-21",
        status: "근무 취소",
        category: "기상",
        reason: "비 예보로 작업 취소",
        at: "2026-11-21T07:10",
      },
      status: "미지급",
      risk: 43,
      amount: 0,
      decision: "관측 기상지수가 약정 기준값 이하",
    },
    {
      id: "CL-DEMO-03",
      work: {
...initialWork[0],
id: "AS-DEMO-5",
        date: "2026-11-22",
        status: "근무 취소",
        category: "기상",
        reason: "기상 악화로 작업 취소",
        at: "2026-11-22T07:20",
      },
      status: "보완 요청",
      decision: "작업지 확인 자료를 추가해 주세요.",
    },
  ], (saved) => saved.map((claim) => ({ ...claim, work: updateDemoWorkPlace(claim.work) })));
  const [policies, setPolicies] = useSaved<Policy[]>("onday-policies-v2", []);
  const [sampleContracts, setSampleContracts] = useSaved("wonday-premium-samples", samplePolicies);
  const usingSamples = policies.length === 0;
  const displayedPolicies = usingSamples ? sampleContracts : policies;
  const [managementFarmId, setManagementFarmId] = useState("");
  const [detail, setDetail] = useState<Job | null>(null);
  const [work, setWork] = useState<Work | null>(null);
  const [cancellationTarget, setCancellationTarget] = useState<Work | null>(null);
  const [selected, setSelected] = useState<string[]>([]);
  const [closed, setClosed] = useSaved<string[]>("onday-closed-v2", []);
  const [claimId, setClaimId] = useState<string | null>(null);
  const [claimView, setClaimView] = useState<"guide" | "history" | "login">("guide");
  const [claimCategory, setClaimCategory] = useState<ClaimCategory>("기후");
  const [premiumIndex, setPremiumIndex] = useState(0);
  const [enrollmentPayment, setEnrollmentPayment] = useState<PaymentMethod>(paymentMethods[0]);
  const [modal, setModal] = useState("");
  const [toast, setToast] = useState("");
  const [filter, setFilter] = useState("전체");
  const [myWorkPeriod, setMyWorkPeriod] = useState<WorkPeriod>("30일");
  const [myWorkCustomRange, setMyWorkCustomRange] = useState<WorkDateRange>(() => ({ from: today(), to: today() }));
  const [farmBrowseMode, setFarmBrowseMode] = useState<FarmBrowseMode>("일자리 상세검색");
  const [farmFilters, setFarmFilters] = useState<FarmFilters>(emptyFarmFilters);
  const [farmSearchFilters, setFarmSearchFilters] = useState<FarmSearchFilters>(emptyFarmSearchFilters);
  const [farmProfilePage, setFarmProfilePage] = useState<FarmProfilePage>("지원한 일자리");
  const [farmFavorites, setFarmFavorites] = useSaved<number[]>("onday-farm-favorites", []);
  const [farmPreferences, setFarmPreferences] = useSaved<FarmFilters>("onday-farm-preferences", emptyFarmFilters, normalizeFarmFilters);
  const [farmResume, setFarmResume] = useSaved<FarmResume>("onday-farm-resume", { summary: "", experience: "" });
  const [step, setStep] = useState(-1);
  const [enrollmentComplete, setEnrollmentComplete] = useState(false);
  const [agreed, setAgreed] = useState(false);
  const [product, setProduct] = useState("든든한 하루");
  const [showConnection, setShowConnection] = useState(false);
  const [platformVisit, setPlatformVisit] = useState(false);
  const [pendingJoin, setPendingJoin] = useState(false);
  const isEnrolling = app === "insurance" && step >= 0;
  const isClaimGuide = app === "insurance" && tab === "보험금 청구" && claimView === "guide";
  const [insuranceLaunching, setInsuranceLaunching] = useState(false);
  const insuranceLaunchTimer = useRef<number | undefined>(undefined);
  const toastTimer = useRef<number | undefined>(undefined);
  const farmDatesRef = useRef<HTMLElement>(null);
  const screenRef = useRef<HTMLDivElement>(null);
  const [tabScrollRequest, setTabScrollRequest] = useState(0);
  useEffect(() => {
    if (tabScrollRequest) screenRef.current?.scrollTo({ top: 0, left: 0, behavior: "instant" });
  }, [tabScrollRequest]);
  const farmScreenHistory = useRef<FarmScreenState[]>([]);
  useEffect(() => () => {
    window.clearTimeout(toastTimer.current);
    window.clearTimeout(insuranceLaunchTimer.current);
  }, []);
  function launchInsurance() {
    if (insuranceLaunching) return;
    setModal("");
    setInsuranceLaunching(true);
    insuranceLaunchTimer.current = window.setTimeout(() => {
      open("insurance");
      setInsuranceLaunching(false);
    }, 1000);
  }
  function startEnrollment() {
    setEnrollmentComplete(false);
    setPendingJoin(false);
    setTab("상품");
    setStep(0);
    setAgreed(false);
    setEnrollmentPayment(paymentMethods[0]);
  }
  function notify(s: string) {
    window.clearTimeout(toastTimer.current);
    setToast(s);
    toastTimer.current = window.setTimeout(() => setToast(""), 2600);
  }
  function nav(t: string, rememberFarmScreen = true) {
    if (rememberFarmScreen && app === "farm" && farmLogin && !platformVisit &&
      ["홈", "일자리", "내 공고", "공고 등록", "이력서 알림", "내 근무", "마이페이지"].includes(t) &&
      (t !== tab || detail || work)) {
      farmScreenHistory.current.push({ tab, detailId: detail?.id ?? null, workId: work?.id ?? null, selected,
        mode: farmBrowseMode, filters: farmFilters, searchFilters: farmSearchFilters, profilePage: farmProfilePage, statusFilter: filter });
      if (farmScreenHistory.current.length > 40) farmScreenHistory.current.shift();
    }
    setEnrollmentComplete(false);
    setPlatformVisit(false);
    setShowConnection(false);
    setPendingJoin(false);
    setTab(t);
    setDetail(null);
    setWork(null);
    setStep(-1);
    setFilter("전체");
    setFarmBrowseMode("일자리 상세검색");
    setFarmFilters(emptyFarmFilters);
    setFarmSearchFilters(emptyFarmSearchFilters);
    setFarmProfilePage("지원한 일자리");
    setClaimView("guide");
    setClaimCategory("기후");
  }
  function openClaimHistory(status = "전체") {
    nav("보험금 청구");
    setClaimView("history");
    setFilter(status);
  }
  function showClaimForm() {
    if (!insLogin) {
      setClaimView("login");
      return;
    }
    setModal("수동 접수");
  }
  function resumeInsuranceAction() {
    if (pendingJoin) startEnrollment();
    else if (tab === "보험금 청구" && claimView === "login") {
      setClaimView("guide");
      setModal("수동 접수");
    }
  }
  function advanceEnrollment() {
    if (step === 0) {
      setToast("");
      setShowConnection(true);
      return;
    }

    if (step === 9) {
      const from = today();
      const end = new Date(from + "T12:00:00+09:00");
      end.setDate(end.getDate() + 29);
      const to = new Intl.DateTimeFormat("sv-SE", { timeZone: "Asia/Seoul" }).format(end);
      setPolicies([...policies, {
        name: product, from, to, installments: initialInstallment, paymentMethod: enrollmentPayment,
      }]);
      nav("MY");
      setEnrollmentComplete(true);
      notify("모의 가입을 완료했어요.");
      return;
    }

    notify(enrollmentSteps[step].title + " 완료!");
    setStep(step + 1);
    setAgreed(false);
  }

  function payPremium(index: number, paymentMethod: PaymentMethod) {
    const policy = displayedPolicies[index];
    if (!policy) return;
    const installments = policy.installments ?? initialInstallment;
    if (installments.paidInstallments >= installments.totalInstallments) return;

    const updated = displayedPolicies.map((item, itemIndex) =>
      itemIndex === index
        ? { ...item, paymentMethod, installments: { ...installments, paidInstallments: installments.paidInstallments + 1 } }
        : item,
    );
    if (usingSamples) setSampleContracts(updated);
    else setPolicies(updated);
    setModal("");
    notify(paymentMethod + " 모의 납입을 완료했어요.");
  }

  function open(a: string) {
    farmScreenHistory.current = [];
    setApp(a);
    nav("홈", false);
  }
  const myApplications = works.filter((w) => w.worker === "김하루");
  const mine = [...myApplications, ...demoCancellations];
  const [myWorkPeriodDirection, setMyWorkPeriodDirection] = useState<WorkPeriodDirection>("past");
  const myWorkRange = workDateRange(myWorkPeriod, myWorkCustomRange, today(), myWorkPeriodDirection);
  const myWorkRangeError = workDateRangeError(myWorkRange);
  const periodWorks = filterWorkDates(mine, myWorkRange, filter).sort((a, b) => a.date.localeCompare(b.date));
  const cancelledWorks = mine.filter((w) => w.status === "근무 취소");
  const isEmployer = role === "고용주";
  const guideTitle = modal || (app === "farm"
    ? platformVisit ? "근로자 계정 확인"
      : !farmLogin ? tab === "회원가입" ? "회원가입" : "플랫폼 로그인"
      : work ? "근무 상세"
      : detail ? "공고 상세"
      : tab === "일자리" ? farmBrowseMode
      : tab === "마이페이지" && !isEmployer ? farmProfilePage
      : tab === "홈" ? "농작업 일자리" : tab
    : showConnection && isEnrolling ? "플랫폼 계정 연동"
      : isEnrolling ? enrollmentSteps[step].title
      : enrollmentComplete && tab === "MY" ? "보험 가입 완료"
      : !insLogin && (["MY", "보험료 납입"].includes(tab) || (tab === "보험금 청구" && !isClaimGuide)) ? "보험 로그인"
      : tab === "보험금 청구" && claimView === "history" ? "청구 내역"
      : tab === "홈" ? "보험 홈" : tab);
  const guideDescription = modal ? dialogDescriptions[modal] ?? "상세 내용을 확인하세요."
    : app === "farm"
      ? platformVisit ? "근로자 체험 계정을 확인한 뒤 보험 가입으로 돌아가세요."
        : !farmLogin ? tab === "회원가입" ? "내국인·외국인을 구분해 가입하세요. 등록번호는 입력 형식만 확인하며, 실제 본인인증은 제공되지 않아요." : "근로자 또는 고용주를 선택하고 로그인하세요."
        : work ? "근무 일정과 작업 장소, 진행 상태를 확인하세요."
        : detail ? isEmployer ? "공고의 근무 일정과 지원자를 확인하세요." : "일급과 작업 조건을 확인하고 가능한 날짜에 지원하세요."
        : tab === "홈" ? isEmployer ? "공고를 등록하고 모집 현황을 확인하세요." : "모집 중인 일자리와 지원 현황을 확인하세요. 지역별 일자리는 목록이나 지도에서 선택할 수 있어요. 지도는 끌어서 이동하고, Ctrl 또는 Shift + 휠로 확대·축소하세요."
        : farmScreenDescriptions[guideTitle] ?? "계정과 서비스 설정을 확인하세요."
      : showConnection && isEnrolling ? "플랫폼 계정을 연결하고 가입에 필요한 정보를 확인하세요."
        : isEnrolling ? enrollmentSteps[step].stageIndex === 3 ? "결제 방법을 선택하고 모의 결제로 가입을 마치세요." : "이 단계의 정보를 확인하고 다음 단계로 진행하세요."
        : guideTitle === "보험 가입 완료" ? "가입을 마쳤어요. MY에서 계약 내용을 확인할 수 있어요."
        : guideTitle === "보험 로그인" ? "로그인하면 계약 조회, 보험료 납입, 청구를 이용할 수 있어요."
        : insuranceScreenDescriptions[tab === "홈" ? tab : guideTitle] ?? "현재 화면에서 보험 서비스 정보를 확인하세요.";
  const employerId = farmMember?.id ?? "farmer.demo";
  const ownedJobs = employerJobs(jobs, employerId);
  const pendingApplications = pendingEmployerApplications(jobs, works, employerId);
  const listedJobs = isEmployer ? ownedJobs : jobs.filter((job) => isGyeonggiWorkplace(job.place));
  const farmJobListTab = isEmployer ? "내 공고" : "일자리";
  const canApply = app === "farm" && farmLogin && !platformVisit && !isEmployer && !!detail && !work;

  function openFarmList(mode: FarmBrowseMode = "일자리 상세검색", filters = emptyFarmFilters) {
    nav(farmJobListTab);
    setFarmBrowseMode(mode);
    setFarmFilters(filters);
    setFarmSearchFilters(normalizeFarmSearchFilters(filters));
  }

  function openFarmProfile(page: FarmProfilePage = "지원한 일자리") {
    nav("마이페이지");
    setFarmProfilePage(page);
  }

  function goFarmBack() {
    if (platformVisit) {
      setPlatformVisit(false);
      setApp("insurance");
      setShowConnection(true);
      return;
    }
    if (!farmLogin && tab === "회원가입") return nav("홈");
    if (!farmLogin) return open("home");
    if (work) return setWork(null);
    if (detail) { setDetail(null); setSelected([]); return; }
    if (tab === "마이페이지" && ["맞춤 설정", "이력서·경력"].includes(farmProfilePage)) {
      setFarmProfilePage("지원한 일자리");
      return;
    }
    const previous = farmScreenHistory.current.pop();
    if (!previous) {
      if (tab !== "홈") nav("홈", false);
      else open("home");
      return;
    }
    setTab(previous.tab);
    setDetail(jobs.find((job) => job.id === previous.detailId) ?? null);
    setWork(mine.find((entry) => entry.id === previous.workId) ?? null);
    setSelected(previous.selected);
    setFarmBrowseMode(previous.mode);
    setFarmFilters(previous.filters);
    setFarmSearchFilters(previous.searchFilters);
    setFarmProfilePage(previous.profilePage);
    setFilter(previous.statusFilter);
  }

  function openFarmJob(job: Job) {
    setDetail(job);
    setSelected([]);
  }

  function confirmedWorkActions(entry: Work) {
    if (!isEmployer || entry.status !== "근무 확정") return null;
    return <div className="farm-confirmed-actions">
      <button type="button" onClick={() => { setWork(entry); setModal("근무 완료"); }}>근무 완료</button>
      <button type="button" onClick={() => { setWork(entry); setModal("근무 취소"); }}>근무 취소</button>
    </div>;
  }
  function countConfirmed(job: Job, date: string) {
    return works.filter((entry) => entry.job.id === job.id && entry.date === date &&
      entry.status === "근무 확정").length;
  }

  function isDayClosed(job: Job, date: string, filled: number) {
    return closed.includes(job.id + date) || filled >= job.count;
  }

  function availableFarmDates(job: Job) {
    return job.dates.filter((date) => !isDayClosed(job, date, countConfirmed(job, date)));
  }

  function countOpenDays(job: Job) {
    return availableFarmDates(job).length;
  }

  const openFarmJobs = listedJobs.filter((job) => countOpenDays(job) > 0);

  const appliedJobCount = new Set(myApplications.map((entry) => entry.job.id)).size;
  const favoriteFarmJobs = listedJobs.filter((job) => farmFavorites.includes(job.id));
  const matchedFarmJobs = hasFarmFilters(farmPreferences) ? searchFarmJobs(openFarmJobs, farmPreferences) : [];

  function toggleFarmFavorite(job: Job) {
    setFarmFavorites(farmFavorites.includes(job.id) ? farmFavorites.filter((id) => id !== job.id) : [...farmFavorites, job.id]);
  }

  function farmJobCard(job: Job) {
    return <FarmJobCard key={job.id} job={job} openDays={countOpenDays(job)} onOpen={() => openFarmJob(job)}
      favorite={farmFavorites.includes(job.id)} onFavoriteChange={isEmployer ? undefined : () => toggleFarmFavorite(job)} />;
  }

  function farmWorkCard(entry: Work) {
    return (
      <FarmWorkCard key={entry.id} entry={entry} onOpen={() => setWork(entry)}
        onCancel={() => beginWorkerCancellation(entry)} />
    );
  }

  function beginWorkerCancellation(entry: Work) {
    if (isEmployer || entry.worker !== "김하루") return;
    const blocked = workerCancellationBlockedReason(entry);
    if (blocked) return notify(blocked);
    setCancellationTarget(entry);
    setModal("취소 확인");
  }

  const detailDates = detail?.dates.map((date) => {
    const filled = countConfirmed(detail, date);
    const application = myApplications.find((entry) => entry.job.id === detail.id && entry.date === date &&
      !["지원 취소", "근무 취소"].includes(entry.status));
    return { date, filled, status: application?.status, closed: isDayClosed(detail, date, filled) };
  }) ?? [];
  const selectableDates = detailDates.filter((date) => !date.closed && !date.status);
  const unavailableDates = detailDates.filter((date) => date.closed || date.status);
  const selectedDates = selectableDates.filter(({ date }) => selected.includes(date));

  function applyToJob(job: Job) {
    if (!selectedDates.length) return;
    setWorks([
      ...works,
      ...selectedDates.map(({ date }) => ({
        id: "AS-" + crypto.randomUUID(),
        job: { ...job },
        date,
        worker: "김하루",
        status: "지원 중",
      })),
    ]);
    setToast("");
    setSelected([]);
    setModal("지원 완료");
  }
  function exclusion(w: Work) {
    if (w.cancelledBy === "근로자" || w.category === "근로자 취소")
      return "근로자 본인이 취소한 근무는 당일 기상 취소 보상 대상에서 제외됩니다.";
    if (!isGyeonggiWorkplace(w.job.place))
      return "경기도 내 작업지의 근무만 접수할 수 있어요.";
    if (w.category === "농장 사정")
      return "농장 사정으로 취소된 근무는 접수 대상에서 제외됩니다.";
    if (w.category === "기타")
      return "기타 사유의 처리 방식은 팀 협의 중입니다.";
    if (
      w.status !== "근무 취소" ||
      w.at?.slice(0, 10) !== w.date ||
      w.at.slice(11, 16) >= w.job.start
    )
      return "당일 작업 시작 전 취소된 근무만 접수할 수 있어요.";
    if (w.demo) return "";
    if (!linked) return "농업 계정을 먼저 연결해 주세요.";
    if (!policies.some((p) => w.date >= p.from && w.date <= p.to))
      return "해당 근무일에 보장되는 계약이 없어요.";
    return "";
  }
  function receive(w: Work) {
    const existing = claims.find((c) => c.work.id === w.id);
    if (existing) {
      setClaimId(existing.id);
      setModal("");
      openClaimHistory();
      return notify("이미 접수된 건의 진행 상태를 표시합니다.");
    }
    const reason = exclusion(w);
    if (reason) return notify(reason);
    const claim: Claim = { id: "CL-" + crypto.randomUUID(), work: w, status: "자료 대기" };
    setClaims([claim, ...claims]);
    setClaimId(claim.id);
    setModal("");
    openClaimHistory();
    notify("접수했어요. 관측 자료가 확보되면 심사를 진행해요.");
  }
  const tabs =
    app === "farm"
      ? role === "고용주"
        ? ["내 공고", "공고 등록", "홈", "이력서 알림", "MY"]
        : ["일자리", "WONDAY", "홈", "내 근무", "MY"]
      : ["상품", "보험금 청구", "홈", "보험료 납입", "MY"];
  const insuranceTabIcons = ["products", "claim", "home", "payment", "profile"] as const;
  const farmTabIcons = isEmployer ? ["products", "plus", "home", "bell", "profile"] as const
    : ["products", "grid", "home", "calendar", "profile"] as const;
  const isTabActive = (label: string) => app === "farm" && label === "일자리" ? tab === "일자리"
    : app === "farm" && label === "MY" ? tab === "마이페이지" : tab === label;
  const inProgressClaims = claims.filter((claim) => !["지급 완료", "미지급"].includes(claim.status));
  const visibleClaims = filter === "전체" ? claims
    : filter === "진행 중" ? inProgressClaims
    : claims.filter((claim) => claim.status === filter);
  function loginForm(insurance = false) {
    return (
      <div className="login-page">
        {insurance ? <Mark purple /> : <img className="farm-login-symbol" src={agriworkSymbol} width={44} height={44} alt="" />}
        <h2>
          {insurance ? (
            <>
              안심의 시작,
              <br />
              WONDAY 보험.
            </>
          ) : (
            <>
              반가워요.
              <br />
              오늘의 일을 시작해요.
            </>
          )}
        </h2>
        <p>
          {platformVisit ? "등록된 근로자 체험 계정을 확인하면 보험 가입으로 돌아갑니다." : insurance
            ? "나의 계약과 보상 내역을 확인하세요."
            : "가입한 계정이나 체험 계정으로 로그인하세요."}
        </p>
        {!insurance && !platformVisit && (
          <div className="segmented">
            {["근로자", "고용주"].map((r) => (
              <button
                key={r}
                className={role === r ? "active" : ""}
                onClick={() => setRole(r)}
              >
                {r}
              </button>
            ))}
          </div>
        )}
        <form
          onSubmit={async (e) => {
            e.preventDefault();
            if (platformVisit && !insurance) {
              const login = new FormData(e.currentTarget);
              if (login.get("account") !== "worker.demo" || login.get("password") !== "onday2026")
                return notify("등록된 근로자 체험 계정을 확인해 주세요.");
              setRole("근로자");
              setFarmLogin(true);
              saveConnections({ ...connections, [account]: "found" });
              setPlatformVisit(false);
              setShowConnection(true);
              setApp("insurance");
              startEnrollment();
              return;
            }
            if (insurance) {
              setAccount(String(new FormData(e.currentTarget).get("account")).trim());
              setInsLogin(true);
              resumeInsuranceAction();
            } else {
              const fields = new FormData(e.currentTarget);
              const loginId = String(fields.get("account") ?? "").trim();
              const password = String(fields.get("password") ?? "");
              const member = farmMembers.find((entry) => entry.id.toLowerCase() === loginId.toLowerCase());
              if (member) {
                try {
                  if (await hashFarmPassword(password, member.salt) !== member.passwordHash) return notify("아이디와 비밀번호를 확인해 주세요.");
                } catch { return notify("로그인 정보를 확인하지 못했어요. 다시 시도해 주세요."); }
                setFarmMemberId(member.id);
                setRole(member.role);
              } else {
                const expectedId = role === "고용주" ? "farmer.demo" : "worker.demo";
                if (loginId !== expectedId || password !== "onday2026") return notify("아이디와 비밀번호를 확인해 주세요.");
                setFarmMemberId("");
              }
              setFarmLogin(true);
              nav("홈");
            }
            notify("로그인했어요.");
          }}
        >
          <label>
            아이디
            <input
              required
              name="account"
              defaultValue={
                platformVisit ? "worker.demo" : insurance
                  ? "haru.demo"
                  : role === "고용주"
                    ? "farmer.demo"
                    : "worker.demo"
              }
              key={role}
            />
          </label>
          <label>
            비밀번호
            <input name="password" type="password" required defaultValue="onday2026" />
          </label>
          <button className="primary">
            로그인 <span>↗</span>
          </button>
        </form>
        {!insurance && !platformVisit && <button type="button" className="text-button farm-signup-link" onClick={() => nav("회원가입")}>처음이신가요? 회원가입</button>}
        {insurance && (
          <button
            className="text-button"
            onClick={() => setModal("보험 회원가입")}
          >
            처음이신가요? 회원가입
          </button>
        )}
        <p className="fine">
          {insurance ? "체험 계정 haru.demo에는 근로자 정보가 있어요. new.demo로 로그인하면 연동 정보가 없는 경우를 확인할 수 있어요." : "화면 체험용 계정 · 인증은 서버 연동 후 제공됩니다."}
        </p>
      </div>
    );
  }
  const textSizeToggle = (
    <button className="text-size-toggle" aria-pressed={largeText}
      aria-label={largeText ? "작은 글씨로 전환" : "큰 글씨로 전환"}
      onClick={() => setLargeText(!largeText)}>{largeText ? "작은 글씨" : "큰 글씨"}</button>
  );
  return (
    <div className={app === "insurance" ? "studio insurance" : app === "farm" ? "studio farm-ui" : "studio"}>
      <aside className="studio-side">
        <a
          className="wordmark"
          href="#"
          onClick={(e) => {
            e.preventDefault();
            open("home");
          }}
        >
          <img className="woori-sidebar-logo" src={wooriSymbol} alt="우리" /> <span className="woori-oneday-name">WON데이</span>
        </a>
        <div className="studio-copy">
          {(app !== "home" || modal) ? <div className="studio-screen-guide" role="status" aria-atomic="true">
            <span className="overline">{app === "farm" ? agriworkName : app === "insurance" ? "WONDAY 보험" : "온데이"}</span>
            <h1>{app === "banking" ? "우리WON뱅킹" : app === "global" ? "우리WON글로벌" : guideTitle}</h1>
            <p>{app === "banking" ? "공식 앱의 계좌 카드와 금융 메뉴 구성을 참고한 체험 화면이에요. 계좌 조회와 이체 입력 흐름을 둘러보세요." : app === "global" ? "공식 앱의 언어 선택과 해외송금 중심 구성을 참고했어요. 주요 메뉴는 한국어·영어·베트남어로 전환할 수 있어요." : guideDescription}</p>
          </div> : <>
          <span className="overline">일하는 오늘을, 더 든든하게</span>
          <h1>
            좋은 일자리와
            <br />
            안심되는 하루.<span className="lime-dot">✳</span>
          </h1>
          <p>
            일을 찾는 순간부터 하루를 마칠 때까지.
            <br />
            WON데이가 당신의 내일을 함께 준비해요.
          </p>
          </>}
          <div className="studio-links">
            <button className={app === "home" ? "active" : ""} onClick={() => open("home")}><FarmIcon name="grid" />홈 화면<span aria-hidden="true">↗</span></button>
            <button
              className={app === "farm" ? "active" : ""}
              onClick={() => open("farm")}
            >
              <img className="agriwork-shortcut-symbol" src={agriworkSymbol} width={34} height={34} alt="" />
              <strong className="agriwork-shortcut-name">{agriworkName}</strong><span aria-hidden="true">↗</span>
            </button>
            <button
              className={app === "insurance" ? "active" : ""}
              onClick={() => open("insurance")}
            >
              <img className="agriwork-shortcut-symbol" src={wooriSymbol} alt="" /> WON데이 보험 <span>↗</span>
            </button>
            <button className={app === "banking" ? "active" : ""} onClick={() => open("banking")}><img className="woori-sidebar-app-icon" src={wonBankingIcon} alt="" />우리WON뱅킹<span aria-hidden="true">↗</span></button>
            <button className={app === "global" ? "active" : ""} onClick={() => open("global")}><img className="woori-sidebar-app-icon" src={wonGlobalIcon} alt="" />우리WON글로벌<span aria-hidden="true">↗</span></button>
          </div>
        </div>
        <footer>
          <span>작은 안심이 모여, 더 나은 내일로.</span>
          <span>© 2026 WON데이</span>
        </footer>
      </aside>
      <main className={"phone " + (app === "home" ? "launcher" : app === "banking" || app === "global" ? "won-phone" : isEnrolling ? "enrolling" : "") + (largeText ? app === "insurance" ? " insurance-large-text" : app === "farm" ? " farm-large-text" : "" : "")}>
        <div className="status-bar" role="group" aria-label="기기 상태 미리보기">
          <b className="status-time">9:41</b>
          <span className="island" aria-hidden="true" />
          <span className="status-indicators" role="img" aria-label="신호 양호, Wi-Fi 연결, 배터리 80%">
            <svg className="status-signal" viewBox="0 0 20 14" fill="currentColor" aria-hidden="true">
              <rect x="0" y="9" width="3.5" height="5" rx=".8" />
              <rect x="5.5" y="6" width="3.5" height="8" rx=".8" />
              <rect x="11" y="3" width="3.5" height="11" rx=".8" />
              <rect x="16.5" width="3.5" height="14" rx=".8" />
            </svg>
            <svg className="status-wifi" viewBox="0 0 18 14" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" aria-hidden="true">
              <path d="M1.5 4a11.1 11.1 0 0 1 15 0M4.5 7.2a6.6 6.6 0 0 1 9 0M7.4 10.2a2.4 2.4 0 0 1 3.2 0" />
              <circle cx="9" cy="12.5" r=".8" fill="currentColor" stroke="none" />
            </svg>
            <svg className="status-battery" viewBox="0 0 28 14" fill="currentColor" aria-hidden="true">
              <rect x=".6" y="1" width="23.5" height="12" rx="3" fill="none" stroke="currentColor" strokeOpacity=".4" strokeWidth="1.2" />
              <rect x="2.5" y="3" width="16" height="8" rx="1.4" />
              <path d="M25.6 4.5c2.4.4 2.4 4.6 0 5Z" opacity=".5" />
            </svg>
          </span>
        </div>
        {app === "banking" || app === "global" ? <WooriBankingApp key={app} global={app === "global"} onClose={() => open("home")} onInsurance={() => setModal("보험 앱으로 이동할까요?")} /> : app === "home" ? (
          <>
            <IphoneHomeWidgets onWeather={() => open("insurance")} onCalendar={() => { open("farm"); if (farmLogin) nav(role === "고용주" ? "내 공고" : "내 근무"); }} />
            <div className="home-apps">
              <button onClick={() => open("farm")}>
                <span className="app-icon farm-icon"><img src={agriworkSymbol} width={51} height={51} alt="" /></span>
                <b>{agriworkName}</b>
                <small>나에게 맞는 일자리</small>
              </button>
              <button onClick={() => open("insurance")}>
                <span className="app-icon insurance-icon" aria-hidden="true">
                  <img className="woori-symbol" src={wooriSymbol} alt="" />
                </span>
                <b>WONDAY 보험</b>
                <small className="insurer-wordmark">우리손해보험</small>
              </button>
              <button onClick={() => open("banking")}><span className="app-icon bank-app-icon"><img src={wonBankingIcon} alt="" /></span><b>우리WON뱅킹</b><small>나의 일상 금융</small></button>
              <button onClick={() => open("global")}><span className="app-icon bank-app-icon"><img src={wonGlobalIcon} alt="" /></span><b>우리WON글로벌</b><small>세계와 이어지는 금융</small></button>            </div>
            <div className="iphone-page-indicator" aria-hidden="true"><i /><i /></div>
            <IphoneDock />
          </>
        ) : (
          <>
            {app === "farm" && farmLogin && !platformVisit && !work && (tab === "홈" || tab === "일자리" || tab === "내 공고" || !!detail) &&
              <FarmScrollTop key={[tab, detail?.id].join(":")} raised={canApply} />}
            {isEnrolling ? (
              <header className="enrollment-header">
                <div className="enrollment-topline">
                  <button className="back" onClick={() => {
                    if (showConnection) setShowConnection(false);
                    else {
                      setStep(step - 1);
                      setAgreed(false);
                    }
                  }}>← 이전</button>
                  <span>{product}</span>
                  {textSizeToggle}
                </div>
                <EnrollmentProgress step={step} compact />
              </header>
            ) : (
            <header className="app-header">
              <button className="app-brand" onClick={() => nav("홈")}>
                {app === "farm" ? <img className="farm-wordmark" src={agriworkLogo} width={196} height={24} alt={agriworkName} /> :
                  <><Mark purple /><span className="insurer-wordmark">WON데이보험</span></>}
              </button>
              <div className={app === "insurance" ? "insurance-header-actions" : "farm-header-actions"}>
                {textSizeToggle}
                <button
                  className="icon-button"
                  aria-label="알림"
                  onClick={() => setModal("알림")}
                >
                  <InsuranceIcon name="bell" /><i />
                </button>
                {app !== "farm" && <button
                  className="icon-button"
                  aria-label="휴대폰 홈"
                  onClick={() => open("home")}
                >
                  <InsuranceIcon name="launcher" />
                </button>}
              </div>
              {app === "farm" && (farmLogin || platformVisit || tab === "회원가입") && (tab !== "홈" || !!detail || !!work || platformVisit) && <button type="button" className="farm-header-back" aria-label="뒤로 가기" onClick={goFarmBack}><FarmIcon name="chevronLeft" /><span>뒤로</span></button>}
              {app === "farm" && farmLogin && !platformVisit && tab === "마이페이지" && !detail && !work && <h2 className="farm-header-page-title" id="farm-profile-title">마이페이지</h2>}
            </header>
            )}
            <div
              ref={screenRef}
              className={isEnrolling ? "screen enrollment-screen" : app === "farm" ? "screen farm-screen" : "screen"}
              key={app === "insurance" ? tab + step + showConnection + claimView : [tab, detail?.id, work?.id].join(":")}
            >
              {isEnrolling && showConnection ? (
                <ConnectionScreen
                  hasRecord={platformRecord || linked}
                  onVisitPlatform={() => {
                    setShowConnection(false);
                    setPlatformVisit(true);
                    setApp("farm");
                  }}
                  onBack={() => setShowConnection(false)}
                  onComplete={() => {
                    saveConnections({ ...connections, [account]: "linked" });
                    setShowConnection(false);
                    setStep(1);
                    setAgreed(false);
                    notify(`${agriworkName} 연동 완료!`);
                  }}
                />
              ) : app === "farm" ? (
                !farmLogin || platformVisit ? (
                  !platformVisit && tab === "회원가입" ? <FarmSignup initialRole={role} members={farmMembers}
                    onCancel={() => nav("홈")} onRegister={(member) => {
                      setFarmMembers([...farmMembers, member]);
                      setFarmMemberId(member.id);
                      setRole(member.role);
                      setFarmLogin(true);
                      nav("홈");
                      notify("회원가입을 완료했어요.");
                    }} /> : loginForm()
                ) : (
                  <>
                    {isEmployer && work && ["근무 확정", "근무 완료"].includes(work.status) ? (
                      <article className="farm-confirmed-detail">
                        <h2>{work.worker}</h2><p>{work.job.title}</p>
                        <FarmJobOverview job={work.job} openDays={countOpenDays(work.job)} conditionsOnly />
                        {work.status === "근무 확정" ? confirmedWorkActions(work) : <p role="status">근무 완료로 처리했어요.</p>}
                      </article>
                    ) : work ? (
                      <>
                        {work.reason && (
                          <Card tone="farm-work-change-reason">
                            <h3>{work.cancelledBy === "근로자" ? "본인이 기록한 취소" : "고용주가 기록한 변경"}</h3>
                            <p>
                              {work.category} · {work.reason}
                            </p>
                            <p>{work.at?.replace("T", " ")} (KST)</p>
                          </Card>
                        )}
                        <div className="farm-work-heading">
                          <Badge>{work.status}{work.demo ? " · 체험" : ""}</Badge>
                          <h2>
                            {day(work.date)}
                            <br />
                            {work.job.title}
                          </h2>
                        </div>
                        <Card>
                          <h3>{work.status === "지원 중" || work.status === "지원 취소" ? "지원한 근무 조건" : "확정 당시 근무 조건"}</h3>
                          <dl>
                            <dt>근로자</dt>
                            <dd>{work.worker}</dd>
                            <dt>작업지</dt>
                            <dd>{work.job.place}</dd>
                            <dt>시간대</dt>
                            <dd>GMT+09:00</dd>
                            <dt>일급</dt>
                            <dd>{money(work.job.wage)}</dd>
                            <dt>근무시간</dt>
                            <dd>
                              {work.job.start}–{work.job.end}
                            </dd>
                            <dt>무급 휴게 구간</dt>
                            <dd>{work.job.rest}</dd>
                          </dl>
                        </Card>
                        <FarmWorkplaceMap address={work.job.place} registeredPin={work.job.workplacePin} />
                        <WorkplaceWeather address={work.job.place} />
                        {!isEmployer && work.worker === "김하루" && ["지원 중", "근무 확정"].includes(work.status) && (
                          <div className="farm-work-detail-actions">
                            <button type="button" className="farm-cancel-action" disabled={!!workerCancellationBlockedReason(work)}
                              onClick={() => beginWorkerCancellation(work)}>{work.status === "지원 중" ? "지원 취소" : "근무 취소"}</button>
                            {workerCancellationBlockedReason(work) && <p>{workerCancellationBlockedReason(work)}</p>}
                          </div>
                        )}
                        {role === "고용주" && work.status === "근무 확정" ? (
                          <>
                            <button
                              className="primary"
                              onClick={() => setModal("근무 취소")}
                            >
                              이 근로자의 근무 취소
                            </button>
                            <button
                              className="secondary"
                              onClick={() => setModal("조기 종료")}
                            >
                              조기 종료 등록
                            </button>
                          </>
                        ) : (
                          <button
                            className="primary farm-compensation-button"
                            onClick={() => {
                              const claim = claims.find((c) => c.work.id === work.id);
                              open("insurance");
                              if (claim) {
                                openClaimHistory();
                                setClaimId(claim.id);
                              } else if (work.status === "근무 취소") {
                                nav("보험금 청구");
                                showClaimForm();
                              } else {
                                nav("MY");
                              }
                            }}
                          >
                            WONDAY 보험에서 보상 확인하기 ↗
                          </button>
                        )}
                      </>
                    ) : detail ? (
                      <article className="farm-job-detail">
                        <FarmJobOverview job={detail} openDays={countOpenDays(detail)} collapsible={isEmployer}
                          favorite={farmFavorites.includes(detail.id)} onFavoriteChange={!isEmployer ? () => toggleFarmFavorite(detail) : undefined} />
                        <section className="farm-detail-dates" aria-labelledby="farm-dates" ref={farmDatesRef}>
                        <h3 id="farm-dates">
                          {role === "고용주"
                            ? "지원자 관리"
                            : "일할 수 있는 날짜를 골라주세요"}
                        </h3>
                        {isEmployer && detailDates.length === 0 && <p className="farm-empty">현재 모집 중인 근무일이 없어요.</p>}
                        {!isEmployer ? (
                          <>
                            {!selectableDates.length && <p className="farm-dates-unavailable">현재 지원할 수 있는 날짜가 없어요.</p>}
                            {selectableDates.map(({ date, filled }) => (
                              <button className="date-row farm-apply-date" type="button" key={date}
                                aria-pressed={selected.includes(date)}
                                onClick={() => setSelected((previous) => previous.includes(date)
                                  ? previous.filter((value) => value !== date) : [...previous, date])}>
                                <span className="farm-apply-date-mark" aria-hidden="true"><FarmIcon name="check" /></span>
                                <span>
                                  <strong>{day(date)}</strong>
                                  <small>지원 가능 · {detail.count - filled}명 남음</small>
                                </span>
                              </button>
                            ))}
                            {unavailableDates.length > 0 && (
                              <details className="farm-unavailable-dates">
                                <summary>지원 이력·마감 날짜 {unavailableDates.length}개</summary>
                                <ul>
                                  {unavailableDates.map(({ date, status }) => (
                                    <li key={date}><span>{day(date)}</span><span>{status || "모집 마감"}</span></li>
                                  ))}
                                </ul>
                              </details>
                            )}
                          </>
                        ) : <FarmApplicantSchedule key={detail.id} dates={detailDates.map((item) => item.date)} applications={works.filter((entry) => entry.job.id === detail.id)}
                          renderApplication={(entry) => <div className="farm-schedule-application"><span><strong>{entry.worker}</strong>
                            <span className="badge farm-applicant-status" data-status={entry.status}>{entry.status}</span></span>{confirmedWorkActions(entry)}</div>}
                          renderDate={(d) => {
                          const info = detailDates.find((item) => item.date === d);
                          if (!info) return null;
                          const { filled, closed: dayClosed } = info;
                          return (
                            <div className="date-row" key={d}>
                                  <b>{day(d)}</b>
                                  <p>
                                    {filled}/{detail.count}명 확정{" "}
                                    {dayClosed && "· 모집 마감"}
                                  </p>
                                  {works
                                    .filter(
                                      (w) =>
                                        w.job.id === detail.id && w.date === d,
                                    )
                                    .map((w) => (
                                      <div className="applicant" key={w.id}>
                                        <span><strong>{w.worker}</strong><span className="badge farm-applicant-status" data-status={w.status}>{w.status}</span></span>
                                        {confirmedWorkActions(w)}
                                      </div>
                                    ))}
                                  <div className="inline-actions">
                                    <button
                                      onClick={() => {
                                        setClosed([...closed, detail.id + d]);
                                        setWorks(
                                          works.map((w) =>
                                            w.job.id === detail.id &&
                                            w.date === d &&
                                            w.status === "지원 중"
                                              ? { ...w, status: "미선정" }
                                              : w,
                                          ),
                                        );
                                        notify(
                                          "남은 지원자를 미선정 처리하고 모집을 마감했어요.",
                                        );
                                      }}
                                    >
                                      모집 마감
                                    </button>
                                    <button
                                      onClick={() => {
                                        if (
                                          works.some(
                                            (w) =>
                                              w.job.id === detail.id &&
                                              w.date === d &&
                                              ![
                                                "지원 중",
                                                "모집 취소",
                                              ].includes(w.status),
                                          )
                                        )
                                          return notify(
                                            "확정 이력이 있는 날짜는 변경할 수 없어요.",
                                          );
                                        setSelected([d]);
                                        setModal("일정 변경");
                                      }}
                                    >
                                      일정 변경
                                    </button>
                                    <button
                                      onClick={() => {
                                        if (
                                          works.some(
                                            (w) =>
                                              w.job.id === detail.id &&
                                              w.date === d &&
                                              ![
                                                "지원 중",
                                                "모집 취소",
                                              ].includes(w.status),
                                          )
                                        )
                                          return notify(
                                            "확정 이력이 있는 날짜는 제외할 수 없어요.",
                                          );
                                        const next = {
                                          ...detail,
                                          dates: detail.dates.filter(
                                            (x) => x !== d,
                                          ),
                                        };
                                        setJobs(
                                          jobs.map((j) =>
                                            j.id === detail.id ? next : j,
                                          ),
                                        );
                                        setDetail(next);
                                        setWorks(
                                          works.map((w) =>
                                            w.job.id === detail.id &&
                                            w.date === d
                                              ? { ...w, status: "모집 취소" }
                                              : w,
                                          ),
                                        );
                                        notify(
                                          "지원 내역에 모집 취소를 반영했어요.",
                                        );
                                      }}
                                    >
                                      모집 취소
                                    </button>
                                  </div>
                            </div>
                          );
                        }} />}
                        </section>
                        <FarmWorkplaceMap address={detail.place} registeredPin={detail.workplacePin} />
                        <WorkplaceWeather address={detail.place} />
                      </article>
                    ) : (
                      <>
                        {tab === "홈" && (
                          <FarmHome displayName={farmMember?.name} employer={isEmployer} favoriteIds={farmFavorites} onToggleFavorite={isEmployer ? undefined : toggleFarmFavorite}
                            jobs={isEmployer ? listedJobs : openFarmJobs}
                            preferences={farmPreferences} availableDates={availableFarmDates}
                            onSettings={() => openFarmProfile("맞춤 설정")} onOpen={openFarmJob}

                            applicationCount={isEmployer ? listedJobs.length : appliedJobCount} favoriteCount={isEmployer ? listedJobs.filter((job) => countOpenDays(job) > 0).length : favoriteFarmJobs.length}
                            onManage={() => isEmployer ? nav("내 공고") : openFarmProfile()}
                            onCreate={() => nav("공고 등록")}
                            onBookmarks={() => openFarmProfile("찜한 일자리")} />
                        )}
                        {["일자리", "내 공고"].includes(tab) && (
                          <FarmJobList regionContent={isEmployer ? <FarmWorkManagement onOpenWorker={setWork} jobs={ownedJobs} works={works} selectedFarmId={managementFarmId} onChooseFarm={setManagementFarmId} onOpen={openFarmJob} openDays={countOpenDays} onCreate={() => nav("공고 등록")} /> : undefined} employer={isEmployer} jobs={listedJobs} favoriteIds={farmFavorites} onToggleFavorite={isEmployer ? undefined : toggleFarmFavorite}
                            mode={farmBrowseMode} onModeChange={openFarmList} filters={farmFilters}
                            onFilterChange={setFarmFilters} preferences={farmPreferences}
                            searchFilters={farmSearchFilters} onSearchFilterChange={setFarmSearchFilters}
                            onSettings={() => openFarmProfile("맞춤 설정")} onOpen={openFarmJob}
                            openDays={countOpenDays} availableDates={availableFarmDates} />
                        )}
                        {isEmployer && tab === "이력서 알림" && <FarmApplicantAlerts onOpenWorker={setWork} jobs={ownedJobs} works={works} onOpen={openFarmJob} />}
                        {tab === "공고 등록" && (
                          <>
                            <h2>농작업 공고 등록</h2>
                            <p>경기도 내 작업지와 날짜별 모집 조건을 입력해 주세요.</p>
                            <form
                              onSubmit={(e) => {
                                e.preventDefault();
                                const f = new FormData(e.currentTarget);
                                const city = String(f.get("city"));
                                const cropGroup = String(f.get("cropGroup") ?? "");
                                const crop = String(f.get("crop") ?? "");
                                if (!cropGroups.includes(cropGroup) || !getCropOptions(cropGroup).includes(crop))
                                  return notify("공고에 해당하는 작물 분류와 품목을 선택해 주세요.");
                                const address = String(f.get("place") ?? "").trim();
                                if (!isGyeonggiRegion(city) || !address)
                                  return notify("경기도 시·군을 선택하고 상세 작업지를 입력해 주세요.");
                                const rawPin = String(f.get("workplacePin") ?? "").trim();
                                const workplacePin = parseWorkplacePin(rawPin);
                                if (rawPin && !workplacePin)
                                  return notify("경기도 내 작업장 좌표를 위도, 경도 순서로 입력해 주세요.");
                                if (!String(f.get("dates") ?? "").trim()) return notify("달력에서 근무일을 한 개 이상 선택해 주세요.");
                                const dates = String(f.get("dates"))
                                  .split(",")
                                  .map((s) => s.trim());
                                const start = String(f.get("start"));
                                const end = String(f.get("end"));
                                const rs = String(f.get("rs"));
                                const re = String(f.get("re"));
                                if (
                                  dates.some(
                                    (d) =>
                                      !/^\d{4}-\d{2}-\d{2}$/.test(d) ||
                                      isNaN(Date.parse(d)),
                                  )
                                )
                                  return notify(
                                    "근무일을 YYYY-MM-DD 형식으로 입력해 주세요.",
                                  );
                                if (!(start < rs && rs < re && re < end))
                                  return notify(
                                    "근무와 휴게 구간의 시각을 확인해 주세요.",
                                  );
                                const j: Job = {
                                  employerId,
                                  id: Math.max(0, ...jobs.map((job) => job.id)) + 1,
                                  title: String(f.get("title")),
                                  crop,
                                  cropGroup,
                                  type: normalizeWorkType(String(f.get("type"))),
                                  place: `경기 ${city} ${address}`,
                                  workplacePin,
                                  description: String(f.get("description")),
                                  experience: String(f.get("experience") ?? "").trim() || undefined,
                                  benefits: String(f.get("benefits") ?? "").trim() || undefined,
                                  wage: Number(f.get("wage")),
                                  count: Number(f.get("count")),
                                  dates: [...new Set(dates)],
                                  start,
                                  end,
                                  rest: rs + "–" + re,
                                };
                                setJobs([...jobs, j]);
                                nav("내 공고");
                                notify("새 공고를 등록했어요.");
                              }}
                            >
                              <label>
                                공고 제목
                                <input
                                  name="title"
                                  required
                                  placeholder="어떤 일을 함께하나요?"
                                />
                              </label>
                              <FarmCropSelection />
                              <label>
                                  작업형태
                                  <select name="type" required defaultValue="">
                                    <option value="">전체</option>
                                    {workTypes.map((type) => <option key={type}>{type}</option>)}
                                  </select>
                              </label>
                              <label>
                                작업 내용
                                <textarea
                                  name="description"
                                  required
                                  placeholder="작업 내용과 준비물을 알려주세요"
                                />
                              </label>
                              <label>
                                필요 경력 (선택)
                                <select name="experience" defaultValue="">
                                  <option value="">경력 조건 미기재</option>
                                  {experienceOptions.map((value) => <option key={value}>{value}</option>)}
                                </select>
                              </label>
                              <label>
                                복리후생·교통 안내 (선택)
                                <input name="benefits" placeholder="예: 작업장 집결 장소와 교통편" />
                              </label>
                              <label>
                                작업지 시·군
                                <select name="city" required defaultValue="">
                                  <option value="" disabled>경기도 시·군 선택</option>
                                  {gyeonggiRegions.map((city) => <option key={city}>{city}</option>)}
                                </select>
                              </label>
                              <label>
                                상세 작업지
                                <input
                                  name="place"
                                  required
                                  placeholder="예: 장호원읍 샘터길 12"
                                />
                              </label>
                              <details className="farm-pin-settings">
                                <summary>작업장 핀 좌표 입력 (선택)</summary>
                                <label>
                                  위도, 경도
                                  <input name="workplacePin" placeholder="예: 37.1318, 127.597" aria-describedby="farm-pin-hint" />
                                </label>
                                <p id="farm-pin-hint">입력한 지점이 공고 지도에 작업장 핀으로 표시돼요.</p>
                              </details>
                              <div className="form-grid">
                                <label>
                                  일급 (원)
                                  <input
                                    name="wage"
                                    type="number"
                                    min="1"
                                    required
                                    defaultValue="120000"
                                  />
                                </label>
                                <label>
                                  하루 모집 인원
                                  <input
                                    name="count"
                                    type="number"
                                    min="1"
                                    required
                                    defaultValue="2"
                                  />
                                </label>
                              </div>
                              <FarmWorkdayPicker />
                              <p className="fine">
                                등록 후 일정 변경에서 날짜별 시간을 조정할 수
                                있어요.
                              </p>
                              <div className="form-grid">
                                {[
                                  ["start", "시작", "08:00"],
                                  ["end", "종료", "17:00"],
                                  ["rs", "무급 휴게 시작", "12:00"],
                                  ["re", "무급 휴게 종료", "13:00"],
                                ].map(([n, l, v]) => (
                                  <label key={n}>
                                    {l}
                                    <input
                                      name={n}
                                      type="time"
                                      required
                                      defaultValue={v}
                                    />
                                  </label>
                                ))}
                              </div>
                              <button className="primary">
                                공고 등록하기 ↗
                              </button>
                            </form>
                          </>
                        )}
                        {tab === "내 근무" && (
                          <>
                            <h2>차곡차곡, 나의 하루</h2>
                            <FarmWorkPeriodFilter period={myWorkPeriod} range={myWorkRange} direction={myWorkPeriodDirection}
                              onDirectionChange={setMyWorkPeriodDirection}
                              onPeriodChange={(value) => {
                                if (value === "직접 설정" && myWorkPeriod !== "직접 설정") setMyWorkCustomRange(myWorkRange);
                                setMyWorkPeriod(value);
                              }} onRangeChange={setMyWorkCustomRange} />
                            <div className="farm-work-status-filter" role="group" aria-label="내 근무 상태 필터">
                              <button type="button" className="farm-work-status-all" aria-pressed={filter === "전체"}
                                onClick={() => setFilter("전체")}>전체</button>
                              <span className="farm-work-status-divider" aria-hidden="true" />
                              <div className="farm-work-status-options" role="group" aria-label="근무 상태 선택">
                              {[
                                "지원 중",
                                "근무 확정",
                                "근무 취소",
                                "지원 취소",
                              ].map((f) => (
                                <button
                                  key={f}
                                  type="button"
                                  aria-pressed={filter === f}
                                  onClick={() => setFilter(f)}
                                >
                                  {f}
                                </button>
                              ))}
                              </div>
                            </div>
                            {!myWorkRangeError && <p className="farm-work-result-count" role="status">근무 내역 <strong>{periodWorks.length}건</strong></p>}
                            {!myWorkRangeError && !periodWorks.length && <p className="farm-empty">선택한 기간과 상태에 해당하는 근무가 없어요.</p>}
                            <FarmCursorList items={periodWorks} queryKey={JSON.stringify([myWorkRange, filter, myWorkPeriodDirection])} renderItem={farmWorkCard} />
                          </>
                        )}
                        {tab === "마이페이지" && (
                          <FarmProfile displayName={farmMember?.name} employer={isEmployer}
                            salaryAccount={farmMember ? farmMember.salaryAccount : demoSalaryAccounts[role]}
                            onSaveSalaryAccount={(value) => {
                              if (farmMember) setFarmMembers(farmMembers.map((member) => member.id === farmMember.id ? { ...member, salaryAccount: value } : member));
                              else setDemoSalaryAccounts({ ...demoSalaryAccounts, [role]: value });
                              notify(value ? "급여 수령 계좌를 저장했어요." : "급여 수령 계좌를 삭제했어요.");
                            }}
                            metrics={isEmployer ? [
                              ["등록 공고", ownedJobs.length],
                              ["모집 중", ownedJobs.filter((job) => countOpenDays(job) > 0).length],
                              ["지원 대기", pendingApplications.length],
                            ] : [
                              ["지원한 일자리", appliedJobCount],
                              ["찜한 일자리", favoriteFarmJobs.length],
                              ["맞춤 일자리", matchedFarmJobs.length],
                            ]}
                            activeMetric={farmProfilePage}
                            onMetricSelect={isEmployer ? undefined : (index) => {
                              const pages = ["지원한 일자리", "찜한 일자리", "맞춤 일자리"] as const;
                              const page = pages[index];
                              if (page) setFarmProfilePage(page);
                            }}
                            onManage={() => nav("내 공고")} onCreate={() => nav("공고 등록")}
                            onResumeManagement={() => nav("이력서 알림")}
                            onInsurance={() => setModal("보험 앱으로 이동할까요?")}
                            onLogout={() => { setFarmLogin(false); nav("홈"); }}>
                            {!isEmployer && <FarmProfileJobs page={farmProfilePage} onPageChange={setFarmProfilePage}
                              applied={myApplications.length ? myApplications.map(farmWorkCard) : null}
                              saved={favoriteFarmJobs.length ? favoriteFarmJobs.map(farmJobCard) : null}
                              matched={matchedFarmJobs.length ? matchedFarmJobs.map(farmJobCard) : null}
                              jobs={listedJobs} preferences={farmPreferences}
                              onSavePreferences={(value) => {
                                setFarmPreferences(value);
                                setFarmProfilePage("맞춤 일자리");
                                notify("관심 조건을 저장했어요.");
                              }}
                              resume={farmResume} onSaveResume={(value) => {
                                setFarmResume(value);
                                notify("이력서를 저장했어요.");
                              }}
                              onBrowse={() => openFarmList()} />}
                          </FarmProfile>
                        )}
                      </>
                    )}
                  </>
                )
              ) : !insLogin && (["MY", "보험료 납입"].includes(tab) || (tab === "보험금 청구" && !isClaimGuide)) ? (
                loginForm(true)
              ) : isEnrolling ? (
                <>
                  <h2>{enrollmentSteps[step].title}</h2>
                  <Card>
                    {step === 0 ? (
                      <PlatformConsent consented={agreed} onChange={setAgreed} />
                    ) : step === 1 ? (
                      <IdentityFields verified={agreed}
                        onVerified={() => { setAgreed(true); notify("간편 인증 완료!"); }} />
                    ) : step === 3 ? (
                      <>
                        <h3>보험금을 받을 분을 확인해요</h3>
                        {["피보험자", "계약자", "수익자"].map((t) => (
                          <label key={t}>
                            {t}
                            <input defaultValue="김하루" />
                          </label>
                        ))}
                        <label className="check-row">
                          <input
                            type="checkbox"
                            checked={agreed}
                            onChange={(e) => setAgreed(e.target.checked)}
                          />
                          설정한 내용을 확인했습니다.
                        </label>
                      </>
                    ) : step === 9 ? (
                      <>
                        <h3>결제 방법</h3>
                        <select aria-label="결제 방법" value={enrollmentPayment}
                          onChange={(event) => {
                            setEnrollmentPayment(event.target.value as PaymentMethod);
                            setAgreed(false);
                          }}>
                          {paymentMethods.map((method) => <option key={method} value={method}>{method} (모의)</option>)}
                        </select>
                        <dl>
                          <dt>보험료 예시</dt>
                          <dd>9,900원</dd>
                        </dl>
                        <p className="fine">
                          모의 결제로 가입 화면을 체험합니다.
                        </p>
                        <label className="check-row">
                          <input
                            type="checkbox"
                            checked={agreed}
                            onChange={(e) => setAgreed(e.target.checked)}
                          />
                          모의 결제 및 가입 내용을 확인했습니다.
                        </label>
                      </>
                    ) : (
                      <>
                        <h3>{enrollmentSteps[step].title}</h3>
                        <dl>
                          <dt>가입 상품</dt>
                          <dd>{product}</dd>
                          <dt>보장 기간</dt>
                          <dd>가입일부터 30일</dd>
                          <dt>보험료 예시</dt>
                          <dd>9,900원</dd>
                          <dt>보장 단위</dt>
                          <dd>근로자별 · 확정 근무별</dd>
                        </dl>
                        <p>
                          보장 기간 중 당일 시작 전 기상 사유로 취소된 확정
                          근무에 약정 기준을 적용합니다. 농장 사정, 전날 취소,
                          조기 종료는 접수 대상에서 제외됩니다.
                        </p>
                        <p className="fine">
                          화면 검토용 약관 요약입니다. 보험료·지급 산식은 담당
                          팀 확정 후 적용됩니다.
                        </p>
                        <label className="check-row">
                          <input
                            type="checkbox"
                            checked={agreed}
                            onChange={(e) => setAgreed(e.target.checked)}
                          />
                          내용을 확인하고 동의합니다.
                        </label>
                      </>
                    )}
                  </Card>
                </>
              ) : (
                <>
                  {tab === "홈" && (
                    <div className="insurance-home">
                      <div className="insurance-page-intro home-intro">
                        <h2>안녕하세요. <strong>{insLogin ? insuranceName : "이하루"}님</strong>.</h2>
                        <button className="insurance-profile" aria-label="MY 열기" onClick={() => nav("MY")}>
                          <Mark purple />
                        </button>
                      </div>
                      <TodayWeather region={weatherRegion} onRegionChange={setWeatherRegion} />
                      <section className="insurance-hero" aria-labelledby="parametric-banner-title">
                        <h2 id="parametric-banner-title"><span className="wonday-wordmark">WON데이</span> 파라메트릭 보험</h2>
                        <p className="insurance-banner-copy">날씨에 흔들리는 농촌의 하루에, 든든한 안심을</p>
                        <button onClick={() => nav("상품")}>
                          보험 상품 보기 <span aria-hidden="true">↗</span>
                        </button>
                      </section>
                      <nav className="quick-actions" aria-label="보험 바로가기">
                        {([
                          ["보험금 청구", "claim"],
                          ["보험료 납입", "payment"],
                          ["상품", "products"],
                          ["MY", "profile"],
                        ] as const).map(([label, icon]) => (
                          <button key={label} onClick={() => nav(label)}>
                            <span aria-hidden="true"><InsuranceIcon name={icon} /></span>
                            {label}
                          </button>
                        ))}
                      </nav>
                      <section className="home-insurance-summary" aria-labelledby="home-insurance-summary-title">
                        <h3 id="home-insurance-summary-title">내 보험 현황</h3>
                        <div className="home-insurance-counts">
                          <button onClick={() => nav("MY")}>
                            <span>가입한 보험</span>
                            <strong>{insLogin ? `${policies.length}건` : "계약 조회"}</strong>
                          </button>
                          <button onClick={() => openClaimHistory("진행 중")}>
                            <span>진행 중 청구</span>
                            <strong>{insLogin ? `${inProgressClaims.length}건` : "청구 조회"}</strong>
                          </button>
                        </div>
                      </section>
                      <Card tone="home-claim-guide">
                        <h3>작업 전 취소, 어떻게 보상받나요?</h3>
                        <p>
                          당일 기상 사유로 취소된 확정 근무를 확인하고 약정한
                          기상 기준으로 심사해요.
                        </p>
                        <button
                          className="text-button"
                          onClick={() => setModal("보상 안내")}
                        >
                          보상 기준 살펴보기 →
                        </button>
                      </Card>
                    </div>
                  )}
                  {tab === "상품" && (
                    <InsuranceProducts onSelect={(name) => {
                      setProduct(name);
                      setModal("상품 상세");
                    }} />
                  )}
                  {tab === "MY" && (
                    <>
                      <div className="insurance-page-intro my-intro">
                        <span className="my-profile-mark" aria-hidden="true"><Mark purple /></span>
                        <div><h2>MY</h2><p>{account}</p></div>
                      </div>
                      {enrollmentComplete && (
                        <>
                          <h2>모의 가입이 완료됐어요</h2>
                          <EnrollmentProgress step={enrollmentSteps.length} />
                        </>
                      )}
                      <div className="section-heading">
                        <h2>계약현황</h2>
                        <span className="contract-count">계약 {policies.length}건</span>
                      </div>
                      {usingSamples && (
                        <div className="contract-empty">
                          <p>가입한 보험이 없어요. 아래에서 계약 카드 예시를 확인할 수 있어요.</p>
                          <button className="secondary" onClick={() => nav("상품")}>보험 상품 보기 ↗</button>
                        </div>
                      )}
                      {displayedPolicies.map((policy, index) => (
                        <PolicyPaymentCard
                          key={index}
                          policy={policy}
                          sample={usingSamples}
                          coverageStatus={today() >= policy.from && today() <= policy.to ? "보장 중" : "보장 종료"}
                        />
                      ))}
                      <div className="insurance-settings">
                        <button className="list-link" onClick={() => nav("보험료 납입")}>
                          <span aria-hidden="true">₩</span>
                          <b>보험료 납입</b>
                          <span aria-hidden="true">›</span>
                        </button>
                        <p>{linked ? `${agriworkName} 연동 완료` : `${agriworkName} 연동은 상품 가입 중 진행해요.`}</p>
                      </div>
                      <button
                        className="secondary"
                        onClick={() => {
                          setInsLogin(false);
                          notify("로그아웃했어요.");
                        }}
                      >
                        보험 앱 로그아웃
                      </button>
                    </>
                  )}
                  {tab === "보험료 납입" && (
                    <>
                      <button className="back" onClick={() => nav("홈")}>← 보험 홈</button>
                      <h2>보험료 납입</h2>
                      <p>{usingSamples
                        ? "예시 계약의 보험료 납입을 체험할 수 있어요."
                        : "계약별 납입 내역과 남은 횟수를 확인하세요."}</p>
                      {displayedPolicies.map((policy, index) => (
                        <PolicyPaymentCard
                          key={index}
                          policy={policy}
                          sample={usingSamples}
                          billing
                          onPay={() => { setPremiumIndex(index); setModal("모의 납입"); }}
                        />
                      ))}
                    </>
                  )}
                  {isClaimGuide && <ClaimGuide category={claimCategory} onChange={setClaimCategory} />}
                  {tab === "보험금 청구" && claimView === "history" && (
                    <>
                      <div className="insurance-page-intro claim-intro">
                        <h2>청구현황</h2>
                        <button className="outline-action" onClick={() => setModal("수동 접수")}>＋ 직접 접수</button>
                      </div>
                      <button className="back claim-guide-back" onClick={() => nav("보험금 청구")}>← 사고 유형 안내</button>
                      <p>근무별 청구 내역 {claims.length}건</p>
                      <div className="chips claim-filters" aria-label="청구 상태 필터">
                        {["전체", "진행 중", "지급 완료", "미지급"].map((status) => (
                          <button key={status} aria-pressed={filter === status}
                            className={filter === status ? "active" : ""}
                            onClick={() => setFilter(status)}>
                            {status}
                          </button>
                        ))}
                      </div>
                      {claims.length ? (
                        visibleClaims.length ? visibleClaims.map((c) => (
                          <details className="claim-record" key={c.id} open={claimId === c.id || undefined}>
                            <summary>
                              <span className="claim-record-copy">
                                <span className="claim-record-status">{c.status}</span>
                                <strong>{c.work.job.title}</strong>
                                <small>{day(c.work.date)} · {c.work.demo || c.id.startsWith("CL-DEMO-") ? "체험 내역" : c.id}</small>
                              </span>
                              <span className="claim-record-amount">
                                {c.amount !== undefined && money(c.amount)}
                                <span aria-hidden="true">›</span>
                              </span>
                            </summary>
                            <div className="claim-record-detail">
                              <dl>
                                <dt>고용주 취소 사유</dt>
                                <dd>
                                  {c.work.category} · {c.work.reason}
                                </dd>
                                <dt>취소 시각</dt>
                                <dd>{c.work.at?.replace("T", " ")}</dd>
                                <dt>보험사 판정 사유</dt>
                                <dd>
                                  {c.decision ?? "관측 자료 확보 후 심사 예정"}
                                </dd>
                                <dt>위험지수 · 기준값</dt>
                                <dd>
                                  {c.risk === undefined
                                    ? "미확정"
                                    : c.risk + " / 기준값 60 (예시)"}
                                </dd>
                                <dt>예정액 · 완료금</dt>
                                <dd>
                                  {c.amount === undefined
                                    ? "미확정"
                                    : money(c.amount)}{" "}
                                  ·{" "}
                                  {c.status === "지급 완료"
                                    ? money(c.amount ?? 0)
                                    : "0원"}
                                </dd>
                              </dl>
                              <button
                                className="secondary"
                                onClick={() => {
                                  setClaimId(c.id);
                                  setModal("보완 자료");
                                }}
                              >
                                보완 요청 및 자료 확인
                              </button>
                            </div>
                          </details>
                        )) : <Card tone="empty"><h3>이 상태의 청구 내역이 없어요</h3></Card>
                      ) : (
                        <Card tone="empty">
                          <span>◷</span>
                          <h3>접수된 보상 건이 없어요</h3>
                          <p>
                            당일 기상 취소 근무가 누락되었다면
                            <br />
                            직접 접수할 수 있어요.
                          </p>
                          <button
                            className="primary"
                            onClick={() => setModal("수동 접수")}
                          >
                            누락된 근무 확인 ↗
                          </button>
                        </Card>
                      )}
                    </>
                  )}
                </>
              )}
            </div>
            {isClaimGuide && (
              <div className="claim-guide-footer">
                <button className="secondary" onClick={() => openClaimHistory()}>청구현황 확인</button>
                <button className="primary" disabled={claimCategory !== "기후"} onClick={showClaimForm}>보험금 청구</button>
              </div>
            )}
            {isEnrolling ? (
              !showConnection && (step !== 1 || agreed) && (
                <div className="enrollment-footer">
                  <button className="primary" disabled={!agreed} onClick={advanceEnrollment}>
                    {step === 0 ? "연동하기" : step === 9 ? "모의 결제하고 가입 완료" : "확인하고 다음으로"}
                    <span aria-hidden="true">↗</span>
                  </button>
                </div>
              )
            ) : (<>
              {canApply && detail && (
              <footer className="farm-apply-footer">
                <button className="primary" disabled={!selectableDates.length} onClick={() => {
                  if (selectedDates.length) applyToJob(detail);
                  else {
                    const firstDate = farmDatesRef.current?.querySelector<HTMLButtonElement>(".farm-apply-date");
                    firstDate?.scrollIntoView({ block: "center" });
                    firstDate?.focus({ preventScroll: true });
                  }
                }}>
                  {selectedDates.length ? `${selectedDates.length}개 날짜에 지원하기`
                    : selectableDates.length ? "지원 날짜 보기" : "지원 가능한 날짜가 없어요"}
                  <FarmIcon name="arrow" />
                </button>
              </footer>
              )}
            <nav className="bottom-nav" aria-label={app === "insurance" ? "보험 메뉴" : `${agriworkName} 메뉴`}>
              {tabs.map((t, i) => (
                <button
                  key={t}
                  className={[isTabActive(t) ? "active" : "", app === "farm" && t === "홈" ? "farm-home-nav-button" : ""].filter(Boolean).join(" ")}
                  aria-current={isTabActive(t) ? "page" : undefined}
                  onClick={() => {
                    setTabScrollRequest((previous) => previous + 1);
                    if (isTabActive(t) && !detail && !work && !platformVisit && !isEnrolling) return;
                    if (app === "farm" && t === "WONDAY") setModal("보험 앱으로 이동할까요?");

                    else if (app === "farm" && t === "MY") openFarmProfile();
                    else if (app === "farm" && t === "알림") setModal("알림");
                    else nav(t);
                  }}
                >
                  <span aria-hidden="true">
                    {app === "farm" && t === "WONDAY" ? <FarmIcon name="shield" /> :
                      app === "insurance" ? <InsuranceIcon name={insuranceTabIcons[i]} /> :
                      farmTabIcons[i] === "plus" ? <FarmIcon name="plus" /> :
                      farmTabIcons[i] === "calendar" ? <FarmIcon name="calendar" /> :
                      farmTabIcons[i] === "grid" ? <FarmIcon name="grid" /> :
                      <InsuranceIcon name={farmTabIcons[i]} />}
                  </span>
                  {t}
                </button>
              ))}
            </nav>
            </>)}
          </>
        )}
        {insuranceLaunching && <div className="insurance-launch-splash" role="status" aria-live="polite">
          <img src={wooriSymbol} width={64} height={64} alt="우리금융그룹 로고" />
          <strong className="insurer-wordmark">우리 WON데이 보험</strong>
        </div>}
        <div className="home-indicator" />
        {toast && (
          <div className="toast" role="status">
            ✓ {toast}
          </div>
        )}
        {modal && (
          <div className="modal-shade" onClick={() => setModal("")}>
            <section
              className="sheet"
              role="dialog"
              aria-modal="true"
              aria-label={modal}
              onClick={(e) => e.stopPropagation()}
            >
              {modal === "지원 완료" ? <button type="button" className="farm-application-dismiss-top" aria-label="지원 완료 창 닫기" onClick={() => setModal("")}><span className="sheet-handle" /></button> : <div className="sheet-handle" />}
              <button
                className="sheet-close"
                aria-label="닫기"
                onClick={() => setModal("")}
              >
                ×
              </button>
              <h2 onClick={modal === "지원 완료" ? () => setModal("") : undefined}>{modal === "취소 확인" && cancellationTarget ? cancellationTarget.status === "지원 중" ? "지원을 취소할까요?" : "근무를 취소할까요?" : modal === "보험 앱으로 이동할까요?" ? (app === "global" ? "WONday Insurance" : "WONDAY 보험") : modal}</h2>
              {modal === "보험 앱으로 이동할까요?" ? <FarmApplicationBanner handoff english={app === "global"} onInsurance={launchInsurance} onDismiss={() => setModal("")} /> : modal === "지원 완료" ? (
                <FarmApplicationBanner onInsurance={() => { setModal(""); setApp("insurance"); nav("상품"); }} onDismiss={() => {
                  setModal("");
                  setApp("farm");
                  nav("내 근무");
                }} />
              ) : modal === "취소 확인" && cancellationTarget ? (
                <form className="farm-worker-cancel-form" onSubmit={(event) => {
                  event.preventDefault();
                  const current = works.find((entry) => entry.id === cancellationTarget.id);
                  if (!current || current.worker !== "김하루" || isEmployer) return;
                  const reason = String(new FormData(event.currentTarget).get("reason") ?? "");
                  const next = cancelWorkerWork(current, reason);
                  if (!next) return notify(workerCancellationBlockedReason(current) || "취소 사유를 입력해 주세요.");
                  setWorks(works.map((entry) => entry.id === next.id ? next : entry));
                  if (work?.id === next.id) setWork(next);
                  setModal("");
                  setCancellationTarget(null);
                  notify(next.status + "가 완료되었어요.");
                }}>
                  <p>{day(cancellationTarget.date)} · {cancellationTarget.job.title}</p>
                  <p>선택한 날짜의 본인 {cancellationTarget.status === "지원 중" ? "지원만" : "근무만"} 취소돼요.</p>
                  <label>취소 사유<textarea name="reason" required maxLength={500} placeholder="취소하는 이유를 입력해 주세요." /></label>
                  {cancellationTarget.status === "근무 확정" && <p className="fine">본인이 취소한 근무는 WONDAY 당일 기상 취소 보상 대상에 포함되지 않아요.</p>}
                  <div className="farm-cancel-confirm-actions">
                    <button type="button" className="secondary" onClick={() => setModal("")}>돌아가기</button>
                    <button type="submit" className="farm-cancel-confirm">{cancellationTarget.status === "지원 중" ? "지원 취소 확정" : "근무 취소 확정"}</button>
                  </div>
                </form>
              ) : modal === "근무 완료" && work ? (
                <div>
                  <p>{work.worker} · {day(work.date)}</p><p>{work.job.title}</p>
                  <p>이 근로자의 근무를 완료 처리할까요?</p>
                  <p className="fine">근무 상태만 변경되며 실제 급여 이체는 진행되지 않아요.</p>
                  <div className="farm-salary-actions">
                    <button type="button" className="secondary" onClick={() => setModal("")}>돌아가기</button>
                    <button type="button" className="primary" onClick={() => {
                      const current = works.find((entry) => entry.id === work.id);
                      if (!isEmployer || current?.status !== "근무 확정" || !ownedJobs.some((job) => job.id === current.job.id)) return;
                      const next = { ...current, status: "근무 완료" };
                      setWorks(works.map((entry) => entry.id === next.id ? next : entry)); setWork(next); setModal(""); notify("근무 완료로 처리했어요.");
                    }}>근무 완료 처리</button>
                  </div>
                </div>
              ) : ["근무 취소", "조기 종료"].includes(modal) && work ? (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    const at = new Intl.DateTimeFormat("sv-SE", {
                      timeZone: "Asia/Seoul",
                      year: "numeric",
                      month: "2-digit",
                      day: "2-digit",
                      hour: "2-digit",
                      minute: "2-digit",
                      hour12: false,
                    })
                      .format(new Date())
                      .replace(" ", "T");
                    if (
                      modal === "근무 취소" &&
                      at >= work.date + "T" + work.job.start
                    )
                      return notify(
                        "시작 시각 이후에는 조기 종료로 등록해 주세요.",
                      );
                    const next: Work = {
                      ...work,
                      status: modal,
                      cancelledBy: modal === "근무 취소" ? "고용주" : work.cancelledBy,
                      category: String(f.get("category")),
                      reason: String(f.get("reason")),
                      at:
                        modal === "조기 종료"
                          ? work.date + "T" + f.get("end")
                          : at,
                    };
                    setWorks(works.map((w) => (w.id === next.id ? next : w)));
                    setWork(next);
                    if (
                      modal === "근무 취소" &&
                      next.worker === "김하루" &&
                      !exclusion(next) &&
                      !claims.some((c) => c.work.id === next.id)
                    )
                      setClaims([
                        ...claims,
                        {
                          id: "CL-" + crypto.randomUUID(),
                          work: next,
                          status: "자료 대기",
                        },
                      ]);
                    setModal("");
                    notify("선택한 근로자의 변경 내용을 기록했어요.");
                  }}
                >
                  <p>
                    {work.worker} · {day(work.date)}
                  </p>
                  <label>
                    사유 구분
                    <select name="category">
                      <option>기상</option>
                      <option>농장 사정</option>
                      <option>기타</option>
                    </select>
                  </label>
                  <label>
                    상세 사유
                    <textarea name="reason" required />
                  </label>
                  {modal === "조기 종료" && (
                    <label>
                      실제 종료 시각
                      <input
                        type="time"
                        name="end"
                        min={work.job.start}
                        max={work.job.end}
                        required
                      />
                    </label>
                  )}
                  <button className="primary">{modal} 확정</button>
                </form>
              ) : modal === "일정 변경" && detail ? (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const f = new FormData(e.currentTarget);
                    const start = String(f.get("start"));
                    const end = String(f.get("end"));
                    const rs = String(f.get("rs"));
                    const re = String(f.get("re"));
                    if (!(start < rs && rs < re && re < end))
                      return notify("근무 및 휴게 구간을 확인해 주세요.");
                    const changed = {
                      ...detail,
                      start,
                      end,
                      rest: rs + "–" + re,
                      id: Math.max(0, ...jobs.map((job) => job.id)) + 1,
                      dates: selected,
                    };
                    const original = {
                      ...detail,
                      dates: detail.dates.filter((d) => !selected.includes(d)),
                    };
                    setJobs([
                      ...jobs.filter((j) => j.id !== detail.id),
                      ...(original.dates.length ? [original] : []),
                      changed,
                    ]);
                    setWorks(
                      works.map((w) =>
                        w.job.id === detail.id && selected.includes(w.date)
                          ? { ...w, job: changed }
                          : w,
                      ),
                    );
                    setDetail(changed);
                    setModal("");
                    notify("선택한 날짜의 일정이 변경되었어요.");
                  }}
                >
                  <p>{selected.map(day).join(", ")}</p>
                  {[
                    ["start", "시작", detail.start],
                    ["end", "종료", detail.end],
                    ["rs", "휴게 시작", detail.rest.split("–")[0]],
                    ["re", "휴게 종료", detail.rest.split("–")[1]],
                  ].map(([n, l, v]) => (
                    <label key={n}>
                      {l}
                      <input type="time" name={n} defaultValue={v} required />
                    </label>
                  ))}
                  <button className="primary">변경 저장</button>
                </form>
              ) : modal === "상품 상세" ? (
                <>
                  <Badge>보험료 · 지급 조건은 예시</Badge>
                  <h3>{product}</h3>
                  <p>월 9,900원 · 30일 보장</p>
                  <p>
                    당일 시작 전 기상 사유 취소에 관측자료와 가입 당시 약정
                    기준을 적용합니다. 기준값을 초과하면 지급하며 같거나 낮으면
                    미지급됩니다.
                  </p>
                  <p>
                    농장 사정·전날 취소·조기 종료는 접수 제외, 기타 사유는 처리
                    방식 협의 중입니다.
                  </p>
                  <button
                    className="primary"
                    onClick={() => {
                      setModal("");
                      if (!insLogin) {
                        nav("MY");
                        setPendingJoin(true);
                        notify("가입하려면 먼저 로그인해 주세요.");
                      } else {
                        startEnrollment();
                      }
                    }}
                  >
                    상품 가입하기 ↗
                  </button>
                </>
              ) : modal === "모의 납입" && displayedPolicies[premiumIndex] ? (
                <PremiumPaymentForm
                  policy={displayedPolicies[premiumIndex]}
                  onConfirm={(method) => payPremium(premiumIndex, method)}
                />
              ) : modal === "수동 접수" ? (
                <>
                  <p>취소된 확정 근무를 선택해 보험금을 청구할 수 있어요.</p>
                  <p className="fine">체험 근무는 예시 보험에 가입한 상태를 가정해요.</p>
                  {!linked && (
                    <button className="secondary" onClick={() => { setModal(""); nav("상품"); }}>
                      내 근무를 조회하려면 농업 계정 연결 ↗
                    </button>
                  )}
                  <div className="claim-candidates">
                    {cancelledWorks.filter((work) => linked || work.demo).map((work) => {
                      const existing = claims.some((claim) => claim.work.id === work.id);
                      const reason = existing ? "" : exclusion(work);
                      return (
                        <Card key={work.id} tone="claim-candidate">
                          <Badge>{work.demo ? "체험 근무" : "확정 후 취소"}</Badge>
                          <h3>{day(work.date)} · {work.job.title}</h3>
                          <p>{work.category} · {work.reason}</p>
                          {reason ? (
                            <div className="claim-unavailable">
                              <strong>접수 대상 제외</strong>
                              <p>{reason}</p>
                            </div>
                          ) : (
                            <button className="primary" onClick={() => receive(work)}>
                              {existing ? "기존 접수 건 확인" : "보험금 청구 접수"}
                            </button>
                          )}
                        </Card>
                      );
                    })}
                  </div>
                </>
              ) : modal === "보험 회원가입" ? (
                <form
                  onSubmit={(e) => {
                    e.preventDefault();
                    const fields = new FormData(e.currentTarget);
                    const loginId = String(fields.get("account")).trim();
                    const displayName = String(fields.get("displayName")).trim();
                    setInsLogin(true);
                    setModal("");
                    setAccount(loginId);
                    setInsuranceNames({ ...insuranceNames, [loginId]: displayName });
                    resumeInsuranceAction();
                    notify("체험 계정을 생성했어요.");
                  }}
                >
                  <label>
                    이름
                    <input name="displayName" required />
                  </label>
                  <label>
                    아이디
                    <input name="account" required />
                  </label>
                  <label>
                    비밀번호
                    <input type="password" minLength={8} required />
                  </label>
                  <label className="check-row">
                    <input type="checkbox" required />
                    체험 서비스 이용에 동의합니다.
                  </label>
                  <button className="primary">체험 계정 만들기</button>
                </form>
              ) : modal === "보완 자료" ? (
                claims.find((c) => c.id === claimId)?.status === "보완 요청" ? (
                  <form
                    onSubmit={(e) => {
                      e.preventDefault();
                      const f = new FormData(e.currentTarget);
                      const file = f.get("file") as File;
                      setClaims(
                        claims.map((c) =>
                          c.id === claimId
                            ? {
                                ...c,
                                status: "심사 중",
                                supplement: file.name,
                                decision:
                                  String(f.get("message")) +
                                  " · 보완 자료 제출 완료",
                              }
                            : c,
                        ),
                      );
                      setModal("");
                      notify("보완 자료를 체험 내역에 반영했어요.");
                    }}
                  >
                    <p>{claims.find((c) => c.id === claimId)?.decision}</p>
                    <label>
                      첨부 자료
                      <input
                        type="file"
                        name="file"
                        accept="image/*,.pdf"
                        required
                      />
                    </label>
                    <label>
                      보완 설명
                      <textarea name="message" required />
                    </label>
                    <p className="fine">
                      체험 내역에는 파일명과 설명이 저장됩니다.
                    </p>
                    <button className="primary">자료 제출 체험</button>
                  </form>
                ) : (
                  <p>현재 보완 요청이 없습니다.</p>
                )
              ) : (
                <p>
                  {modal === "보완 자료"
                    ? "현재 보완 요청이 없습니다. 요청을 받으면 해당 건에 자료를 추가할 수 있습니다."
                    : modal === "알림"
                      ? "새 알림이 없어요. 지원 및 근무 변경은 내 근무에서 확인하세요."
                      : `${agriworkName}에서 날짜별 일을 찾고, WONDAY 보험에서 보장과 보상을 확인하세요. 당일 시작 전 기상 사유 취소는 약정 기준에 따라 심사합니다. 데이터와 인증·결제는 화면 체험용입니다.`}
                </p>
              )}
            </section>
          </div>
        )}
      </main>
      <div className="studio-caption">
        <span /> YOUR DAY, WELL PROTECTED.
      </div>
    </div>
  );
}
export default App;

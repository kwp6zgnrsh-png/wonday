import { useState } from "react";
import "./App.css";

type Claim = {
  id: string;
  name: string;
  avatar: string;
  place: string;
  job: string;
  date: string;
  received: string;
  source: string;
  status: string;
  risk: number | null;
  amount: number;
  reason: string;
  history: string[];
};
const seed: Claim[] = [
  {
    id: "CL-2026-0148",
    name: "김하루",
    avatar: "하",
    place: "경기 이천시",
    job: "사과 수확",
    date: "2026-11-23",
    received: "2026-11-23 07:32",
    source: "자동 접수",
    status: "승인 대기",
    risk: 82,
    amount: 72000,
    reason: "강수로 인한 작업 취소",
    history: [
      "07:15 고용주 기상 사유 취소",
      "07:32 변경 기록 조회 · 자동 접수",
      "07:35 관측 자료 수집 완료",
      "07:36 위험지수 분석 완료 · 승인 대기",
    ],
  },
  {
    id: "CL-2026-0147",
    name: "이서연",
    avatar: "서",
    place: "경기 평택시",
    job: "딸기 수확",
    date: "2026-11-23",
    received: "2026-11-23 07:28",
    source: "자동 접수",
    status: "자료 대기",
    risk: null,
    amount: 0,
    reason: "강풍으로 작업 취소",
    history: [
      "07:10 고용주 기상 사유 취소",
      "07:28 변경 기록 조회 · 자동 접수",
      "07:30 관측 자료 대기",
    ],
  },
  {
    id: "CL-2026-0146",
    name: "박민수",
    avatar: "민",
    place: "경기 양평군",
    job: "배추 정식",
    date: "2026-11-23",
    received: "2026-11-23 07:20",
    source: "수동 접수",
    status: "보완 요청",
    risk: 76,
    amount: 72000,
    reason: "폭우로 인한 작업 취소",
    history: [
      "06:50 고용주 기상 사유 취소",
      "07:20 근로자 수동 접수",
      "07:40 작업지 확인 자료 보완 요청",
    ],
  },
  {
    id: "CL-2026-0145",
    name: "최지우",
    avatar: "지",
    place: "경기 안성시",
    job: "사과 수확",
    date: "2026-11-22",
    received: "2026-11-22 07:05",
    source: "자동 접수",
    status: "지급 완료",
    risk: 93,
    amount: 120000,
    reason: "기상 악화로 수확 취소",
    history: ["07:05 자동 접수", "08:00 지급 승인", "08:10 모의 지급 완료"],
  },
  {
    id: "CL-2026-0144",
    name: "정도윤",
    avatar: "도",
    place: "경기 여주시",
    job: "농약 살포",
    date: "2026-11-22",
    received: "2026-11-22 06:54",
    source: "수동 접수",
    status: "미지급",
    risk: 43,
    amount: 0,
    reason: "비 예보로 작업 취소",
    history: [
      "06:54 수동 접수",
      "07:10 관측자료 분석",
      "07:20 계약 기준 미충족 · 미지급",
    ],
  },
];
const previousDemoPlaces: Partial<Record<string, string>> = {
  "CL-2026-0148": "경북 영주시",
  "CL-2026-0147": "충남 논산시",
  "CL-2026-0146": "전남 해남군",
  "CL-2026-0145": "경북 안동시",
  "CL-2026-0144": "전북 완주군",
};

const currency = (n: number) => n.toLocaleString("ko-KR") + "원";
function App() {
  const [page, setPage] = useState("대시보드");
  const [rows, setRows] = useState<Claim[]>(() => {
    try {
      const saved = JSON.parse(localStorage.getItem("onday-admin-claims") || "null") as Claim[] | null;
      return saved?.map((claim) => {
        const example = seed.find((item) => item.id === claim.id);
        return example && claim.place === previousDemoPlaces[claim.id]
          ? { ...claim, place: example.place }
          : claim;
      }) ?? seed;
    } catch {
      return seed;
    }
  });
  const [selected, setSelected] = useState<string | null>("CL-2026-0148");
  const [filter, setFilter] = useState("전체");
  const [query, setQuery] = useState("");
  const [period, setPeriod] = useState("전체 기간");
  const [modal, setModal] = useState("");
  const [notice, setNotice] = useState("");
  const [logged, setLogged] = useState(true);
  const [section, setSection] = useState("판정 근거");
  const current = rows.find((c) => c.id === selected);
  const shown = rows.filter(
    (c) =>
      (filter === "전체" || c.status === filter) &&
      (period === "전체 기간" || c.date === "2026-11-23") &&
      (c.id + c.name + c.place).includes(query),
  );
  function notify(s: string) {
    setNotice(s);
    window.setTimeout(() => setNotice(""), 4000);
  }
  function update(status: string, reason: string) {
    if (!current) return;
    const next = rows.map((c) =>
      c.id === current.id
        ? {
            ...c,
            status,
            amount: status === "미지급" ? 0 : c.amount,
            history: [
              ...c.history,
              new Date().toLocaleTimeString("ko-KR", {
                timeZone: "Asia/Seoul",
              }) +
                " " +
                status +
                " · " +
                reason,
            ],
          }
        : c,
    );
    setRows(next);
    localStorage.setItem("onday-admin-claims", JSON.stringify(next));
    setModal("");
    notify(status + " 처리를 저장했어요.");
  }
  function changePage(p: string) {
    setPage(p);
    setSelected(null);
    setFilter("전체");
    setQuery("");
  }
  if (!logged)
    return (
      <div className="login">
        <span className="brand-symbol">✳</span>
        <h1>우리손해보험 WONDAY</h1>
        <p>보험사 관리자 체험 화면</p>
        <form
          onSubmit={(e) => {
            e.preventDefault();
            setLogged(true);
          }}
        >
          <label>
            관리자 아이디
            <input defaultValue="admin.demo" required />
          </label>
          <label>
            비밀번호
            <input type="password" defaultValue="onday2026" required />
          </label>
          <button className="solid">로그인 ↗</button>
        </form>
      </div>
    );
  return (
    <div className="admin-shell">
      <aside className="sidebar">
        <a
          href="#"
          className="brand"
          onClick={(e) => {
            e.preventDefault();
            changePage("대시보드");
          }}
        >
          <span className="brand-symbol">✳</span>
          <b>
            WONDAY<span>partners</span>
          </b>
        </a>
        <div className="workspace">
          <span className="workspace-icon">O</span>
          <div>
            <b>우리손해보험</b>
            <small>보험사 관리 워크스페이스</small>
          </div>
          <span>⌄</span>
        </div>
        <p className="nav-label">WORKSPACE</p>
        <nav>
          {[
            ["대시보드", "◫"],
            ["보상 관리", "▦"],
            ["계약 관리", "▤"],
            ["처리 이력", "◷"],
          ].map(([p, icon]) => (
            <button
              key={p}
              className={page === p ? "active" : ""}
              onClick={() => changePage(p)}
            >
              <span>{icon}</span>
              {p}
              {p === "보상 관리" && (
                <em>
                  {
                    rows.filter(
                      (c) => !["지급 완료", "미지급"].includes(c.status),
                    ).length
                  }
                </em>
              )}
            </button>
          ))}
        </nav>
        <div className="sidebar-bottom">
          <div className="help-card">
            <span>✦</span>
            <h3>함께 만드는 안심</h3>
            <p>
              보상 한 건에 담긴
              <br />
              농업인의 하루를 지켜요.
            </p>
            <button onClick={() => setModal("이용 안내")}>업무 가이드 ↗</button>
          </div>
          <button className="admin-profile" onClick={() => setModal("내 계정")}>
            <span className="avatar">윤</span>
            <div>
              <b>윤지안 매니저</b>
              <small>보상심사팀</small>
            </div>
            <span>⋯</span>
          </button>
        </div>
      </aside>
      <div className="admin-main">
        <header className="topbar">
          <div>
            워크스페이스 <span>/</span> <b>{page}</b>
          </div>
          <div className="topbar-right">
            <span className="demo-label">체험 데이터</span>
            <button aria-label="알림" onClick={() => setModal("알림")}>
              ♧<i />
            </button>
            <span className="divider" />
            <span className="avatar small">윤</span>
          </div>
        </header>
        <main className="content">
          <div className="page-heading">
            <div>
              <p className="eyebrow">WONDAY INSURANCE WORKSPACE</p>
              <h1>
                {page === "대시보드" ? "오늘도, 든든한 하루를 만들어요." : page}
              </h1>
              <p>
                {page === "대시보드"
                  ? "접수부터 지급까지, 오늘의 보상 현황을 확인하세요."
                  : "계약과 근무 기록을 확인하고 처리 현황을 관리하세요."}
              </p>
            </div>
            <div className="heading-tools">
              <span>2026. 11. 23 월요일</span>
              <button
                className="outline"
                onClick={() =>
                  notify(
                    "브라우저에 저장된 최신 체험 데이터를 표시하고 있어요.",
                  )
                }
              >
                ↻ 새로고침
              </button>
            </div>
          </div>
          {page === "대시보드" && (
            <>
              <div className="overview">
                <section className="welcome-card">
                  <div>
                    <span>EVERY DAY, WELL PROTECTED</span>
                    <h2>
                      안심을 잇는 일,
                      <br />
                      WONDAY와 함께.
                    </h2>
                    <p>오늘 접수된 보상 건을 살펴보세요.</p>
                    <button onClick={() => changePage("보상 관리")}>
                      보상 관리로 이동 <b>↗</b>
                    </button>
                  </div>
                  <span className="welcome-flower">✳</span>
                </section>
                <div className="metric">
                  <div>
                    <p>전체 보상 건</p>
                    <span>↗</span>
                  </div>
                  <b>
                    {rows.length.toString().padStart(2, "0")}
                    <small>건</small>
                  </b>
                  <p>
                    <i className="green-dot" /> 전체 접수 현황
                  </p>
                </div>
                <div className="metric lavender">
                  <div>
                    <p>확인이 필요한 건</p>
                    <span>↗</span>
                  </div>
                  <b>
                    {rows
                      .filter((c) =>
                        ["승인 대기", "보완 요청", "자료 대기"].includes(
                          c.status,
                        ),
                      )
                      .length.toString()
                      .padStart(2, "0")}
                    <small>건</small>
                  </b>
                  <p>자료 대기 · 보완 · 승인 대기</p>
                </div>
              </div>
              <div className="summary-strip">
                <div>
                  <span className="mini-icon">◷</span>
                  <p>
                    자료 대기{" "}
                    <b>
                      {rows.filter((c) => c.status === "자료 대기").length}건
                    </b>
                  </p>
                </div>
                <div>
                  <span className="mini-icon lilac">▤</span>
                  <p>
                    보완 요청{" "}
                    <b>
                      {rows.filter((c) => c.status === "보완 요청").length}건
                    </b>
                  </p>
                </div>
                <div>
                  <span className="mini-icon peach">✓</span>
                  <p>
                    승인 대기{" "}
                    <b>
                      {rows.filter((c) => c.status === "승인 대기").length}건
                    </b>
                  </p>
                </div>
                <div>
                  <span className="mini-icon">↗</span>
                  <p>
                    모의 지급 완료{" "}
                    <b>
                      {currency(
                        rows
                          .filter((c) => c.status === "지급 완료")
                          .reduce((s, c) => s + c.amount, 0),
                      )}
                    </b>
                  </p>
                </div>
              </div>
            </>
          )}
          {page === "계약 관리" ? (
            <section className="panel">
              <div className="panel-heading">
                <h2>가입 계약</h2>
                <span>예시 계약 5건</span>
              </div>
              <input
                className="search"
                placeholder="계약자 이름 검색"
                value={query}
                onChange={(e) => setQuery(e.target.value)}
              />
              <div className="table-wrap">
                <table>
                  <thead>
                    <tr>
                      <th>계약 번호</th>
                      <th>계약자</th>
                      <th>가입 상품</th>
                      <th>보장 기간 (KST)</th>
                      <th>적용 조건</th>
                      <th>상태</th>
                    </tr>
                  </thead>
                  <tbody>
                    {rows
                      .filter((c) => c.name.includes(query))
                      .map((c, i) => (
                        <tr
                          key={c.id}
                          onClick={() => {
                            setSelected(c.id);
                            setModal("계약 상세");
                          }}
                        >
                          <td>PL-2026-00{i + 1}</td>
                          <td>{c.name}</td>
                          <td>든든한 하루</td>
                          <td>2026.11.01 – 2026.11.30</td>
                          <td>체험 약관 v1</td>
                          <td>
                            <span className="status 지급완료">보장 중</span>
                          </td>
                        </tr>
                      ))}
                  </tbody>
                </table>
              </div>
            </section>
          ) : page === "처리 이력" ? (
            <section className="panel">
              <div className="panel-heading">
                <h2>보상 처리 이력</h2>
                <span>접수 · 심사 · 지급</span>
              </div>
              {rows.map((c) => (
                <div className="history-row" key={c.id}>
                  <b>
                    {c.id} · {c.name}
                  </b>
                  <p>{c.history.join(" → ")}</p>
                </div>
              ))}
            </section>
          ) : (
            <div className={"claims-layout " + (current ? "has-detail" : "")}>
              <section className="panel claims-panel">
                <div className="panel-heading">
                  <div>
                    <h2>
                      보상 접수 현황 <span>{rows.length}</span>
                    </h2>
                    <p>근무별 보상 건의 진행 상태를 확인하세요.</p>
                  </div>
                  <button
                    className="more"
                    aria-label="보상 관리 열기"
                    onClick={() => changePage("보상 관리")}
                  >
                    ↗
                  </button>
                </div>
                <div className="filters">
                  <div className="filter-tabs">
                    {[
                      "전체",
                      "승인 대기",
                      "자료 대기",
                      "보완 요청",
                      "지급 완료",
                      "미지급",
                    ].map((f) => (
                      <button
                        key={f}
                        className={filter === f ? "active" : ""}
                        onClick={() => setFilter(f)}
                      >
                        {f}
                      </button>
                    ))}
                  </div>
                  <div className="filter-inputs">
                    <input
                      className="search"
                      placeholder="⌕ 이름, 접수번호 검색"
                      value={query}
                      onChange={(e) => setQuery(e.target.value)}
                    />
                    <select
                      aria-label="조회 기간"
                      value={period}
                      onChange={(e) => setPeriod(e.target.value)}
                    >
                      <option>전체 기간</option>
                      <option>오늘</option>
                    </select>
                  </div>
                </div>
                <div className="table-wrap">
                  <table>
                    <thead>
                      <tr>
                        <th>근로자 / 접수번호</th>
                        <th>근무일 · 작업지</th>
                        <th>접수 경로</th>
                        <th>상태</th>
                        <th>지급 예정액</th>
                        <th />
                      </tr>
                    </thead>
                    <tbody>
                      {shown.map((c) => (
                        <tr
                          key={c.id}
                          className={selected === c.id ? "selected" : ""}
                          onClick={() => {
                            setSelected(c.id);
                            setSection("판정 근거");
                          }}
                        >
                          <td>
                            <button
                              className="person-cell"
                              onClick={() => {
                                setSelected(c.id);
                                setSection("판정 근거");
                              }}
                            >
                              <span className={"avatar avatar-" + c.avatar}>
                                {c.avatar}
                              </span>
                              <span>
                                <b>{c.name}</b>
                                <small>{c.id}</small>
                              </span>
                            </button>
                          </td>
                          <td>
                            <b>{c.date.slice(5).replace("-", ". ")}</b>
                            <small>{c.place}</small>
                          </td>
                          <td>
                            <span className="source">{c.source}</span>
                          </td>
                          <td>
                            <span
                              className={"status " + c.status.replace(" ", "")}
                            >
                              {c.status}
                            </span>
                          </td>
                          <td className="amount">
                            {c.risk === null ? "미확정" : currency(c.amount)}
                          </td>
                          <td>↗</td>
                        </tr>
                      ))}
                    </tbody>
                  </table>
                  {!shown.length && (
                    <div className="empty">조건에 맞는 보상 건이 없습니다.</div>
                  )}
                </div>
                <div className="table-footer">
                  <span>총 {shown.length}건 · 체험 데이터</span>
                  <div>
                    <button aria-label="이전 페이지" disabled>
                      ←
                    </button>
                    <button className="page-number">1</button>
                    <button aria-label="다음 페이지" disabled>
                      →
                    </button>
                  </div>
                </div>
              </section>
              {current && (
                <aside className="panel detail-panel">
                  <div className="detail-heading">
                    <div>
                      <p>CLAIM DETAILS</p>
                      <h2>보상 건 상세</h2>
                    </div>
                    <button
                      aria-label="상세 닫기"
                      onClick={() => setSelected(null)}
                    >
                      ×
                    </button>
                  </div>
                  <div className="detail-person">
                    <span className="avatar">{current.avatar}</span>
                    <div>
                      <h3>{current.name}</h3>
                      <small>{current.id}</small>
                    </div>
                    <span
                      className={"status " + current.status.replace(" ", "")}
                    >
                      {current.status}
                    </span>
                  </div>
                  <div className="detail-tabs">
                    {["판정 근거", "근무 기록", "처리 이력"].map((s) => (
                      <button
                        className={section === s ? "active" : ""}
                        onClick={() => setSection(s)}
                        key={s}
                      >
                        {s}
                      </button>
                    ))}
                  </div>
                  {section === "판정 근거" ? (
                    <>
                      <div className="risk-card">
                        <div>
                          <p>기상 복합위험지수</p>
                          <span>체험 산식 v1 ↗</span>
                        </div>
                        <b>
                          {current.risk ?? "—"}
                          <small>/ 100</small>
                        </b>
                        <div className="risk-track">
                          <span style={{ width: (current.risk ?? 0) + "%" }} />
                          <i />
                        </div>
                        <div className="risk-labels">
                          <span>0</span>
                          <span>기준값 60</span>
                          <span>100</span>
                        </div>
                        <p>
                          {current.risk === null
                            ? "관측 자료 확보 후 판정합니다."
                            : current.risk > 60
                              ? "약정 기준값을 초과한 예시입니다."
                              : "약정 기준값 이하인 예시입니다."}
                        </p>
                      </div>
                      <dl>
                        <dt>고용주 취소 사유</dt>
                        <dd>기상 · {current.reason}</dd>
                        <dt>보험사 판정 사유</dt>
                        <dd>
                          {current.risk === null
                            ? "관측 자료 대기"
                            : current.risk > 60
                              ? "기상지수 기준 초과"
                              : "기상지수 기준 미충족"}
                        </dd>
                        <dt>관측 지점</dt>
                        <dd>{current.place} 인근 관측소 (예시)</dd>
                        <dt>관측 기간</dt>
                        <dd>{current.date} 08:00–17:00</dd>
                        <dt>관측값 예시</dt>
                        <dd>
                          {current.risk === null
                            ? "자료 대기"
                            : "강수 24mm · 풍속 8m/s"}
                        </dd>
                        <dt>지급률 예시</dt>
                        <dd>
                          {current.risk === null
                            ? "미확정"
                            : current.risk >= 90
                              ? "100%"
                              : current.risk > 60
                                ? "60%"
                                : "0%"}
                        </dd>
                      </dl>
                      <div className="payout">
                        <span>
                          {current.status === "지급 완료"
                            ? "모의 지급 완료 금액"
                            : "지급 예정액"}
                        </span>
                        <b>
                          {current.risk === null
                            ? "미확정"
                            : currency(current.amount)}
                        </b>
                      </div>
                    </>
                  ) : section === "근무 기록" ? (
                    <>
                      <h3 className="record-title">확정 당시 조건</h3>
                      <dl>
                        <dt>근무 식별자</dt>
                        <dd>AS-{current.id.slice(-4)}</dd>
                        <dt>작업</dt>
                        <dd>{current.job}</dd>
                        <dt>작업지</dt>
                        <dd>{current.place}</dd>
                        <dt>근무일</dt>
                        <dd>{current.date} (GMT+09:00)</dd>
                        <dt>예정 근무</dt>
                        <dd>08:00–17:00</dd>
                        <dt>무급 휴게 구간</dt>
                        <dd>12:00–13:00</dd>
                        <dt>약정 일급 예시</dt>
                        <dd>120,000원</dd>
                        <dt>취소 확정 시각</dt>
                        <dd>{current.date} 07:15 KST</dd>
                        <dt>취소 사유</dt>
                        <dd>기상 · {current.reason}</dd>
                        <dt>접수 경로</dt>
                        <dd>{current.source}</dd>
                      </dl>
                    </>
                  ) : (
                    <ol className="timeline">
                      {current.history.map((h, i) => (
                        <li key={i}>
                          <span />
                          <p>{h}</p>
                        </li>
                      ))}
                    </ol>
                  )}
                  <div className="detail-actions">
                    {!["지급 완료", "미지급"].includes(current.status) && (
                      <>
                        <button
                          className="outline"
                          onClick={() => setModal("보완 요청")}
                        >
                          보완 요청
                        </button>
                        <button
                          className="outline"
                          onClick={() => setModal("미지급 처리")}
                        >
                          미지급
                        </button>
                        <button
                          className="solid"
                          disabled={current.risk === null || current.risk <= 60}
                          onClick={() =>
                            setModal(
                              current.status === "지급 대상"
                                ? "모의 지급"
                                : "지급 승인",
                            )
                          }
                        >
                          {current.status === "지급 대상"
                            ? "모의 지급 ↗"
                            : "지급 승인 ↗"}
                        </button>
                      </>
                    )}
                    {current.status === "지급 완료" && (
                      <p className="complete">
                        ✓ 모의 지급 완료 · {current.id}-PAY
                      </p>
                    )}
                  </div>
                  <p className="detail-note">
                    승인·모의 지급은 1차 범위 협의를 위한 체험 기능입니다.
                  </p>
                </aside>
              )}
            </div>
          )}
          <footer className="content-footer">
            <span>© 2026 WONDAY. All rights reserved.</span>
            <span>
              <i className="green-dot" /> 체험 모드 · 실제 지급 연동 전
            </span>
          </footer>
        </main>
      </div>
      {notice && (
        <div className="toast" role="status">
          ✓ {notice}
        </div>
      )}
      {modal && (
        <div className="modal-overlay" onClick={() => setModal("")}>
          <section
            className="modal"
            role="dialog"
            aria-modal="true"
            aria-label={modal}
            onClick={(e) => e.stopPropagation()}
          >
            <button
              className="close-modal"
              aria-label="닫기"
              onClick={() => setModal("")}
            >
              ×
            </button>
            <span className="brand-symbol">✳</span>
            <h2>{modal}</h2>
            {["보완 요청", "미지급 처리", "지급 승인", "모의 지급"].includes(
              modal,
            ) ? (
              <form
                onSubmit={(e) => {
                  e.preventDefault();
                  const reason = String(
                    new FormData(e.currentTarget).get("reason"),
                  );
                  update(
                    modal === "미지급 처리"
                      ? "미지급"
                      : modal === "지급 승인"
                        ? "지급 대상"
                        : modal === "모의 지급"
                          ? "지급 완료"
                          : "보완 요청",
                    reason,
                  );
                }}
              >
                <p>
                  {current?.id} · {current?.name}
                </p>
                <label>
                  {modal === "보완 요청"
                    ? "근로자에게 요청할 자료"
                    : "처리 사유"}
                  <textarea
                    name="reason"
                    required
                    placeholder="판정 근거와 안내 내용을 작성해 주세요."
                  />
                </label>
                {modal === "모의 지급" && (
                  <p>모의 지급 금액 {currency(current?.amount ?? 0)}</p>
                )}
                <button className="solid">{modal} 확정</button>
              </form>
            ) : modal === "계약 상세" ? (
              <>
                <h3>{current?.name} · 든든한 하루</h3>
                <dl>
                  <dt>보장 기간</dt>
                  <dd>2026.11.01 00:00 – 11.30 23:59 KST</dd>
                  <dt>약관 버전</dt>
                  <dd>v1 (예시)</dd>
                  <dt>보험료 예시</dt>
                  <dd>9,900원</dd>
                  <dt>보장 조건</dt>
                  <dd>당일 시작 전 기상 취소 · 기준 초과</dd>
                </dl>
                <p>상품 단위 가입 · 여러 확정 근무에 조건 적용</p>
              </>
            ) : modal === "내 계정" ? (
              <>
                <p>윤지안 · 보상심사팀 관리자 체험 계정</p>
                <button
                  className="solid"
                  onClick={() => {
                    setLogged(false);
                    setModal("");
                  }}
                >
                  로그아웃
                </button>
              </>
            ) : (
              <p>
                {modal === "알림"
                  ? "자료 대기·보완 요청·승인 대기 건을 보상 관리에서 확인하세요."
                  : "근무 기록과 기상 관측 근거를 확인한 후 보완·승인·미지급을 기록하세요. 화면의 계약, 위험지수, 기준값과 지급액은 예시이며 담당 팀 확정 후 실제 연동합니다."}
              </p>
            )}
          </section>
        </div>
      )}
    </div>
  );
}
export default App;

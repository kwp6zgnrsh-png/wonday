// farm-work-selection.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 가짜이며, 상태를 가진 가짜 서버가 WORK-02·03·04 응답을 흉내 낸다.
// 실제 브라우저·Spring 서버·세션·CSRF 검증·Security 체인·DB는 없다. 가짜 서버의 판단은 실제 API 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-28-work-02-selection/work-selection-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(path.resolve(here, '../../../src/main/resources/demo/farm-work-selection.html'), 'utf8');
const SCRIPT = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const TOKEN = 'csrf-token-SECRET-xyz';
const EMPLOYER = { id: 10, name: '가상 고용주', role: 'EMPLOYER' };
const WORKER = { id: 123, name: '가상 근로자', role: 'WORKER' };
const P = 4201;
const settle = async () => { for (let i = 0; i < 40; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));

function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, type: '', className: '', colSpan: 1 }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {} })); }
    replaceChildren(...items) { this.children = []; this.append(...items); }
    append(...items) { for (const item of items) { item.parent = this; this.children.push(item); } }
    prepend(item) { item.parent = this; this.children.unshift(item); }
    remove() { this.parent.children = this.parent.children.filter(x => x !== this); }
    get lastElementChild() { return this.children.at(-1); }
    get value() { return this._value; }
    set value(v) { this._value = String(v); }
    querySelectorAll(selector) {
      const found = [];
      const walk = el => { for (const child of el.children) { if (child.tagName === selector) found.push(child); walk(child); } };
      walk(this); return found;
    }
    get allText() { return [this.textContent, this._value, ...this.children.map(c => c.allText)].join(' '); }
  }
  for (const name of ['innerHTML', 'outerHTML']) {
    Object.defineProperty(El.prototype, name, { get() { forbidden.push(name); return ''; }, set() { forbidden.push(name); } });
  }
  El.prototype.insertAdjacentHTML = function () { forbidden.push('insertAdjacentHTML'); };
  return { El };
}
const trap = (forbidden, name) => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });

const cond = (over = {}) => ({ availability: 'AVAILABLE', termsVersion: 1, workDate: '2026-12-05', timeZone: 'GMT+9', workLocation: '가상 작업지',
  latitude: 37, longitude: 127, cropCode: 'FC03030100030103', cropName: '콩(일반)', workTypeCode: '0003', workTypeName: '경운',
  description: '가상 내용', headcount: 1, dailyWage: 120000, plannedStartAt: '2026-12-05T09:00:00', plannedEndAt: '2026-12-05T17:00:00',
  breakStartAt: '2026-12-05T12:00:00', breakEndAt: '2026-12-05T13:00:00', unavailableFields: [], ...over });

// 가상 사례: 정상(v1)·조건 변경(지원 v1→최신 v2)·이전 버전(v2)·마감·정원 충족
function fakeServer() {
  const s = {
    account: EMPLOYER, prepared: 0, latest: { 1001: 1, 1002: 2, 1003: 2, 1005: 1, 1011: 1 },
    applied: { 1001: 1, 1002: 1, 1003: 2, 1005: 1, 1011: 1 }, acks: {}, assignments: {}, posts: [],
    cases: [
      { scenario: 'NORMAL', label: '정상 선정·같은 선정 재요청', id: 1001, s: 301, status: 'OPEN' },
      { scenario: 'CHANGED', label: '변경 조건 재확인 후 선정', id: 1002, s: 302, status: 'OPEN' },
      { scenario: 'STALE_VERSION', label: '이전 버전 요청 409', id: 1003, s: 303, status: 'OPEN' },
      { scenario: 'CLOSED', label: '마감 상태 409', id: 1005, s: 305, status: 'CLOSED' },
      { scenario: 'CAPACITY_FULL', label: '정원 충족 409', id: 1011, s: 311, status: 'OPEN' }]
  };
  s.caseRows = () => s.cases.map(c => ({ scenario: c.scenario, label: c.label, jobPostingId: P, workScheduleId: c.s, applicationId: c.id,
    workerId: 123, workerName: WORKER.name, employerId: 10, employerName: EMPLOYER.name, workDate: '2026-12-05', recruitmentStatus: c.status,
    termsVersion: s.latest[c.id], appliedTermsVersion: s.applied[c.id], applicationStatus: s.assignments[c.id] ? 'SELECTED' : 'PENDING',
    assignmentId: s.assignments[c.id]?.assignmentId ?? null, workStatus: s.assignments[c.id] ? 'CONFIRMED' : 'PENDING' }));
  s.confirmation = id => {
    const ack = s.acks[id], applied = s.applied[id], latest = s.latest[id];
    if (applied === latest) return { source: 'INITIAL_APPLICATION', confirmedTermsVersion: applied, latestTermsConfirmed: true, acknowledgementId: null, acknowledgedTermsVersion: null, acknowledgedAt: null };
    if (ack) return { source: 'RECONFIRMATION', confirmedTermsVersion: ack.v, latestTermsConfirmed: ack.v === latest, acknowledgementId: ack.id, acknowledgedTermsVersion: ack.v, acknowledgedAt: ack.at };
    return { source: 'INITIAL_APPLICATION', confirmedTermsVersion: applied, latestTermsConfirmed: false, acknowledgementId: null, acknowledgedTermsVersion: null, acknowledgedAt: null };
  };
  s.detail = id => ({ applicationId: id, appliedTermsVersion: s.applied[id], latestTermsVersion: s.latest[id],
    appliedConditions: cond({ termsVersion: s.applied[id] }),
    latestConditions: cond(s.latest[id] > s.applied[id] ? { termsVersion: s.latest[id], plannedStartAt: '2026-12-05T10:00:00', plannedEndAt: '2026-12-05T18:00:00' } : {}),
    changedFields: s.latest[id] > s.applied[id] ? ['plannedStartAt', 'plannedEndAt'] : [], unavailableComparisonFields: [], comparisonComplete: true,
    conditionComparisonStatus: s.latest[id] > s.applied[id] ? 'CHANGED' : 'UNCHANGED', conditionConfirmation: s.confirmation(id),
    assignment: s.assignments[id] ? { assignmentId: s.assignments[id].assignmentId, confirmedAt: s.assignments[id].confirmedAt, completedAt: null, change: null } : null });
  const conflict = (code, message) => ({ status: 409, body: { code, message } });
  s.handle = (url, options) => {
    const method = options.method || 'GET';
    if (url === '/api/auth/me') return s.account ? { status: 200, body: s.account } : { status: 401 };
    if (url === '/api/auth/csrf') return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: TOKEN } };
    const csrfOk = options.headers?.['X-CSRF-TOKEN'] === TOKEN;
    if (url === '/demo/api/work-selection/fixtures') {
      if (method === 'GET') return { status: 200, body: { created: false, preparedJobPostingId: null, cases: s.caseRows() } };
      if (!csrfOk) return { status: 403 };
      s.prepared++;
      return { status: s.prepared === 1 ? 201 : 200, body: { created: s.prepared === 1, preparedJobPostingId: P, cases: s.caseRows() } };
    }
    let m = url.match(/^\/api\/job-postings\/4201\/schedules\/\d+\/applications\/(\d+)$/) || url.match(/^\/api\/me\/applications\/(\d+)$/);
    if (m && method === 'GET') return { status: 200, body: s.detail(Number(m[1])) };
    m = url.match(/^\/api\/job-postings\/4201\/schedules\/(\d+)\/applications\/(\d+)\/selection$/);
    if (m && method === 'POST') {
      if (!csrfOk || options.headers['Content-Type'] !== 'application/json') return { status: 403 };
      const id = Number(m[2]), v = JSON.parse(options.body).termsVersion;
      s.posts.push({ url, body: JSON.parse(options.body) });
      const base = { applicationId: id, workScheduleId: Number(m[1]), workerId: 123, status: 'SELECTED' };
      if (s.assignments[id]) return { status: 200, body: { ...base, ...s.assignments[id], workStatus: 'CONFIRMED', completedAt: null } };
      if (id === 1005) return conflict('SELECTION_NOT_ALLOWED', '모집 중 또는 일시중단인 일정에서만 처리할 수 있습니다.');
      if (v !== s.latest[id]) return conflict('TERMS_VERSION_CONFLICT', '근무 조건이 변경되었습니다.');
      if (s.applied[id] !== s.latest[id] && s.acks[id]?.v !== s.latest[id]) return conflict('CONDITION_RECONFIRMATION_REQUIRED', '근로자가 최신 근무 조건을 재확인한 뒤 선정할 수 있습니다.');
      if (id === 1011) return conflict('CAPACITY_FULL', '확정 인원이 정원을 채웠습니다.');
      s.assignments[id] = { assignmentId: 7000 + id % 100, agreedTermsVersion: v, confirmedAt: '2026-11-28T15:00:00.123456+09:00' };
      return { status: 201, body: { ...base, ...s.assignments[id], workStatus: 'CONFIRMED', completedAt: null } };
    }
    m = url.match(/^\/api\/me\/applications\/(\d+)\/condition-confirmation$/);
    if (m && method === 'POST') {
      if (!csrfOk || options.headers['Content-Type'] !== 'application/json') return { status: 403 };
      const id = Number(m[1]), v = JSON.parse(options.body).termsVersion;
      s.posts.push({ url, body: JSON.parse(options.body) });
      if (s.assignments[id]) return conflict('APPLICATION_NOT_PENDING', '본인의 현재 회차 지원 대기 건만 재확인할 수 있습니다.');
      if (v !== s.latest[id]) return conflict('TERMS_VERSION_CONFLICT', '근무 조건이 변경되었습니다.');
      if (s.applied[id] !== v) s.acks[id] ??= { id: 9001, v, at: '2026-11-28T14:00:00.000001+09:00' };
      return { status: 200, body: { applicationId: id, applicationRound: 1, status: 'PENDING', conditionConfirmation: s.confirmation(id) } };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function page() {
  const forbidden = [];
  const { El } = makeDom(forbidden);
  const els = {};
  const get = id => (els[id] ??= new El('el', id));
  const server = fakeServer();
  const calls = [], overrides = [], held = [], windowListeners = {};
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: get, createElement: tag => new El(tag), write: () => forbidden.push('document.write'),
      get cookie() { forbidden.push('document.cookie'); return ''; } },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, location: { search: '' } },
    AbortController, URLSearchParams,
    console: trap(forbidden, 'console'), localStorage: trap(forbidden, 'localStorage'), sessionStorage: trap(forbidden, 'sessionStorage'),
    indexedDB: trap(forbidden, 'indexedDB'),
    fetch: (url, options = {}) => {
      calls.push({ url, options: plain({ ...options, signal: undefined }) });
      const index = overrides.findIndex(o => o.match(url, options));
      let spec;
      if (index >= 0) { spec = overrides[index].reply; overrides.splice(index, 1); } else spec = server.handle(url, options);
      if (spec === 'hold') return new Promise((resolve, reject) => held.push({ resolve: s => resolve(respond(s ?? server.handle(url, options))), reject }));
      return spec instanceof Error ? Promise.reject(spec) : Promise.resolve(respond(spec));
    }
  };
  vm.createContext(context);
  vm.runInContext(SCRIPT, context);
  const p = {
    els, server, calls, held, forbidden, el: get,
    once(match, reply) { overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    show: async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); },
    hide: async () => { windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    click: async id => { get(id).fire('click'); await settle(); },
    caseButton: applicationId => get('cases').children.find(r => r.children[3]?.textContent.startsWith('지원 #' + applicationId + ' '))
      .children[4].children[0],
    choose: async applicationId => { p.caseButton(applicationId).fire('click'); await settle(); },
    urls: () => calls.map(c => (c.options.method || 'GET') + ' ' + c.url),
    posts: () => calls.filter(c => c.options.method === 'POST'),
    since: n => calls.slice(n).map(c => (c.options.method || 'GET') + ' ' + c.url),
    allText: () => Object.values(els).map(e => e.allText).join('\n')
  };
  return p;
}
async function shown(account = EMPLOYER) {
  const p = page();
  p.server.account = account;
  await p.show();
  return p;
}
async function selected(applicationId, account = EMPLOYER) {
  const p = await shown(account);
  await p.choose(applicationId);
  return p;
}
const SEL = id => `/api/job-postings/${P}/schedules/${300 + id % 100}/applications/${id}/selection`;
const CONF = id => `/api/me/applications/${id}/condition-confirmation`;

test('진입: /me → 본인 사례 GET만, 준비 POST 없음, same-origin·no-store, 고용주 패널만 표시', async () => {
  const p = await shown();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /demo/api/work-selection/fixtures', 'GET /api/auth/me']);
  assert.ok(p.calls.every(c => c.options.credentials === 'same-origin' && c.options.cache === 'no-store'));
  assert.equal(p.posts().length, 0);
  assert.equal(p.els.cases.children.length, 5);
  assert.equal(p.els['employer-panel'].hidden, false);
  assert.equal(p.els['worker-panel'].hidden, true);
  assert.match(p.els.account.textContent, /가상 고용주 · 고용주 · 회원 ID 10/);
  assert.equal(p.els.select.disabled, true, '사례·조건 조회 전에는 선정 비활성');
});

test('로그인 전(401)·지원하지 않는 역할 → 안내·사례 비움·요청 중단', async () => {
  for (const reply of [{ status: 401 }, { status: 200, body: { id: 5, name: 'X', role: 'ADMIN' } }]) {
    const p = page();
    p.once('/api/auth/me', reply);
    await p.show();
    assert.equal(p.els.account.textContent, '고용주 또는 근로자로 로그인해 주세요.');
    assert.deepEqual(p.urls(), ['GET /api/auth/me']);
    assert.equal(p.els.cases.children.length, 0);
    assert.equal(p.els.prepare.disabled, true);
    assert.match(p.els.notice.textContent, /로그인이 필요합니다/);
  }
});

test('근로자: 근로자 패널만, 사례 선택 시 /api/me/applications/{id} 상세 조회', async () => {
  const p = await selected(1002, WORKER);
  assert.equal(p.els['worker-panel'].hidden, false);
  assert.equal(p.els['employer-panel'].hidden, true);
  assert.ok(p.urls().includes('GET /api/me/applications/1002'));
  assert.ok(!p.urls().some(u => u.includes('/api/job-postings/')));
  assert.equal(p.els.select.disabled, true);
  assert.equal(p.els.confirm.disabled, false);
});

test('준비 버튼에서만: CSRF 발급 → /me → POST(CSRF 헤더만, 본문 없음) → /me → 첫 사례 상세; 두 번째는 200 재사용', async () => {
  const p = await shown();
  const n = p.calls.length;
  await p.click('prepare');
  assert.deepEqual(p.since(n).slice(0, 6), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST /demo/api/work-selection/fixtures',
    'GET /api/auth/me', `GET /api/job-postings/${P}/schedules/301/applications/1001`]);
  const post = p.posts()[0];
  assert.deepEqual(Object.keys(post.options.headers), ['X-CSRF-TOKEN']);
  assert.equal(post.options.body, undefined);
  assert.match(p.els['fixture-note'].textContent, /^시연 준비 HTTP 201 · 새 데이터 생성/);
  assert.match(p.els['target-note'].textContent, /정상 선정·같은 선정 재요청/);
  await p.click('prepare');
  assert.match(p.els['fixture-note'].textContent, /^시연 준비 HTTP 200 · 기존 상태를 유지하고 재사용/);
  assert.equal(p.posts().length, 2);
});

test('조건 조회: 버전 자동 사용(입력은 읽기 전용 값), 비교 표 변경 행 강조, 확인 근거·재확인 ID·최초 재확인 일시 표시', async () => {
  const p = await selected(1002);
  assert.equal(p.els.version.value, '2');
  assert.match(p.els.confirmation.textContent, /최초 지원 버전: 1 \/ 최신 버전: 2\n최신 조건 재확인 필요\n확인 근거 버전: 1\n재확인 ID: 없음\n최초 재확인 일시: 없음/);
  const rows = p.els.conditions.children;
  assert.equal(rows.length, 14);
  const start = rows.find(r => r.children[0].textContent === '예정 시작');
  assert.equal(start.className, 'changed');
  assert.deepEqual(start.children.map(c => c.textContent), ['예정 시작', '2026-12-05T09:00:00', '2026-12-05T10:00:00']);
  assert.equal(rows.find(r => r.children[0].textContent === '일급').className, '');
  assert.equal(p.els.assignment.textContent, '아직 확정 근무가 없습니다.');
  p.server.acks[1002] = { id: 9001, v: 2, at: '2026-11-28T14:00:00.000001+09:00' };
  await p.click('detail');
  assert.match(p.els.confirmation.textContent, /최신 조건 재확인 완료\n확인 근거 버전: 2\n재확인 ID: 9001\n최초 재확인 일시: 2026-11-28T14:00:00.000001\+09:00/);
});

test('선정 201 → 같은 요청 200: 같은 경로·본문, 확정 근무 ID·확정 일시 유지, CSRF·JSON 헤더', async () => {
  const p = await selected(1001);
  assert.equal(p.els['repeat-select'].disabled, true);
  await p.click('select');
  const first = p.posts().at(-1);
  assert.equal(first.url, SEL(1001));
  assert.equal(first.options.body, '{"termsVersion":1}');
  assert.equal(first.options.headers['X-CSRF-TOKEN'], TOKEN);
  assert.equal(first.options.headers['Content-Type'], 'application/json');
  assert.equal(p.els['write-status'].textContent, 'HTTP 201\n확정 근무 #7001 · 확정 근무 · 확정 일시 2026-11-28T15:00:00.123456+09:00');
  assert.equal(p.els['write-status'].dataset.error, 'false');
  assert.equal(p.els['repeat-select'].disabled, false);
  const before = p.calls.length;
  await p.click('repeat-select');
  assert.deepEqual(p.since(before), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST ' + SEL(1001), 'GET /api/auth/me']);
  assert.equal(p.posts().at(-1).options.body, first.options.body);
  assert.equal(p.els['write-status'].textContent, 'HTTP 200\n확정 근무 #7001 · 확정 근무 · 확정 일시 2026-11-28T15:00:00.123456+09:00');
  assert.equal(p.server.prepared, 0, '재요청 전에 준비 POST로 상태를 초기화하지 않음');
});

test('쓰기 직전에 최신 버전으로 몰래 교체하지 않음: 조회 후 서버 버전이 바뀌면 조회 버전 그대로 보내 409 TERMS_VERSION_CONFLICT', async () => {
  const p = await selected(1001);
  p.server.latest[1001] = 2;
  const before = p.calls.length;
  await p.click('select');
  assert.deepEqual(p.since(before), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST ' + SEL(1001), 'GET /api/auth/me']);
  assert.equal(p.posts().at(-1).options.body, '{"termsVersion":1}');
  assert.match(p.els['write-status'].textContent, /^HTTP 409 · TERMS_VERSION_CONFLICT/);
  assert.equal(p.els['write-status'].dataset.error, 'true');
  assert.equal(p.els.version.value, '1');
  assert.equal(p.els['repeat-select'].disabled, true, '실패한 요청은 재요청 대상이 아님');
});

test('이전 버전 버튼: 조회 버전보다 1 작은 값 전송(버전 2 이상만 활성), 409 표시', async () => {
  const one = await selected(1001);
  assert.equal(one.els['old-select'].disabled, true);
  const p = await selected(1003);
  assert.equal(p.els['old-select'].disabled, false);
  await p.click('old-select');
  assert.equal(p.posts().at(-1).options.body, '{"termsVersion":1}');
  assert.match(p.els.notice.textContent, /^HTTP 409 · TERMS_VERSION_CONFLICT/);
  assert.equal(p.els.notice.dataset.error, 'true');
});

test('409 표시: 미확인 CONDITION_RECONFIRMATION_REQUIRED·정원 CAPACITY_FULL·상태 SELECTION_NOT_ALLOWED', async () => {
  for (const [id, code] of [[1002, 'CONDITION_RECONFIRMATION_REQUIRED'], [1011, 'CAPACITY_FULL'], [1005, 'SELECTION_NOT_ALLOWED']]) {
    const p = await selected(id);
    await p.click('select');
    assert.match(p.els['write-status'].textContent, new RegExp('^HTTP 409 · ' + code), code);
    assert.match(p.els.notice.textContent, new RegExp('HTTP 409 · ' + code), code);
    assert.equal(p.els['write-status'].dataset.error, 'true');
    assert.match(p.els['write-response'].textContent, new RegExp('"code": "' + code + '"'));
    assert.equal(p.els['repeat-select'].disabled, true);
  }
});

test('근로자 재확인 200 → 같은 요청 같은 본문 200, 최초 재확인 ID·시각 유지; 이전 버전 재확인 409; 이후 고용주 선정 201', async () => {
  const w = await selected(1002, WORKER);
  await w.click('confirm');
  const first = w.posts().at(-1);
  assert.equal(first.url, CONF(1002));
  assert.equal(first.options.body, '{"termsVersion":2}');
  assert.equal(w.els['write-status'].textContent, 'HTTP 200\n최신 조건 재확인 완료\n확인 근거 버전: 2\n재확인 ID: 9001\n최초 재확인 일시: 2026-11-28T14:00:00.000001+09:00');
  await w.click('repeat-confirm');
  assert.equal(w.posts().at(-1).options.body, first.options.body);
  assert.equal(w.posts().at(-1).url, first.url);
  assert.match(w.els['write-status'].textContent, /재확인 ID: 9001\n최초 재확인 일시: 2026-11-28T14:00:00.000001\+09:00/);
  await w.click('old-confirm');
  assert.equal(w.posts().at(-1).options.body, '{"termsVersion":1}');
  assert.match(w.els['write-status'].textContent, /^HTTP 409 · TERMS_VERSION_CONFLICT/);
  const e = page();
  e.server.acks = w.server.acks;
  await e.show(); await e.choose(1002); await e.click('select');
  assert.match(e.els['write-status'].textContent, /^HTTP 201/);
});

test('최근 쓰기 결과는 후속 GET(조건 조회)에 덮이지 않음, 진단 응답은 GET으로 갱신', async () => {
  const p = await selected(1001);
  await p.click('select');
  const status = p.els['write-status'].textContent, body = p.els['write-response'].textContent;
  await p.click('detail');
  assert.equal(p.els['write-status'].textContent, status);
  assert.equal(p.els['write-response'].textContent, body);
  assert.match(p.els['http-status'].textContent, /^GET \/api\/job-postings\/4201\/schedules\/301\/applications\/1001 · HTTP 200/);
  assert.match(p.els.assignment.textContent, /^확정 근무 #7001 · 확정 일시 2026-11-28T15:00:00.123456\+09:00/);
});

test('불확실한 POST(통신 실패·JSON 없는 500)는 자동 재전송하지 않고 성공으로 표시하지 않음, 재요청 버튼 비활성 유지', async () => {
  const p = await selected(1001);
  p.once(SEL(1001), new TypeError('network down'));
  await p.click('select');
  assert.equal(p.posts().length, 1);
  assert.match(p.els.notice.textContent, /network down\n쓰기 요청 결과가 불확실하면 최신 상태를 조회하세요. 자동 재전송하지 않습니다./);
  assert.equal(p.els['repeat-select'].disabled, true);
  assert.ok(!/^HTTP 2/.test(p.el('write-status').textContent), '성공 쓰기 결과 표시 없음');
  p.once(SEL(1001), { status: 500 });
  await p.click('select');
  assert.equal(p.posts().length, 2);
  assert.match(p.els['write-status'].textContent, /^HTTP 500\n응답을 확인하세요./);
  assert.equal(p.els['write-status'].dataset.error, 'true');
  assert.equal(p.els['repeat-select'].disabled, true);
});

test('연속 클릭 중복 전송 방지: POST 대기 중 선정·준비·조회 버튼 비활성·재요청 없음', async () => {
  const p = await selected(1001);
  p.once(SEL(1001), 'hold');
  await p.click('select');
  assert.equal(p.els.select.disabled, true);
  assert.equal(p.els.prepare.disabled, true);
  await p.click('select'); await p.click('prepare'); await p.click('detail'); await p.click('old-select');
  assert.equal(p.posts().length, 1);
  p.held[0].resolve();
  await settle();
  assert.match(p.els['write-status'].textContent, /^HTTP 201/);
  assert.equal(p.els.select.disabled, false);
});

test('계정 전환: POST 대기 중 다른 계정이 되면 결과를 표시하지 않고 사례·대상 초기화', async () => {
  const p = await selected(1001);
  p.once(SEL(1001), 'hold');
  await p.click('select');
  p.server.account = { id: 11, name: '다른 고용주', role: 'EMPLOYER' };
  p.held[0].resolve();
  await settle();
  assert.equal(p.els.account.textContent, '계정이 변경되었습니다. 사례를 다시 불러오세요.');
  assert.equal(p.els['write-status'].textContent, '쓰기 요청 전');
  assert.equal(p.els.cases.children.length, 0);
  assert.equal(p.els.version.value, '');
  assert.match(p.els.notice.textContent, /계정이 바뀌어 이전 요청 결과를 표시하지 않습니다/);
  assert.equal(p.els.select.disabled, true);
});

test('같은 회원의 역할 전환(고용주→근로자) 뒤 다시 불러오면 이전 대상·쓰기 결과를 지우고 근로자 패널', async () => {
  const p = await selected(1001);
  await p.click('select');
  p.server.account = { ...EMPLOYER, role: 'WORKER' };
  await p.click('reload');
  assert.equal(p.els['write-status'].textContent, '쓰기 요청 전');
  assert.equal(p.els['worker-panel'].hidden, false);
  assert.equal(p.els['employer-panel'].hidden, true);
  assert.equal(p.els['repeat-select'].disabled, true);
});

test('화면 이탈(pagehide) 뒤 도착한 조회·쓰기 응답은 표시하지 않음, 진행 요청 abort', async () => {
  const p = await shown();
  p.once(`/api/job-postings/${P}/schedules/301/applications/1001`, 'hold');
  await p.choose(1001);
  await p.hide();
  p.held[0].resolve();
  await settle();
  assert.equal(p.els.confirmation.textContent, '조건 조회 전');
  assert.equal(p.els.account.textContent, '로그인을 다시 확인해 주세요.');
  assert.equal(p.els.cases.children.length, 0);
  const q = await selected(1001);
  q.once(SEL(1001), 'hold');
  await q.click('select');
  await q.hide();
  q.held[0].resolve();
  await settle();
  assert.equal(q.els['write-status'].textContent, '쓰기 요청 전');
});

test('권한 403(EMPLOYER_ROLE_REQUIRED)은 전체 초기화 후 다시 로그인 안내', async () => {
  const p = await selected(1001);
  p.once(SEL(1001), { status: 403, body: { code: 'EMPLOYER_ROLE_REQUIRED', message: '고용주 본인 공고의 지원자만 선정할 수 있습니다.' } });
  await p.click('select');
  assert.match(p.els.notice.textContent, /접근 권한이 바뀌었습니다. 다시 로그인해 주세요./);
  assert.equal(p.els.cases.children.length, 0);
  assert.equal(p.els.prepare.disabled, true);
});

test('CSRF 토큰·쿠키·비밀번호를 화면·진단·요청 기록·저장소에 남기지 않음, CSRF 실패 시 POST 없음', async () => {
  const p = await selected(1001);
  await p.click('prepare'); await p.choose(1001); await p.click('select'); await p.click('repeat-select');
  assert.ok(!p.allText().includes(TOKEN));
  assert.ok(!p.allText().toLowerCase().includes('password'));
  assert.deepEqual(p.forbidden, []);
  const posts = p.posts().length;
  p.once('/api/auth/csrf', { status: 200, body: { headerName: '', token: '' } });
  await p.click('select');
  assert.equal(p.posts().length, posts);
  assert.match(p.els.notice.textContent, /^CSRF 토큰을 발급받지 못했습니다./);
});

test('외부 문자열(사례 이름·서버 메시지)은 textContent로만 표시, innerHTML 등 미사용', async () => {
  const p = page();
  const evil = '<img src=x onerror=alert(1)>';
  p.server.cases[0].label = evil;
  await p.show();
  assert.ok(p.els.cases.children[0].children[0].textContent.startsWith(evil));
  await p.choose(1001);
  p.once(SEL(1001), { status: 409, body: { code: 'CAPACITY_FULL', message: evil } });
  await p.click('select');
  assert.ok(p.els.notice.textContent.includes(evil));
  assert.deepEqual(p.forbidden, []);
});

test('한글 버튼·안내 유지', async () => {
  const p = await shown();
  assert.ok(html.includes('<button id="prepare" type="button">선정·재확인 시연 데이터 준비</button>'));
  assert.ok(html.includes('<button id="select" type="button">이 지원자 선정</button>'));
  assert.ok(html.includes('<button id="confirm" type="button">최신 조건으로 지원 유지 · 재확인</button>'));
  assert.equal(p.caseButton(1001).textContent, '이 사례 선택');
  assert.match(p.els.notice.textContent, /본인 시연 사례를 불러왔습니다/);
});

test('[관찰] 선정 성공 뒤 같은 사례를 다시 선택하거나 사례를 새로 불러오면 「같은 선정 요청 다시 보내기」가 비활성으로 돌아간다', async () => {
  const p = await selected(1001);
  await p.click('select');
  assert.equal(p.els['repeat-select'].disabled, false);
  await p.choose(1001);
  assert.equal(p.els['repeat-select'].disabled, true);
  await p.click('select');
  assert.match(p.els['write-status'].textContent, /^HTTP 200/, '일반 선정 버튼으로 다시 보내면 기존 결과 200');
});

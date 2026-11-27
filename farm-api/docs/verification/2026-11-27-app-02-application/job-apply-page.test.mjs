// farm-job-apply.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch·crypto는 모두 가짜이며, 상태를 가진 가짜 서버 함수가 지원 정책을 흉내 내 응답한다.
// 실제 브라우저·Spring 서버·세션·CSRF 검증·DB는 없다. 가짜 서버의 판단은 실제 서비스 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-27-app-02-application/job-apply-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(path.resolve(here, '../../../src/main/resources/demo/farm-job-apply.html'), 'utf8');
const SCRIPT = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const TOKEN = 'csrf-token-SECRET-xyz';
const WORKER = { id: 5, name: 'TEST WORKER', role: 'WORKER' };
const UUID_V4 = /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/;
const settle = async () => { for (let i = 0; i < 40; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));

function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, attributes: {}, checked: false, type: '', href: '', colSpan: 1 }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {} })); }
    replaceChildren(...items) { this.children = []; this.append(...items); if (this.tagName === 'select') this._value = items[0]?.value ?? ''; }
    append(...items) { for (const item of items) { item.parent = this; this.children.push(item); } }
    prepend(item) { item.parent = this; this.children.unshift(item); }
    remove() { this.parent.children = this.parent.children.filter(x => x !== this); }
    get lastElementChild() { return this.children.at(-1); }
    get options() { return this.children; }
    get value() { return this._value; }
    set value(v) { this._value = String(v); }
    setAttribute(k, v) { this.attributes[k] = v; }
    querySelectorAll(selector) {
      const found = [];
      const walk = el => { for (const child of el.children) { if (child.tagName === selector) found.push(child); walk(child); } };
      walk(this); return found;
    }
    get allText() { return [this.textContent, this._value, this.attributes['aria-label'] ?? '', ...this.children.map(c => c.allText)].join(' '); }
  }
  for (const name of ['innerHTML', 'outerHTML']) {
    Object.defineProperty(El.prototype, name, { get() { forbidden.push(name); return ''; }, set() { forbidden.push(name); } });
  }
  El.prototype.insertAdjacentHTML = function () { forbidden.push('insertAdjacentHTML'); };
  function Option(text, value) { const option = new El('option'); option.textContent = text; option.value = value; return option; }
  return { El, Option };
}
const trap = (forbidden, name) => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });

// 가짜 서버: 공고 101, 일정 201(v1)·202(v3)·203(v2). 접수 정책을 단순화해 흉내 낸다.
function fakeServer() {
  const s = {
    clock: '2026-12-01T10:02:03.456789+09:00', nextId: 9000,
    schedules: [
      { workScheduleId: 201, workDate: '2026-12-08', termsVersion: 1 },
      { workScheduleId: 202, workDate: '2026-12-09', termsVersion: 3 },
      { workScheduleId: 203, workDate: '2026-12-10', termsVersion: 2 }],
    apps: [], receipts: new Map(), fixtures: 0, title: '[가상] 사과 수확', workLocation: '가상 작업지'
  };
  const detail = () => ({ jobPostingId: 101, title: s.title, workLocation: s.workLocation, cropName: '사과', workTypeName: '수확',
    timeZone: 'GMT+9', headcount: 3, dailyWage: 120000,
    schedules: s.schedules.map(row => {
      const current = s.apps.find(a => a.workScheduleId === row.workScheduleId && a.current);
      const canApply = !current || current.status === 'NOT_SELECTED';
      return { ...row, plannedStartAt: row.workDate + 'T09:00:00', plannedEndAt: row.workDate + 'T17:00:00',
        breakStartAt: row.workDate + 'T12:00:00', breakEndAt: row.workDate + 'T13:00:00', status: 'OPEN', remainingSlots: 3,
        canApply, unavailableReason: canApply ? null : 'ALREADY_APPLIED' };
    }) });
  s.handle = (url, options) => {
    const method = options.method || 'GET';
    if (url === '/api/auth/me') return { status: 200, body: WORKER };
    if (url === '/api/auth/csrf') return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: TOKEN } };
    if (url.startsWith('/api/job-postings?')) return { status: 200, body: { jobPostings: [{ jobPostingId: 101, title: s.title }], page: 0, hasNext: false } };
    if (url === '/api/job-postings/101' && method === 'GET') return { status: 200, body: detail() };
    if (url === '/demo/api/applications/postings/101/history') return { status: 200, body: { applications: [...s.apps].sort((a, b) => b.applicationId - a.applicationId)
      .map(a => ({ applicationId: a.applicationId, workScheduleId: a.workScheduleId, applicationRound: a.applicationRound, status: a.status,
        current: a.current, appliedTermsVersion: a.appliedTermsVersion, assignmentId: null, changeType: null })) } };
    if (url === '/demo/api/applications/fixtures' && method === 'POST') {
      if (options.headers?.['X-CSRF-TOKEN'] !== TOKEN) return { status: 403 };
      s.fixtures++;
      if (!s.apps.some(a => a.workScheduleId === 203)) s.apps.push({ applicationId: ++s.nextId, workScheduleId: 203, applicationRound: 1,
        status: 'NOT_SELECTED', current: true, appliedTermsVersion: 1 });
      return { status: s.fixtures === 1 ? 201 : 200, body: { jobPostingId: 101, created: s.fixtures === 1 } };
    }
    if (url === '/api/job-postings/101/applications' && method === 'POST') {
      if (options.headers?.['X-CSRF-TOKEN'] !== TOKEN) return { status: 403 };
      const key = options.headers['Idempotency-Key'];
      const requested = JSON.parse(options.body).schedules;
      const fingerprint = JSON.stringify([...requested].sort((a, b) => a.workScheduleId - b.workScheduleId));
      const receipt = s.receipts.get(key);
      if (receipt) return receipt.fingerprint === fingerprint ? { status: 200, body: receipt.body }
        : { status: 409, body: { code: 'IDEMPOTENCY_KEY_CONFLICT', message: '이미 접수한 요청 키에 다른 내용을 보낼 수 없습니다.' } };
      for (const item of requested) {
        const row = s.schedules.find(r => r.workScheduleId === item.workScheduleId);
        if (row.termsVersion !== item.termsVersion) return { status: 409, body: { code: 'SCHEDULE_TERMS_CHANGED', message: '근무 조건이 변경되었습니다.' } };
        const current = s.apps.find(a => a.workScheduleId === item.workScheduleId && a.current);
        if (current && current.status !== 'NOT_SELECTED') return { status: 409, body: { code: 'ALREADY_APPLIED', message: '이미 지원 대기 중인 날짜가 포함되어 있습니다.' } };
      }
      const created = requested.map(item => {
        const past = s.apps.filter(a => a.workScheduleId === item.workScheduleId);
        past.forEach(a => { a.current = false; });
        const app = { applicationId: ++s.nextId, workScheduleId: item.workScheduleId, applicationRound: past.length + 1, status: 'PENDING',
          current: true, appliedTermsVersion: item.termsVersion };
        s.apps.push(app);
        return { applicationId: app.applicationId, workScheduleId: app.workScheduleId, applicationRound: app.applicationRound,
          status: 'PENDING', appliedTermsVersion: app.appliedTermsVersion, appliedAt: s.clock };
      });
      const body = { jobPostingId: 101, applications: created };
      s.receipts.set(key, { fingerprint, body });
      return { status: 201, body };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function applyPage({ search = '' } = {}) {
  const forbidden = [];
  const { El, Option } = makeDom(forbidden);
  const els = {};
  const get = id => (els[id] ??= new El(id === 'postings' ? 'select' : id === 'posting-id' ? 'input' : 'el', id));
  get('postings').replaceChildren(Option('목록을 조회해 주세요', ''));
  const server = fakeServer();
  const calls = [];
  const overrides = [];
  const held = [];
  const windowListeners = {};
  let uuidCounter = 0;
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: get, createElement: tag => new El(tag), write: () => forbidden.push('document.write') },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, location: { search } },
    Option, AbortController, URLSearchParams,
    crypto: { randomUUID: () => `3f2b8c1e-9a4d-4c6b-8e2f-${String(++uuidCounter).padStart(12, '0')}` },
    console: trap(forbidden, 'console'), localStorage: trap(forbidden, 'localStorage'), sessionStorage: trap(forbidden, 'sessionStorage'),
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
    els, server, calls, overrides, held, forbidden,
    once(match, reply) { overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    show: async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); },
    hide: async () => { windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    click: async id => { get(id).fire('click'); await settle(); },
    openPosting: async id => { get('posting-id').value = String(id); get('detail-form').fire('submit'); await settle(); },
    boxes: () => get('dates').querySelectorAll('input'),
    check: async (...scheduleIds) => {
      for (const box of p.boxes()) {
        const id = Number(box.attributes['aria-label'].match(/일정 (\d+)/)[1]);
        if (scheduleIds.includes(id) && !box.checked) { box.checked = true; box.fire('change'); }
      }
      await settle();
    },
    posts: () => calls.filter(c => c.options.method === 'POST' && c.url.endsWith('/applications')),
    urls: () => calls.map(c => (c.options.method || 'GET') + ' ' + c.url),
    historyRows: () => get('history').children.map(r => r.children.map(td => td.textContent)),
    allText: () => Object.values(els).map(e => e.allText).join('\n')
  };
  return p;
}

test('초기 표시: /me → 목록 → /me만 호출하고 시연 준비 POST·상세는 자동으로 요청하지 않는다', async () => {
  const p = applyPage();
  await p.show();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/job-postings?page=0&size=20', 'GET /api/auth/me']);
  assert.deepEqual(p.els.postings.options.map(o => o.value), ['', '101']);
  for (const call of p.calls) { assert.equal(call.options.credentials, 'same-origin'); assert.equal(call.options.cache, 'no-store'); }
  assert.equal(p.els.apply.disabled, true);
});

test('?jobPostingId=101이면 상세와 본인 이력을 조회하고, 지원 가능한 날짜만 체크박스 활성', async () => {
  const p = applyPage({ search: '?jobPostingId=101' });
  p.server.apps.push({ applicationId: 8000, workScheduleId: 202, applicationRound: 1, status: 'PENDING', current: true, appliedTermsVersion: 3 });
  await p.show();
  assert.ok(p.urls().includes('GET /api/job-postings/101') && p.urls().includes('GET /demo/api/applications/postings/101/history'));
  assert.deepEqual(p.boxes().map(b => [b.dataset.allowed, b.disabled]), [['true', false], ['false', true], ['true', false]]);
  assert.match(p.els.dates.children[1].allText, /이미 지원 대기 중/);
  assert.deepEqual(p.historyRows()[0], ['8000', '202', '1', '지원 대기', '현재', '3', '없음']);
});

test('일반 지원: CSRF 조회 → /me → POST(JSON·UUID v4 키·CSRF 헤더·조회 버전) → /me → 상세·이력 재조회, 201 안내', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(202, 201);
  p.calls.length = 0;
  await p.click('apply');
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST /api/job-postings/101/applications',
    'GET /api/auth/me', 'GET /api/job-postings/101', 'GET /demo/api/applications/postings/101/history', 'GET /api/auth/me']);
  const post = p.posts()[0];
  assert.match(post.options.headers['Idempotency-Key'], UUID_V4);
  assert.equal(post.options.headers['Content-Type'], 'application/json');
  assert.equal(post.options.headers['X-CSRF-TOKEN'], TOKEN);
  assert.deepEqual(JSON.parse(post.options.body), { schedules: [{ workScheduleId: 201, termsVersion: 1 }, { workScheduleId: 202, termsVersion: 3 }] });
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201');
  assert.match(p.els.comparison.textContent, /^새 지원이 접수됐습니다/);
  assert.equal(p.els.notice.dataset.error, 'false');
  assert.deepEqual(p.historyRows().map(r => r.slice(0, 5)), [['9002', '202', '1', '지원 대기', '현재'], ['9001', '201', '1', '지원 대기', '현재']]);
  assert.ok(!p.allText().includes(TOKEN), 'CSRF 토큰이 화면·기록에 없음');
  assert.ok(!p.els.requests.allText.includes('csrf'), '요청 기록 표에 CSRF 요청 없음');
  assert.deepEqual(p.forbidden, []);
});

test('같은 요청 재전송: 같은 키·같은 본문 문자열로 200, 최초 결과와 같으면 유지 안내', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  await p.click('apply');
  p.server.clock = '2026-12-01T11:00:00+09:00';
  p.server.schedules[0].termsVersion = 5;   // 그사이 조건이 바뀌어도 서버는 최초 결과를 돌려준다
  await p.click('retry');
  const [first, second] = p.posts();
  assert.equal(second.options.headers['Idempotency-Key'], first.options.headers['Idempotency-Key']);
  assert.equal(second.options.body, first.options.body);
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 200');
  assert.equal(p.els.comparison.textContent, '최초 지원 ID·회차·버전·접수 시각이 모두 유지됐습니다.');
  assert.equal(p.els['last-key'].textContent, first.options.headers['Idempotency-Key']);
});

test('재요청 결과가 최초 결과와 다르면(ID·시각 변경) 유지 성공으로 표시하지 않는다', async () => {
  for (const change of [body => { body.applications[0].applicationId = 9999; }, body => { body.applications[0].appliedAt = '2026-12-01T01:02:03.456789Z'; }]) {
    const p = applyPage();
    await p.show();
    await p.openPosting(101);
    await p.check(201);
    await p.click('apply');
    const stored = [...p.server.receipts.values()][0].body;
    const altered = plain(stored); change(altered);
    p.once(url => url.endsWith('/applications'), { status: 200, body: altered });
    await p.click('retry');
    assert.equal(p.els.comparison.textContent, '재요청 응답이 최초 접수 결과와 다릅니다. 유지 성공으로 표시하지 않았습니다.');
    assert.equal(p.els.notice.dataset.error, 'true');
  }
});

test('요청만 준비: POST하지 않고 키·이전 버전을 보관 → 서버 버전 변경 후 지원은 보관 버전으로 409 → 최신 조회 필요 → 새 키·새 버전으로 201', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(202);
  await p.click('prepare');
  assert.equal(p.posts().length, 0);
  const preparedKey = p.els['prepared-key'].textContent;
  assert.match(preparedKey, UUID_V4);
  p.server.schedules[1].termsVersion = 4;  // 다른 창에서 고용주가 수정
  await p.click('apply');
  assert.equal(p.posts().length, 1);
  assert.equal(p.posts()[0].options.headers['Idempotency-Key'], preparedKey);
  assert.deepEqual(JSON.parse(p.posts()[0].options.body).schedules, [{ workScheduleId: 202, termsVersion: 3 }], '보관한 이전 버전 그대로');
  assert.match(p.els.notice.textContent, /^HTTP 409 · SCHEDULE_TERMS_CHANGED · /);
  assert.equal(p.els.apply.disabled, true, '최신 조건 조회 전에는 지원 불가');
  assert.equal(p.els.prepare.disabled, true);
  assert.equal(p.els['prepared-key'].textContent, '없음');

  await p.openPosting(101);
  await p.check(202);
  await p.click('apply');
  const retried = p.posts()[1];
  assert.notEqual(retried.options.headers['Idempotency-Key'], preparedKey, '새 키');
  assert.deepEqual(JSON.parse(retried.options.body).schedules, [{ workScheduleId: 202, termsVersion: 4 }], '최신 버전');
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201');
});

test('선택 변경 시 준비한 요청을 초기화한다', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  await p.click('prepare');
  assert.notEqual(p.els['prepared-key'].textContent, '없음');
  await p.check(203);
  assert.equal(p.els['prepared-key'].textContent, '없음');
  await p.click('apply');
  assert.deepEqual(JSON.parse(p.posts()[0].options.body).schedules.map(s => s.workScheduleId), [201, 203]);
});

test('시연 준비는 버튼을 눌러야만 CSRF와 함께 POST, 이후 재지원 시 과거 1회차·현재 2회차를 서버 결과대로 표시', async () => {
  const p = applyPage();
  await p.show();
  assert.ok(!p.urls().some(u => u.includes('fixtures')));
  await p.click('fixture');
  const fixture = p.calls.find(c => c.url === '/demo/api/applications/fixtures');
  assert.equal(fixture.options.method, 'POST');
  assert.equal(fixture.options.headers['X-CSRF-TOKEN'], TOKEN);
  assert.match(p.els.notice.textContent, /^새 시연 공고를 준비했습니다/);
  assert.deepEqual(p.historyRows()[0].slice(2, 5), ['1', '미선정', '현재']);

  await p.check(203);
  await p.click('apply');
  assert.deepEqual(p.historyRows().map(r => r.slice(1, 5)), [['203', '2', '지원 대기', '현재'], ['203', '1', '미선정', '과거']]);

  await p.click('fixture');
  assert.match(p.els.notice.textContent, /^기존 시연 공고를 열었습니다/);
});

test('최대 100개: 101번째 선택은 해제하고 안내', async () => {
  const p = applyPage();
  p.server.schedules = Array.from({ length: 101 }, (_, i) => ({ workScheduleId: 1000 + i, workDate: '2026-12-08', termsVersion: 1 }));
  await p.show();
  await p.openPosting(101);
  for (const box of p.boxes()) { box.checked = true; box.fire('change'); }
  await settle();
  assert.equal(p.boxes().filter(b => b.checked).length, 100);
  assert.equal(p.els.selected.textContent, '선택한 날짜 100개 / 최대 100개');
  assert.equal(p.els.notice.textContent, '최대 100개 날짜를 선택할 수 있습니다.');
  await p.click('apply');
  assert.equal(JSON.parse(p.posts()[0].options.body).schedules.length, 100);
});

test('중복 클릭 방지: POST 대기 중 지원·재전송·준비 재클릭은 추가 요청 없음', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once(url => url.endsWith('/applications'), 'hold');
  await p.click('apply');
  assert.equal(p.els.apply.disabled, true);
  await p.click('apply'); await p.click('retry'); await p.click('fixture'); await p.click('prepare');
  assert.equal(p.posts().length, 1);
  assert.ok(!p.calls.some(c => c.url.includes('fixtures')));
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201');
});

test('POST 통신 실패: 성공으로 표시하지 않고 자동 재전송 없음, 사용자가 누른 재전송만 같은 키로 보낸다', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once(url => url.endsWith('/applications'), new TypeError('Failed to fetch'));
  await p.click('apply');
  await settle();
  assert.equal(p.posts().length, 1);
  assert.match(p.els.notice.textContent, /자동 재전송하지 않습니다/);
  assert.equal(p.els['post-status'].textContent, '지원 POST 전송 중');
  assert.ok(!p.els.comparison.textContent.includes('유지됐습니다') && !p.els.comparison.textContent.includes('접수됐습니다'));
  assert.equal(p.els.retry.disabled, false);
  await p.click('retry');
  assert.equal(p.posts().length, 2);
  assert.equal(p.posts()[1].options.headers['Idempotency-Key'], p.posts()[0].options.headers['Idempotency-Key']);
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201', '서버에 기록이 없던 키라 첫 접수');
});

test('409 오류(이미 지원·키 충돌)는 상태코드·코드·메시지를 표시하고 성공으로 표시하지 않는다', async () => {
  const p = applyPage();
  p.server.apps.push({ applicationId: 8000, workScheduleId: 201, applicationRound: 1, status: 'NOT_SELECTED', current: true, appliedTermsVersion: 1 });
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once(url => url.endsWith('/applications'), { status: 409, body: { code: 'ALREADY_APPLIED', message: '이미 지원 대기 중인 날짜가 포함되어 있습니다.' } });
  await p.click('apply');
  assert.equal(p.els.notice.textContent, 'HTTP 409 · ALREADY_APPLIED · 이미 지원 대기 중인 날짜가 포함되어 있습니다.');
  assert.match(p.els.comparison.textContent, /^성공으로 표시하지 않았습니다/);
  assert.equal(p.els.apply.disabled, false, '버전 충돌이 아니면 최신 조회 강제 없음');
  assert.deepEqual(p.els.requests.children[0].children.map(td => td.textContent), ['POST /api/job-postings/101/applications', '409', 'ALREADY_APPLIED']);
});

test('201이지만 응답 항목이 요청과 다르면(상태 SELECTED·버전 불일치·일정 누락) 성공으로 표시하지 않는다', async () => {
  const bad = [
    body => { body.applications[0].status = 'SELECTED'; },
    body => { body.applications[0].appliedTermsVersion = 9; },
    body => { body.applications = []; },
    body => { body.jobPostingId = 102; }];
  for (const change of bad) {
    const p = applyPage();
    await p.show();
    await p.openPosting(101);
    await p.check(201);
    const body = { jobPostingId: 101, applications: [{ applicationId: 9001, workScheduleId: 201, applicationRound: 1, status: 'PENDING',
      appliedTermsVersion: 1, appliedAt: '2026-12-01T10:02:03+09:00' }] };
    change(body);
    p.once(url => url.endsWith('/applications'), { status: 201, body });
    await p.click('apply');
    assert.match(p.els.notice.textContent, /자동 재전송하지 않습니다/);
    assert.ok(!p.els.comparison.textContent.includes('접수됐습니다'));
  }
});

test('CSRF 조회 실패·형식 오류면 POST를 보내지 않는다', async () => {
  for (const reply of [{ status: 500 }, { status: 200, body: { headerName: '', token: TOKEN } }, new TypeError('x')]) {
    const p = applyPage();
    await p.show();
    await p.openPosting(101);
    await p.check(201);
    p.once('/api/auth/csrf', reply);
    await p.click('apply');
    assert.equal(p.posts().length, 0);
    assert.equal(p.els.notice.dataset.error, 'true');
  }
});

test('POST 응답 후 계정이 바뀌면 결과를 표시하지 않고 이전 키·준비·목록을 지운다, 자동 재전송 없음', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once(url => url === '/api/auth/me' && p.posts().length === 1, { status: 200, body: { id: 6, name: 'OTHER', role: 'WORKER' } });
  await p.click('apply');
  assert.equal(p.posts().length, 1);
  assert.match(p.els.notice.textContent, /자동 재전송하지 않습니다/);
  assert.equal(p.els['last-key'].textContent, '없음');
  assert.equal(p.els.retry.disabled, true, '다른 계정으로 이전 요청 재전송 불가');
  assert.deepEqual(p.els.postings.options.map(o => o.value), ['']);
  assert.equal(p.els.dates.children.length, 0);
});

test('비로그인(401)·고용주는 목록을 조회하지 않는다', async () => {
  for (const me of [{ status: 401 }, { status: 200, body: { id: 10, name: 'E', role: 'EMPLOYER' } }]) {
    const p = applyPage();
    p.once('/api/auth/me', me);
    await p.show();
    assert.ok(!p.urls().some(u => u.includes('/api/job-postings')));
    assert.equal(p.els.account.textContent, '근로자로 로그인해 주세요.');
  }
});

test('화면 이탈(pagehide) 후 도착한 POST 응답은 표시하지 않고 화면을 비운다', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once(url => url.endsWith('/applications'), 'hold');
  await p.click('apply');
  await p.hide();
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['post-result'].textContent, '실제 지원 POST 응답을 표시합니다.');
  assert.equal(p.els['last-key'].textContent, '없음');
  assert.equal(p.els.account.textContent, '근로자 로그인을 다시 확인해 주세요.');
  assert.equal(p.els.history.children.length, 0);
});

test('서버 문자열(공고명·작업지·상태·사유)은 textContent로만 표시된다', async () => {
  const p = applyPage();
  p.server.title = '<img src=x onerror=alert(1)>';
  p.server.workLocation = '<script>alert(2)</script>';
  await p.show();
  await p.openPosting(101);
  assert.equal(p.els['posting-title'].textContent, '2. <img src=x onerror=alert(1)> · #101');
  assert.match(p.els['posting-info'].textContent, /^<script>alert\(2\)<\/script> · /);
  assert.match(p.els.postings.options[1].textContent, /<img src=x/);
  assert.deepEqual(p.forbidden, []);
});


// APP-02 리뷰 보완 회귀 시나리오. 아래 추가분은 작성 후 아직 실행하지 않았다.
test('이력 500이어도 정상 상세의 날짜 선택과 새 지원은 유지하고, 이력 없음으로 표시하지 않는다', async () => {
  const p = applyPage();
  await p.show();
  p.once('/demo/api/applications/postings/101/history',
    { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류' } });
  await p.openPosting(101);
  assert.equal(p.boxes().length, 3);
  assert.ok(p.boxes().every(box => !box.disabled));
  assert.match(p.els['history-note'].textContent, /이력 조회 실패.*HTTP 500/);
  assert.equal(p.els['history-note'].dataset.error, 'true');
  assert.match(p.els.notice.textContent, /HTTP 500/);
  assert.ok(!p.els.history.allText.includes('본인 지원 이력이 없습니다'));
  await p.check(201);
  assert.equal(p.els.apply.disabled, false);
  await p.click('apply');
  assert.equal(p.posts().length, 1);
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201');
  assert.equal(p.els['history-note'].dataset.error, 'false');
  assert.equal(p.els.notice.dataset.error, 'false');
});

test('POST 201 후 이력만 500이면 접수 결과·재전송 키·다른 날짜 선택을 유지한다', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once('/demo/api/applications/postings/101/history', { status: 500 });
  await p.click('apply');
  const firstKey = p.posts()[0].options.headers['Idempotency-Key'];
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 201');
  assert.match(p.els['post-result'].textContent, /9001/);
  assert.match(p.els.notice.textContent, /지원 POST 201/);
  assert.match(p.els.notice.textContent, /HTTP 500/);
  assert.equal(p.els.retry.disabled, false);
  assert.equal(p.boxes().length, 3);
  await p.check(202);
  assert.equal(p.els.apply.disabled, false);
  await p.click('retry');
  assert.equal(p.els['post-status'].textContent, '지원 POST HTTP 200');
  assert.equal(p.posts()[1].options.headers['Idempotency-Key'], firstKey);
  assert.equal(p.server.apps.length, 1);
});

test('이력 통신 실패·잘못된 JSON 구조는 별도 오류로 표시하고 정상 날짜를 유지한다', async () => {
  for (const reply of [new TypeError('network'),
    { status: 200, body: { applications: null } },
    { status: 200, body: { applications: [null] } }]) {
    const p = applyPage();
    await p.show();
    p.once('/demo/api/applications/postings/101/history', reply);
    await p.openPosting(101);
    assert.equal(p.boxes().length, 3);
    assert.equal(p.els['history-note'].dataset.error, 'true');
    await p.check(201);
    assert.equal(p.els.apply.disabled, false);
    assert.equal(p.posts().length, 0);
  }
});

test('이력의 401·403은 보조 장애로 넘기지 않고 기존 상세·선택·키를 지운다', async () => {
  for (const status of [401, 403]) {
    const p = applyPage();
    await p.show();
    await p.openPosting(101);
    await p.check(201);
    await p.click('prepare');
    p.once('/demo/api/applications/postings/101/history', { status });
    await p.openPosting(101);
    assert.equal(p.boxes().length, 0);
    assert.equal(p.els.apply.disabled, true);
    assert.equal(p.els['prepared-key'].textContent, '없음');
    assert.equal(p.posts().length, 0);
  }
});

test('이력 응답 대기 중 계정이 바뀌면 정상 상세라도 새 계정에 이전 데이터를 표시하지 않는다', async () => {
  const p = applyPage();
  await p.show();
  p.once('/demo/api/applications/postings/101/history', 'hold');
  await p.openPosting(101);
  p.once('/api/auth/me', { status: 200, body: { ...WORKER, id: 99 } });
  p.held[0].resolve();
  await settle();
  assert.equal(p.boxes().length, 0);
  assert.equal(p.els.apply.disabled, true);
  assert.equal(p.els['prepared-key'].textContent, '없음');
  assert.equal(p.posts().length, 0);
});

test('상세 GET 자체가 실패하면 이전 공고와 선택 날짜를 유지하지 않는다', async () => {
  const p = applyPage();
  await p.show();
  await p.openPosting(101);
  await p.check(201);
  p.once('/api/job-postings/101', { status: 500 });
  await p.openPosting(101);
  assert.equal(p.boxes().length, 0);
  assert.equal(p.els.apply.disabled, true);
  assert.match(p.els.notice.textContent, /HTTP 500/);
});

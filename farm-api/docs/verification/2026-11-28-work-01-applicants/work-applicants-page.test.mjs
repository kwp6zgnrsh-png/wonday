// farm-work-applicants.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 가짜이며, 상태를 가진 가짜 서버가 WORK-01 응답을 흉내 낸다.
// 실제 브라우저·Spring 서버·세션·CSRF 검증·Security 체인·DB는 없다. 가짜 서버의 판단은 실제 API 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-28-work-01-applicants/work-applicants-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(path.resolve(here, '../../../src/main/resources/demo/farm-work-applicants.html'), 'utf8');
const SCRIPT = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const TOKEN = 'csrf-token-SECRET-xyz';
const PHONE = '01000001001';
const EMPLOYER = { id: 10, name: 'TEST EMPLOYER', role: 'EMPLOYER' };
const BASE = '/api/job-postings/101/schedules/201/applications';
const settle = async () => { for (let i = 0; i < 40; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));

function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, type: '', className: '', colSpan: 1 }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {} })); }
    replaceChildren(...items) { this.children = []; this.append(...items); if (this.tagName === 'select') this._value = items[0]?.value ?? ''; }
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
  function Option(text, value) { const o = new El('option'); o.textContent = text; o.value = value; return o; }
  return { El, Option };
}
const trap = (forbidden, name) => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });

const conditions = (over = {}) => ({ availability: 'AVAILABLE', termsVersion: 2, workDate: '2026-12-08', timeZone: 'GMT+9',
  workLocation: '가상 작업지', latitude: 37, longitude: 127, cropCode: 'FC03030100030103', cropName: '콩(일반)', workTypeCode: '0003',
  workTypeName: '경운', description: '가상 내용', headcount: 3, dailyWage: 120000, plannedStartAt: '2026-12-08T10:00:00+09:00',
  plannedEndAt: '2026-12-08T18:00:00+09:00', breakStartAt: '2026-12-08T12:00:00+09:00', breakEndAt: '2026-12-08T13:00:00+09:00',
  unavailableFields: [], ...over });
const item = (id, over = {}) => ({ applicationId: id, workerId: 9000 + id % 100, name: '시연 근로자 ' + id, maskedPhone: '010-****-1001',
  phoneState: 'AVAILABLE', applicationRound: 1, status: 'PENDING', appliedAt: '2026-11-30T15:00:00+09:00', legacyAppliedAt: null,
  appliedTimeBasis: 'UTC', appliedTermsVersion: 2, latestTermsVersion: 2, conditionComparisonStatus: 'UNCHANGED', canViewContact: true,
  assignmentId: null, workStatus: 'PENDING', ...over });

function fakeServer() {
  const s = {
    items: [item(501, { appliedTermsVersion: 1, conditionComparisonStatus: 'CHANGED' }), item(502), item(503, { status: 'SELECTED', workStatus: 'CONFIRMED', assignmentId: 700 }),
      item(504, { status: 'NOT_SELECTED', workStatus: 'NOT_SELECTED', canViewContact: false }),
      item(505, { maskedPhone: null, phoneState: 'NOT_REGISTERED', applicationRound: 2 })],
    details: {}, contacts: {}, fixtures: 0
  };
  for (const i of s.items) s.details[i.applicationId] = { ...i, appliedConditions: conditions(), latestConditions: conditions(),
    changedFields: [], unavailableComparisonFields: [], comparisonComplete: true, assignment: null };
  s.details[501] = { ...s.details[501], appliedConditions: conditions({ termsVersion: 1, availability: 'PARTIAL', latitude: null, longitude: null,
    description: null, plannedStartAt: '2026-12-08T09:00:00+09:00', plannedEndAt: '2026-12-08T17:00:00+09:00',
    unavailableFields: ['latitude', 'longitude', 'description'] }), changedFields: ['plannedStartAt', 'plannedEndAt'],
    unavailableComparisonFields: ['latitude', 'longitude', 'description'], comparisonComplete: false };
  s.details[503] = { ...s.details[503], assignment: { assignmentId: 700, confirmedAt: '2026-11-30T15:16:00+09:00', completedAt: null, change: null,
    agreedConditions: { availability: 'AVAILABLE', workLocation: '약정 작업지', dailyWage: 120000, breakStartAt: null, breakEndAt: null, unavailableFields: [] } } };
  const contact = (id, phone, state) => ({ status: 200, body: { applicationId: id, workerId: 9000 + id % 100, name: '시연 근로자 ' + id, phone, phoneState: state } });
  s.contacts = { 501: contact(501, PHONE, 'AVAILABLE'), 502: contact(502, '01000001002', 'AVAILABLE'), 503: contact(503, '01000001003', 'AVAILABLE'),
    505: contact(505, null, 'NOT_REGISTERED'),
    504: { status: 403, body: { code: 'CONTACT_ACCESS_DENIED', message: '현재 전체 연락처를 확인할 수 없는 지원 내역입니다.' } },
    401: { status: 403, body: { code: 'CONTACT_ACCESS_DENIED', message: '현재 전체 연락처를 확인할 수 없는 지원 내역입니다.' } } };
  const notFound = { status: 404, body: { code: 'APPLICATION_NOT_FOUND', message: '해당 날짜의 현재 지원 내역을 찾을 수 없습니다.' } };
  s.handle = (url, options) => {
    const method = options.method || 'GET';
    const u = new URL(url, 'http://local');
    if (url === '/api/auth/me') return { status: 200, body: EMPLOYER };
    if (url === '/api/auth/csrf') return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: TOKEN } };
    if (url === '/api/job-postings/mine') return { status: 200, body: { jobPostings: [{ jobPostingId: 101, title: '[가상] 공고',
      schedules: [{ workScheduleId: 201, workDate: '2026-12-08', status: 'OPEN' }, { workScheduleId: 202, workDate: '2026-12-09', status: 'PAUSED' }] }] } };
    if (u.pathname === BASE && method === 'GET') {
      const status = u.searchParams.get('status'), limit = Number(u.searchParams.get('limit')), cursor = u.searchParams.get('cursor');
      if (cursor && !cursor.startsWith('c:' + status + ':')) return { status: 400, body: { code: 'INVALID_CURSOR', message: '유효하지 않은 커서입니다.' } };
      const rows = s.items.filter(r => status === 'ALL' || r.status === status);
      const start = cursor ? Number(cursor.split(':')[2]) : 0;
      const more = rows.length > start + limit;
      return { status: 200, body: { jobPostingId: 101, workScheduleId: 201, workDate: '2026-12-08', recruitmentStatus: 'OPEN', headcount: 3,
        confirmedCount: 1, remainingSlots: 2, items: rows.slice(start, start + limit), nextCursor: more ? 'c:' + status + ':' + (start + limit) : null, hasMore: more } };
    }
    let m = u.pathname.match(/^\/api\/job-postings\/101\/schedules\/201\/applications\/(\d+)\/contact$/);
    if (m) return s.contacts[m[1]] ?? notFound;
    m = u.pathname.match(/^\/api\/job-postings\/101\/schedules\/201\/applications\/(\d+)$/);
    if (m) return s.details[m[1]] ? { status: 200, body: s.details[m[1]] } : notFound;
    if (url === '/demo/api/work-applicants/fixtures' && method === 'POST') {
      if (options.headers?.['X-CSRF-TOKEN'] !== TOKEN) return { status: 403 };
      s.fixtures++;
      return { status: s.fixtures === 1 ? 201 : 200, body: { jobPostingId: 101, created: s.fixtures === 1,
        schedules: [{ workScheduleId: 201, workDate: '2026-12-08', status: 'OPEN', termsVersion: 2 }],
        scenarios: [
          { label: '지원 후 조건 변경', applicationId: 501, workScheduleId: 201, name: '시연 근로자 501', status: 'PENDING', applicationRound: 1, current: true },
          { label: '미선정·연락처 403', applicationId: 504, workScheduleId: 201, name: '시연 근로자 504', status: 'NOT_SELECTED', applicationRound: 1, current: true },
          { label: '재지원 현재 회차·연락처 미등록', applicationId: 505, workScheduleId: 201, name: '시연 근로자 505', status: 'PENDING', applicationRound: 2, current: true },
          { label: '과거 회차: 목록 제외·상세 404·연락처 403', applicationId: 401, workScheduleId: 201, name: '시연 근로자 505', status: 'NOT_SELECTED', applicationRound: 1, current: false }] } };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function page() {
  const forbidden = [];
  const { El, Option } = makeDom(forbidden);
  const els = {};
  const tags = { postings: 'select', dates: 'select', filter: 'select' };
  const get = id => (els[id] ??= new El(tags[id] ?? 'el', id));
  get('postings').replaceChildren(Option('공고 선택', '')); get('dates').replaceChildren(Option('날짜 선택', ''));
  get('filter').value = 'PENDING'; get('limit').value = '20';
  const server = fakeServer();
  const calls = [], overrides = [], held = [], windowListeners = {};
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: get, createElement: tag => new El(tag), write: () => forbidden.push('document.write'),
      get cookie() { forbidden.push('document.cookie'); return ''; } },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, location: { search: '' } },
    Option, AbortController, URLSearchParams,
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
    els, server, calls, held, forbidden,
    once(match, reply) { overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    show: async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); },
    hide: async () => { windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    blur: () => windowListeners.blur.forEach(fn => fn()),
    click: async id => { get(id).fire('click'); await settle(); },
    press: async b => { b.fire('click'); await settle(); },
    submit: async id => { get(id).fire('submit'); await settle(); },
    change: async (id, value) => { get(id).value = value; get(id).fire('change'); await settle(); },
    choose: async (postingId, scheduleId) => {
      get('postings').value = String(postingId); get('postings').fire('change');
      get('dates').value = String(scheduleId); get('dates').fire('change'); await settle();
    },
    rows: id => get(id).children.map(r => r.children.map(td => td.textContent)),
    buttons: (id, label) => get(id).querySelectorAll('button').filter(b => b.textContent === label),
    rowButton: (applicationId, label) => get('applicants').children.find(r => r.children[1]?.textContent.startsWith('#' + applicationId + ' '))
      .children[5].children.find(b => b.textContent === label),
    caseButton: (applicationId, label) => get('cases').children.find(r => r.children[3].textContent.startsWith('#' + applicationId + ' '))
      .children[4].children.find(b => b.textContent === label),
    urls: () => calls.map(c => (c.options.method || 'GET') + ' ' + c.url),
    posts: () => calls.filter(c => c.options.method === 'POST'),
    textExcept: skip => Object.values(els).filter(e => !skip.includes(e.id)).map(e => e.allText).join('\n')
  };
  return p;
}
async function listed(limit = '20', filter = 'PENDING') {
  const p = page();
  await p.show();
  await p.choose(101, 201);
  p.els.filter.value = filter; p.els.limit.value = limit;
  await p.submit('list-form');
  return p;
}
const starts = prefix => url => url.startsWith(prefix);

test('진입: /me → 내 공고 → /me만 호출, 준비 POST·지원자 조회 없음, same-origin·no-store', async () => {
  const p = page();
  await p.show();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/job-postings/mine', 'GET /api/auth/me']);
  for (const c of p.calls) { assert.equal(c.options.credentials, 'same-origin'); assert.equal(c.options.cache, 'no-store'); }
  assert.deepEqual(p.els.postings.children.map(o => o.value), ['', '101']);
  assert.match(p.els.account.textContent, /TEST EMPLOYER · 고용주 · 회원 ID 10/);
});

test('고용주가 아니면(근로자·401) 화면 데이터를 비우고 요청 중단', async () => {
  for (const reply of [{ status: 200, body: { id: 5, name: 'W', role: 'WORKER' } }, { status: 401 }]) {
    const p = page();
    p.once('/api/auth/me', reply);
    await p.show();
    assert.ok(!p.urls().includes('GET /api/job-postings/mine'));
    assert.equal(p.els.account.textContent, '고용주로 로그인해 주세요.');
    assert.equal(p.els.fixture.disabled, true);
  }
});

test('기본 PENDING·20명, 정원·확정·남은 자리 표시, 날짜 선택 후 처음부터 조회', async () => {
  const p = await listed();
  assert.equal(p.urls().at(-2), 'GET ' + BASE + '?status=PENDING&limit=20');
  assert.equal(p.rows('applicants').length, 3);
  assert.match(p.els.capacity.textContent, /2026-12-08 · 모집 중 · 정원 3명 \/ 확정 1명 \/ 남은 자리 2명/);
  assert.match(p.els['list-note'].textContent, /1페이지 · 3명 · 마지막 페이지/);
  assert.equal(p.rows('applicants')[0][0], '시연 근로자 501\n010-****-1001');
  assert.equal(p.rows('applicants')[2][0], '시연 근로자 505\n연락처 미등록');
});

test('전체·선정·미선정 필터는 처음부터 조회(cursor 없음)', async () => {
  const p = await listed();
  for (const [filter, count] of [['ALL', 5], ['SELECTED', 1], ['NOT_SELECTED', 1]]) {
    await p.change('filter', filter);
    await p.submit('list-form');
    assert.equal(p.urls().at(-2), 'GET ' + BASE + '?status=' + filter + '&limit=20');
    assert.equal(p.rows('applicants').length, count, filter);
  }
});

test('다음 페이지는 서버 nextCursor를 같은 공고·일정·필터·개수로 전달, 마지막 페이지면 비활성', async () => {
  const p = await listed('2', 'ALL');
  assert.equal(p.els.next.disabled, false);
  await p.click('next');
  assert.equal(p.urls().at(-2), 'GET ' + BASE + '?status=ALL&limit=2&cursor=c%3AALL%3A2');
  assert.match(p.els['list-note'].textContent, /^2페이지 · 2명 · 다음 페이지 있음/);
  await p.click('next');
  assert.match(p.els['list-note'].textContent, /^3페이지 · 1명 · 마지막 페이지/);
  assert.equal(p.els.next.disabled, true);
});

test('공고·날짜·필터·개수·ID 입력을 바꾸면 이전 커서·명단 제거, 다음 페이지 요청 없음', async () => {
  for (const act of [p => p.choose(101, 202), p => p.change('filter', 'ALL'), p => p.change('limit', '5'),
    p => p.change('posting-id', '101'), p => p.change('schedule-id', '201'),
    async p => { p.els.postings.value = '101'; p.els.postings.fire('change'); await settle(); }]) {
    const p = await listed('1');
    assert.equal(p.els.next.disabled, false);
    const before = p.calls.length;
    await act(p);
    assert.equal(p.els.next.disabled, true);
    assert.equal(p.rows('applicants').length, 0);
    await p.click('next');
    assert.equal(p.calls.length, before);
  }
});

test('상세: 조건 비교 영역 갱신(변경 행 강조·과거 좌표 확인 불가·부분 비교 안내), 조회만 수행 안내', async () => {
  const p = await listed('20', 'ALL');
  await p.press(p.rowButton(501, '상세'));
  assert.equal(p.urls().at(-2), 'GET ' + BASE + '/501');
  assert.equal(p.els['detail-panel'].hidden, false);
  const rows = p.els.conditions.children;
  assert.deepEqual(rows.filter(r => r.className === 'changed').map(r => r.children[0].textContent), ['예정 시작', '예정 종료']);
  assert.match(rows.find(r => r.children[0].textContent === '위도').children[1].textContent, /확인 불가/);
  assert.match(p.els['comparison-note'].textContent, /접수 버전 1 \/ 최신 버전 2\n지원 후 조건 변경\n일부 기록이 없어 전체 동일 여부를 판단할 수 없습니다/);
  assert.match(p.els['detail-heading'].textContent, /시연 근로자 501 · 지원 #501 · 1회차 · 지원 대기 · 010-\*\*\*\*-1001/);
  assert.match(p.els['detail-note'].textContent, /조회로 선정·동의 상태를 변경하지 않습니다/);
  assert.equal(p.els['assignment-note'].textContent, '연결된 확정 근무가 없습니다.');
  await p.press(p.rowButton(503, '상세'));
  assert.match(p.els['assignment-note'].textContent, /확정 근무 #700/);
  assert.equal(p.els['agreed-table'].hidden, false);
  assert.equal(p.rows('applicants').length, 5);
});

test('연락처: 전용 GET으로 요청해 연락처 영역에만 전체 번호 표시, 진단 JSON·요청 기록·다른 영역에는 없음', async () => {
  const p = await listed();
  await p.press(p.rowButton(501, '연락처 보기'));
  assert.equal(p.urls().at(-2), 'GET ' + BASE + '/501/contact');
  assert.equal(p.els['contact-note'].textContent, '시연 근로자 501 · 지원 #501\n' + PHONE);
  assert.match(p.els.response.textContent, /\[연락처 영역에서만 표시\]/);
  assert.ok(!p.textExcept(['contact-note']).includes(PHONE));
  assert.ok(!p.rows('requests').some(r => r.join(' ').includes(PHONE)));
  assert.deepEqual(p.forbidden, []);
});

test('미선정 목록 행의 연락처 버튼은 비활성, 사례의 미선정·과거 연락처 요청은 403 표시·명단 유지', async () => {
  const p = await listed('20', 'ALL');
  assert.equal(p.rowButton(504, '연락처 보기').disabled, true);
  assert.equal(p.rowButton(505, '연락처 보기').disabled, true, '미등록 번호는 목록 버튼 비활성');
  await p.click('fixture');
  for (const id of [504, 401]) {
    await p.press(p.caseButton(id, '연락처 403 확인'));
    assert.match(p.els['contact-note'].textContent, /HTTP 403 · CONTACT_ACCESS_DENIED/, String(id));
    assert.equal(p.els['contact-note'].dataset.error, 'true');
    assert.deepEqual(p.els.postings.children.map(o => o.value), ['', '101'], '403 CONTACT_ACCESS_DENIED는 전체 초기화하지 않음');
    assert.notEqual(p.els.account.textContent, '고용주로 로그인해 주세요.');
  }
});

test('과거 회차 상세는 404 표시, 명단 유지 · 미등록 연락처는 phone=null을 "연락처 미등록"으로 표시', async () => {
  const p = page();
  await p.show();
  await p.click('fixture');
  assert.ok(p.rows('applicants').length > 0);
  await p.press(p.caseButton(401, '상세 404 확인'));
  assert.match(p.els['detail-note'].textContent, /HTTP 404 · APPLICATION_NOT_FOUND/);
  assert.equal(p.els['detail-panel'].hidden, true);
  assert.deepEqual(p.els.postings.children.map(o => o.value), ['', '101'], '404는 전체 초기화하지 않음');
  await p.press(p.caseButton(505, '연락처'));
  assert.equal(p.els['contact-note'].textContent, '시연 근로자 505 · 지원 #505\n연락처 미등록');
});

test('상세·연락처 실패(500·통신·형식 오류)는 해당 영역만 오류, 정상 명단 유지', async () => {
  const p = await listed();
  p.once(BASE + '/501', { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await p.press(p.rowButton(501, '상세'));
  assert.match(p.els['detail-note'].textContent, /HTTP 500/);
  p.once(BASE + '/502', new TypeError('network'));
  await p.press(p.rowButton(502, '상세'));
  assert.match(p.els['detail-note'].textContent, /상세 조회 실패 · 정상 명단은 유지합니다/);
  p.once(BASE + '/501/contact', new TypeError('network'));
  await p.press(p.rowButton(501, '연락처 보기'));
  p.once(BASE + '/502/contact', { status: 200, body: { applicationId: 999 } });
  await p.press(p.rowButton(502, '연락처 보기'));
  assert.equal(p.els['contact-note'].textContent, '전체 연락처 조회 전');
  assert.equal(p.rows('applicants').length, 3);
  assert.equal(p.els.notice.dataset.error, 'true');
});

test('전체 번호 제거: 다른 동작·창 포커스 이탈·계정 변경·화면 이탈 시 연락처 표시 초기화', async () => {
  const actions = [
    ['다른 동작', p => p.submit('list-form')],
    ['포커스 이탈', async p => { p.blur(); }],
    ['계정 변경', async p => { p.once('/api/auth/me', { status: 200, body: { ...EMPLOYER, id: 11 } }); await p.press(p.rowButton(502, '상세')); }],
    ['화면 이탈', p => p.hide()]];
  for (const [name, act] of actions) {
    const p = await listed();
    await p.press(p.rowButton(501, '연락처 보기'));
    assert.match(p.els['contact-note'].textContent, new RegExp(PHONE), name);
    await act(p);
    assert.ok(!Object.values(p.els).some(e => e.allText.includes(PHONE)), name);
  }
});

test('늦은 응답: 연락처 대기 중 계정이 바뀌면 이전 계정 번호를 표시하지 않음, 화면 이탈 후 도착한 응답도 표시 안 함', async () => {
  const p = await listed();
  p.once(BASE + '/501/contact', 'hold');
  await p.press(p.rowButton(501, '연락처 보기'));
  p.once('/api/auth/me', { status: 200, body: { ...EMPLOYER, id: 11 } });
  p.held[0].resolve();
  await settle();
  assert.ok(!Object.values(p.els).some(e => e.allText.includes(PHONE)));
  assert.equal(p.rows('applicants').length, 0);
  assert.match(p.els.account.textContent, /계정이 변경되었습니다/);
  const q = await listed();
  q.once(BASE + '/501/contact', 'hold');
  await q.press(q.rowButton(501, '연락처 보기'));
  await q.hide();
  q.held[0].resolve();
  await settle();
  assert.ok(!Object.values(q.els).some(e => e.allText.includes(PHONE)));
  assert.equal(q.els.account.textContent, '고용주 로그인을 다시 확인해 주세요.');
});

test('[관찰] 연락처 응답 대기 중 창 포커스가 이탈하면, 이탈 뒤 도착한 응답의 전체 번호는 표시된다', async () => {
  const p = await listed();
  p.once(BASE + '/501/contact', 'hold');
  await p.press(p.rowButton(501, '연락처 보기'));
  p.blur();
  p.held[0].resolve();
  await settle();
  // 현재 구현 동작 기록(결함 판정 아님): blur는 이미 표시된 번호만 지우고 대기 중 요청은 취소하지 않는다.
  assert.match(p.els['contact-note'].textContent, new RegExp(PHONE));
});

test('시연 준비: 버튼을 눌렀을 때만 CSRF 조회 → 계정 확인 → POST(CSRF 헤더만) → 계정 확인 → 내 공고·첫 날짜 PENDING 20명 조회', async () => {
  const p = page();
  await p.show();
  assert.equal(p.posts().length, 0);
  p.calls.length = 0;
  await p.click('fixture');
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST /demo/api/work-applicants/fixtures',
    'GET /api/auth/me', 'GET /api/job-postings/mine', 'GET /api/auth/me', 'GET ' + BASE + '?status=PENDING&limit=20', 'GET /api/auth/me']);
  assert.deepEqual(p.posts()[0].options.headers, { 'X-CSRF-TOKEN': TOKEN });
  assert.equal(p.posts()[0].options.body, undefined);
  assert.match(p.els['fixture-note'].textContent, /POST HTTP 201 · 공고 #101 · 새 시연 데이터를 준비했습니다/);
  assert.match(p.els.notice.textContent, /첫 날짜의 지원 대기자를 조회했습니다/);
  assert.equal(p.rows('cases').length, 4);
  assert.equal(p.rows('cases')[3][3], '#401 · 1회차 · 과거');
  assert.ok(!p.allText?.includes?.(TOKEN));
  assert.ok(!Object.values(p.els).some(e => e.allText.includes(TOKEN)));
  await p.click('fixture');
  assert.match(p.els['fixture-note'].textContent, /POST HTTP 200 · 공고 #101 · 오늘 데이터를 재사용합니다. 기존 변경은 유지합니다/);
});

test('준비 실패와 후속 목록 실패를 구분: POST 500은 준비 오류, 목록 500은 사례 유지+재조회 실패, 목록 통신 실패는 자동 조회 실패', async () => {
  const a = page(); await a.show();
  a.once('/demo/api/work-applicants/fixtures', { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await a.click('fixture');
  assert.match(a.els['fixture-note'].textContent, /HTTP 500/);
  assert.equal(a.rows('cases').length, 0);
  const b = page(); await b.show();
  b.once(starts(BASE + '?'), { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await b.click('fixture');
  assert.equal(b.rows('cases').length, 4);
  assert.match(b.els.notice.textContent, /새 시연 데이터를 준비했습니다.\n목록 재조회 실패/);
  const c = page(); await c.show();
  c.once(starts(BASE + '?'), new TypeError('network'));
  await c.click('fixture');
  assert.equal(c.rows('cases').length, 4);
  assert.match(c.els.notice.textContent, /자동 조회 실패 · 위 사례의 상세·연락처 버튼으로 다시 확인하세요/);
  for (const x of [a, b, c]) assert.equal(x.posts().length, 1);
});

test('불확실한 POST(통신 실패·형식 오류)는 자동 재전송하지 않고 성공으로 표시하지 않음, CSRF 실패면 POST 없음', async () => {
  const p = page(); await p.show();
  p.once('/demo/api/work-applicants/fixtures', new TypeError('network'));
  await p.click('fixture');
  assert.equal(p.posts().length, 1);
  assert.match(p.els.notice.textContent, /자동 재전송하지 않습니다/);
  assert.doesNotMatch(p.els['fixture-note'].textContent, /준비했습니다|재사용합니다/);
  const q = page(); await q.show();
  q.once('/demo/api/work-applicants/fixtures', { status: 201, body: { jobPostingId: 101, created: true, schedules: [], scenarios: [{ applicationId: 0 }] } });
  await q.click('fixture');
  assert.equal(q.rows('cases').length, 0);
  for (const reply of [{ status: 500 }, { status: 200, body: { headerName: 'X-CSRF-TOKEN', token: '' } }, new TypeError('network')]) {
    const r = page(); await r.show();
    r.once('/api/auth/csrf', reply);
    await r.click('fixture');
    assert.equal(r.posts().length, 0);
    assert.equal(r.els.notice.dataset.error, 'true');
  }
});

test('중복 클릭 방지: POST 대기 중 준비·조회·상세 재요청 없음', async () => {
  const p = await listed();
  p.once('/demo/api/work-applicants/fixtures', 'hold');
  await p.click('fixture');
  const before = p.calls.length;
  await p.click('fixture');
  await p.submit('list-form');
  await p.submit('detail-form');
  await p.click('contact');
  assert.equal(p.calls.length, before);
  p.held[0].resolve();
  await settle();
  assert.equal(p.posts().length, 1);
});

test('401·권한 403(EMPLOYER_ROLE_REQUIRED)은 전체 초기화, CONTACT_ACCESS_DENIED 403은 초기화하지 않음', async () => {
  const p = await listed();
  p.once(starts(BASE + '?'), { status: 403, body: { code: 'EMPLOYER_ROLE_REQUIRED', message: '고용주 본인 공고의 지원자만 조회할 수 있습니다.' } });
  await p.submit('list-form');
  assert.equal(p.rows('applicants').length, 0);
  assert.equal(p.els['posting-id'].value, '');
  assert.deepEqual(p.els.postings.children.map(o => o.value), ['']);
  const q = await listed();
  q.once(BASE + '/501', { status: 401 });
  await q.press(q.rowButton(501, '상세'));
  assert.equal(q.rows('applicants').length, 0);
});

test('외부 문자열은 textContent로만 표시, 브라우저 저장소·쿠키 접근 없음, 한글 버튼·안내 유지', async () => {
  const p = page();
  p.server.items = [item(501, { name: '<img src=x onerror=alert(1)>' })];
  p.server.details[501] = { ...p.server.details[501], name: '<script>x</script>' };
  await p.show();
  await p.choose(101, 201);
  await p.submit('list-form');
  assert.match(p.rows('applicants')[0][0], /<img src=x onerror=alert\(1\)>/);
  await p.press(p.rowButton(501, '상세'));
  assert.match(p.els['detail-heading'].textContent, /<script>x<\/script>/);
  assert.deepEqual(p.forbidden, []);
  assert.ok(p.rowButton(501, '연락처 보기'));
  assert.match(p.els.notice.textContent, /[가-힣]/);
});

test('잘못된 ID 입력·개수는 요청 없이 한글 안내', async () => {
  const p = page();
  await p.show();
  for (const [field, value, message] of [['posting-id', '0', /양수인 공고·일정·지원 ID/], ['schedule-id', 'abc', /양수인/], ['limit', '101', /조회 개수는 1~100/]]) {
    p.els['posting-id'].value = '101'; p.els['schedule-id'].value = '201'; p.els.limit.value = '20';
    p.els[field].value = value;
    const before = p.calls.length;
    await p.submit('list-form');
    assert.ok(p.urls().slice(before).every(u => u === 'GET /api/auth/me'), field);
    assert.match(p.els.notice.textContent, message);
  }
});

test('[관찰] 시연 사례 버튼은 같은 일정이어도 setTarget으로 명단을 먼저 비운 뒤 요청한다(성공·실패와 무관), 목록 행 버튼은 유지', async () => {
  const p = page();
  await p.show();
  await p.click('fixture');
  assert.equal(p.rows('applicants').length, 3);
  await p.press(p.caseButton(501, '상세'));
  // 현재 구현 동작 기록: 상세가 200이어도 사례 버튼 경로는 clearList()를 먼저 호출한다.
  assert.equal(p.els['detail-panel'].hidden, false);
  assert.equal(p.rows('applicants').length, 0);
  await p.submit('list-form');
  assert.equal(p.rows('applicants').length, 3);
  await p.press(p.rowButton(501, '상세'));
  assert.equal(p.rows('applicants').length, 3);
});

// farm-my-applications.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 모두 가짜이며, 상태를 가진 가짜 서버 함수가 APP-04 응답을 흉내 낸다.
// 실제 브라우저·Spring 서버·세션·CSRF 검증·Security 체인·DB는 없다. 가짜 서버의 판단은 실제 API 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-27-app-04-my-applications/my-applications-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(path.resolve(here, '../../../src/main/resources/demo/farm-my-applications.html'), 'utf8');
const SCRIPT = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const TOKEN = 'csrf-token-SECRET-xyz';
const WORKER = { id: 5, name: 'TEST WORKER', role: 'WORKER' };
const settle = async () => { for (let i = 0; i < 40; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));

function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, attributes: {}, type: '', className: '', colSpan: 1 }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {} })); }
    replaceChildren(...items) { this.children = []; this.append(...items); }
    append(...items) { for (const item of items) { item.parent = this; this.children.push(item); } }
    prepend(item) { item.parent = this; this.children.unshift(item); }
    remove() { this.parent.children = this.parent.children.filter(x => x !== this); }
    get lastElementChild() { return this.children.at(-1); }
    get value() { return this._value; }
    set value(v) { this._value = String(v); }
    setAttribute(k, v) { this.attributes[k] = v; }
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

const conditions = (over = {}) => ({ availability: 'AVAILABLE', termsVersion: 1, workDate: '2026-12-10', timeZone: 'Asia/Seoul',
  workLocation: '가상 작업지', latitude: 37, longitude: 127, cropCode: 'FC03030100030103', cropName: '콩(일반)',
  workTypeCode: '0003', workTypeName: '경운', description: '가상 작업 내용', headcount: 3, dailyWage: 120000,
  plannedStartAt: '2026-12-10T09:00:00+09:00', plannedEndAt: '2026-12-10T17:00:00+09:00',
  breakStartAt: '2026-12-10T12:00:00+09:00', breakEndAt: '2026-12-10T13:00:00+09:00', unavailableFields: [], ...over });

function item(id, over = {}) {
  return { applicationId: id, applicationRound: 1, current: true, jobPostingId: 101, title: '[가상] 공고 ' + id, workScheduleId: 200 + id % 100,
    workDate: '2026-12-10', appliedAt: '2026-12-01T15:00:00.123456+09:00', legacyAppliedAt: null, appliedTimeBasis: 'UTC',
    applicationStatus: 'PENDING', displayStatus: 'PENDING', cancellationType: null, assignmentId: null, completedAt: null,
    appliedTermsVersion: 1, latestTermsVersion: 1, conditionComparisonStatus: 'UNCHANGED', ...over };
}

function detail(id, over = {}) {
  const base = item(id, over.item);
  return { ...base, appliedConditions: conditions(), latestConditions: conditions(), changedFields: [], unavailableComparisonFields: [],
    comparisonComplete: true, assignment: null, ...over.detail };
}

const AGREED = { availability: 'AVAILABLE', workDate: '2026-12-10', timeZone: 'Asia/Seoul', workLocation: '약정 당시 작업지',
  latitude: 36.5, longitude: 126.5, cropCode: 'FC03030100030103', cropName: '콩(일반)', workTypeCode: '0003', workTypeName: '경운',
  dailyWage: 110000, plannedStartAt: '2026-12-10T08:00:00+09:00', plannedEndAt: '2026-12-10T16:00:00+09:00',
  breakStartAt: null, breakEndAt: null, unavailableFields: [] };

// 가상 서버: 현재 회차 501·502·503, 과거 회차 401(501과 같은 일정)
function fakeServer() {
  const s = {
    items: [item(503), item(502, { displayStatus: 'CONFIRMED', applicationStatus: 'SELECTED', assignmentId: 702 }),
      item(501, { applicationRound: 2, conditionComparisonStatus: 'CHANGED', appliedTermsVersion: 1, latestTermsVersion: 2 })],
    past: { 501: [item(401, { current: false, displayStatus: 'NOT_SELECTED', applicationStatus: 'NOT_SELECTED' })] },
    currentOf: { 501: 501, 401: 501 },
    details: {}, fixtures: 0
  };
  s.details[501] = detail(501, { item: { applicationRound: 2, conditionComparisonStatus: 'CHANGED', latestTermsVersion: 2 }, detail: {
    appliedConditions: conditions({ availability: 'PARTIAL', latitude: null, longitude: null, description: null,
      unavailableFields: ['latitude', 'longitude', 'description'] }),
    latestConditions: conditions({ termsVersion: 2, plannedStartAt: '2026-12-10T10:00:00+09:00', plannedEndAt: '2026-12-10T18:00:00+09:00' }),
    changedFields: ['plannedStartAt', 'plannedEndAt'], unavailableComparisonFields: ['latitude', 'longitude', 'description'],
    comparisonComplete: false } });
  s.details[502] = detail(502, { item: { displayStatus: 'CANCELLED', cancellationType: 'ASSIGNMENT_CANCELLED', applicationStatus: 'SELECTED', assignmentId: 702 },
    detail: { assignment: { assignmentId: 702, confirmedAt: '2026-12-02T10:00:00+09:00', completedAt: null, agreedConditions: AGREED,
      change: { changeType: 'CANCELLED', reasonCode: 'FARM_CIRCUMSTANCES', weatherReasonCode: null, reasonDetail: '가상 사유',
        effectiveAt: '2026-12-05T10:00:00+09:00', recordedAt: '2026-12-05T10:00:00+09:00', updatedAt: '2026-12-05T11:00:00+09:00' } } } });
  s.details[503] = detail(503);
  s.details[401] = detail(401, { item: { current: false, displayStatus: 'NOT_SELECTED' } });
  s.handle = (url, options) => {
    const method = options.method || 'GET';
    const u = new URL(url, 'http://local');
    if (url === '/api/auth/me') return { status: 200, body: WORKER };
    if (url === '/api/auth/csrf') return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: TOKEN } };
    if (u.pathname === '/api/me/applications' && method === 'GET') {
      const status = u.searchParams.get('status'), limit = Number(u.searchParams.get('limit'));
      const cursor = u.searchParams.get('cursor');
      const rows = s.items.filter(row => status === 'ALL' || row.displayStatus === status);
      const start = cursor ? Number(cursor.split(':')[2]) : 0;
      if (cursor && !cursor.startsWith('c:' + status + ':')) return { status: 400, body: { code: 'INVALID_CURSOR', message: '유효하지 않은 커서입니다.' } };
      const page = rows.slice(start, start + limit), more = rows.length > start + limit;
      return { status: 200, body: { items: page, nextCursor: more ? 'c:' + status + ':' + (start + limit) : null, hasMore: more } };
    }
    let m = u.pathname.match(/^\/api\/me\/applications\/(\d+)\/history$/);
    if (m) {
      const id = Number(m[1]);
      if (!s.details[id]) return { status: 404, body: { code: 'APPLICATION_NOT_FOUND', message: '본인의 지원 내역을 찾을 수 없습니다.' } };
      return { status: 200, body: { currentApplicationId: s.currentOf[id] ?? null, items: s.past[id] ?? [], nextCursor: null, hasMore: false } };
    }
    m = u.pathname.match(/^\/api\/me\/applications\/(\d+)$/);
    if (m) {
      const found = s.details[Number(m[1])];
      return found ? { status: 200, body: found } : { status: 404, body: { code: 'APPLICATION_NOT_FOUND', message: '본인의 지원 내역을 찾을 수 없습니다.' } };
    }
    if (url === '/demo/api/my-applications/fixtures' && method === 'POST') {
      if (options.headers?.['X-CSRF-TOKEN'] !== TOKEN) return { status: 403 };
      s.fixtures++;
      return { status: s.fixtures === 1 ? 201 : 200, body: { jobPostingId: 4001, created: s.fixtures === 1, scenarios: [
        { label: '확정 후 취소', applicationId: 3061, workScheduleId: 306, workDate: '2026-12-13', applicationRound: 1, current: true,
          assignmentId: 53061, displayStatus: 'CANCELLED', conditionsChanged: false },
        { label: '재지원의 과거 회차', applicationId: 3091, workScheduleId: 309, workDate: '2026-12-14', applicationRound: 1, current: false,
          assignmentId: null, displayStatus: 'NOT_SELECTED', conditionsChanged: false }] } };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function page({ search = '' } = {}) {
  const forbidden = [];
  const { El } = makeDom(forbidden);
  const els = {};
  const tagOf = id => ({ filter: 'select', limit: 'input', 'application-id': 'input' })[id] ?? 'el';
  const get = id => (els[id] ??= new El(tagOf(id), id));
  get('filter').value = 'ALL'; get('limit').value = '20';
  const server = fakeServer();
  const calls = [], overrides = [], held = [], windowListeners = {};
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: get, createElement: tag => new El(tag), write: () => forbidden.push('document.write'),
      get cookie() { forbidden.push('document.cookie'); return ''; } },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, location: { search } },
    AbortController, URLSearchParams, JSON, Number, Object, Set, String, Error,
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
  const rows = id => get(id).children.map(r => r.children.map(td => td.textContent));
  const buttons = (id, label) => get(id).querySelectorAll('button').filter(b => b.textContent === label);
  const p = {
    els, server, calls, overrides, held, forbidden, rows, buttons,
    once(match, reply) { overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    show: async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); },
    hide: async () => { windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    click: async id => { get(id).fire('click'); await settle(); },
    press: async button => { button.fire('click'); await settle(); },
    submit: async id => { get(id).fire('submit'); await settle(); },
    change: async (id, value) => { get(id).value = value; get(id).fire('change'); await settle(); },
    urls: () => calls.map(c => (c.options.method || 'GET') + ' ' + c.url),
    posts: () => calls.filter(c => c.options.method === 'POST'),
    allText: () => Object.values(els).map(e => e.allText).join('\n')
  };
  return p;
}
const startsWith = prefix => url => url.startsWith(prefix);

test('페이지 진입: /me → 정식 목록 GET(ALL·20) → /me, 준비 POST·상세 자동 호출 없음, same-origin·no-store', async () => {
  const p = page();
  await p.show();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/me/applications?status=ALL&limit=20', 'GET /api/auth/me']);
  assert.equal(p.posts().length, 0);
  for (const call of p.calls) { assert.equal(call.options.credentials, 'same-origin'); assert.equal(call.options.cache, 'no-store'); }
  assert.equal(p.rows('applications').length, 3);
  assert.match(p.els['list-note'].textContent, /1페이지 · 이번 조회 3건 · 마지막 페이지/);
  assert.equal(p.els['next-list'].disabled, true);
  assert.equal(p.els['detail-panel'].hidden, true);
});

test('?applicationId=503이면 목록 뒤 그 상세만 조회(POST 없음), 잘못된 값이면 상세 요청 없음', async () => {
  const p = page({ search: '?applicationId=503' });
  await p.show();
  assert.ok(p.urls().includes('GET /api/me/applications/503'));
  assert.equal(p.els['detail-panel'].hidden, false);
  const q = page({ search: '?applicationId=0x1' });
  await q.show();
  assert.ok(!q.urls().some(u => /applications\/\d/.test(u)));
});

test('상태 필터·조회 개수·다음 페이지: 같은 status·limit와 서버 nextCursor로 이어 조회, 마지막에 다음 버튼 비활성', async () => {
  const p = page();
  await p.show();
  await p.change('limit', '1');
  await p.submit('list-form');
  assert.equal(p.urls().at(-2), 'GET /api/me/applications?status=ALL&limit=1');
  assert.equal(p.els['next-list'].disabled, false);
  await p.click('next-list');
  assert.equal(p.urls().at(-2), 'GET /api/me/applications?status=ALL&limit=1&cursor=c%3AALL%3A1');
  assert.match(p.els['list-note'].textContent, /^2페이지/);
  await p.click('next-list');
  assert.match(p.els['list-note'].textContent, /^3페이지 · 이번 조회 1건 · 마지막 페이지/);
  assert.equal(p.els['next-list'].disabled, true);
  await p.change('filter', 'CONFIRMED');
  await p.submit('list-form');
  assert.equal(p.urls().at(-2), 'GET /api/me/applications?status=CONFIRMED&limit=1');
  assert.deepEqual(p.rows('applications').map(r => r[1]), ['#502 · 1회차 · 현재']);
});

test('필터·개수를 바꾸면 이전 커서·목록 폐기, 다음 페이지 버튼 비활성·요청 없음', async () => {
  for (const [id, value] of [['filter', 'PENDING'], ['limit', '5']]) {
    const p = page();
    await p.show();
    await p.change('limit', '1');
    await p.submit('list-form');
    assert.equal(p.els['next-list'].disabled, false);
    const before = p.calls.length;
    await p.change(id, value);
    assert.equal(p.els['next-list'].disabled, true);
    assert.equal(p.rows('applications').length, 0);
    assert.match(p.els['list-note'].textContent, /조회 조건이 바뀌었습니다/);
    await p.click('next-list');
    assert.equal(p.calls.length, before);
  }
});

test('조회 개수 0·101·소수는 요청 없이 안내', async () => {
  for (const value of ['0', '101', '1.5', '']) {
    const p = page();
    await p.show();
    const before = p.calls.length;
    p.els.limit.value = value;
    await p.submit('list-form');
    assert.ok(p.urls().slice(before).every(u => u === 'GET /api/auth/me'), value);
    assert.match(p.els.notice.textContent, /조회 개수는 1~100/);
  }
});

test('조건 변경 강조와 확인 불가 표시: 변경 행만 changed, 과거 좌표·작업 내용은 확인 불가, 부분 비교 안내', async () => {
  const p = page();
  await p.show();
  await p.press(p.buttons('applications', '상세').find(b => b.parent.parent.children[1].textContent.startsWith('#501')));
  const rows = p.els.conditions.children;
  const changed = rows.filter(r => r.className === 'changed').map(r => r.children[0].textContent);
  assert.deepEqual(changed, ['예정 시작 일시', '예정 종료 일시']);
  const latitude = rows.find(r => r.children[0].textContent === '위도');
  assert.match(latitude.children[1].textContent, /확인 불가/);
  assert.equal(latitude.children[2].textContent, '37');
  assert.match(p.els['comparison-note'].textContent, /조건 변경됨 · 접수 버전 1 \/ 최신 버전 2/);
  assert.match(p.els['comparison-note'].textContent, /일부 조건은 기록이 없어 전체 동일 여부를 판단할 수 없습니다/);
  assert.match(p.els['comparison-note'].textContent, /비교 불가: 위도, 경도, 작업 내용/);
  assert.doesNotMatch(p.els['comparison-note'].textContent, /모두 같습니다/);
  assert.equal(p.els['detail-note'].textContent, '상세 HTTP 200 · 조회만 수행했습니다.');
});

test('부분 비교: 버전이 달라도 changedFields=[]·비교 불완전이면 "모두 같다"고 단정하지 않음, 완전 비교일 때만 표시', async () => {
  const p = page();
  p.server.details[503] = detail(503, { item: { conditionComparisonStatus: 'CHANGED', latestTermsVersion: 2 }, detail: {
    comparisonComplete: false, unavailableComparisonFields: ['description'],
    appliedConditions: conditions({ availability: 'PARTIAL', description: null, unavailableFields: ['description'] }) } });
  await p.show();
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  assert.doesNotMatch(p.els['comparison-note'].textContent, /모두 같습니다/);
  assert.match(p.els['comparison-note'].textContent, /전체 동일 여부를 판단할 수 없습니다/);
  assert.equal(p.els.conditions.children.filter(r => r.className === 'changed').length, 0);
  p.server.details[503] = detail(503);
  await p.submit('detail-form');
  assert.match(p.els['comparison-note'].textContent, /같은 조건 버전[\s\S]*현재 비교 항목은 모두 같습니다/);
});

test('휴게 없음과 확인 불가 구분, 모르는 코드 이름 표시', async () => {
  const p = page();
  p.server.details[503] = detail(503, { detail: { latestConditions: conditions({ breakStartAt: null, breakEndAt: null, cropName: null, cropCode: 'ZZ' }),
    appliedConditions: conditions({ availability: 'PARTIAL', breakStartAt: null, unavailableFields: ['breakStartAt'] }) } });
  await p.show();
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  const row = label => p.els.conditions.children.find(r => r.children[0].textContent === label).children;
  assert.match(row('휴게 시작 일시')[1].textContent, /확인 불가/);
  assert.equal(row('휴게 시작 일시')[2].textContent, '휴게 없음');
  assert.equal(row('작목')[2].textContent, '이름 미확인 (ZZ)');
});

test('과거 회차: 목록 행의 과거 회차 → 과거 항목만 표시, 현재 회차 상세 버튼으로 현재 회차 상세 이동, 과거 항목 상세 조회', async () => {
  const p = page();
  await p.show();
  await p.press(p.buttons('applications', '과거 회차').find(b => b.parent.parent.children[1].textContent.startsWith('#501')));
  assert.ok(p.urls().includes('GET /api/me/applications/501/history?limit=20'));
  assert.deepEqual(p.rows('history').map(r => r[1]), ['#401 · 1회차 · 과거']);
  assert.match(p.els['history-note'].textContent, /기준 지원 #501 · 과거 회차 1페이지 · 1건 · 현재 지원 ID 501 · 마지막 페이지/);
  assert.equal(p.els['current-detail'].disabled, false);
  assert.equal(p.buttons('history', '과거 회차').length, 0);
  await p.click('current-detail');
  assert.equal(p.urls().at(-2), 'GET /api/me/applications/501');
  assert.match(p.els['detail-heading'].textContent, /지원 #501 · 2회차 · 현재/);
  await p.press(p.buttons('history', '상세')[0]);
  assert.match(p.els['detail-heading'].textContent, /지원 #401 · 1회차 · 과거/);
});

test('상세와 과거 이력은 독립: 이력 500이어도 상세·목록 유지, 상세 실패(500·통신)여도 이력·목록 유지', async () => {
  const p = page();
  await p.show();
  p.els['application-id'].value = '501';
  await p.submit('detail-form');
  p.once(startsWith('/api/me/applications/501/history'), { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await p.click('open-history');
  assert.equal(p.els['detail-panel'].hidden, false);
  assert.match(p.els['history-note'].textContent, /HTTP 500 · INTERNAL_SERVER_ERROR/);
  assert.equal(p.els['history-note'].dataset.error, 'true');
  assert.equal(p.rows('applications').length, 3);
  await p.click('open-history');
  assert.equal(p.rows('history').length, 1);
  p.once('/api/me/applications/503', { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  assert.equal(p.els['detail-panel'].hidden, true);
  assert.match(p.els['detail-note'].textContent, /HTTP 500/);
  assert.equal(p.rows('history').length, 1);
  assert.equal(p.rows('applications').length, 3);
  p.once('/api/me/applications/503', new TypeError('network'));
  await p.submit('detail-form');
  assert.match(p.els['detail-note'].textContent, /상세를 불러오지 못했습니다. 목록과 과거 회차는 별도로 확인할 수 있습니다/);
  assert.equal(p.rows('history').length, 1);
  assert.equal(p.rows('applications').length, 3);
});

test('목록 실패(500)는 목록만 오류, 이전 상세 유지 · 없는 지원 404는 실제 상태·코드 표시', async () => {
  const p = page();
  await p.show();
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  p.once(startsWith('/api/me/applications?'), { status: 503, body: { code: 'SERVICE_UNAVAILABLE', message: '지원 내역을 조회할 수 없습니다.' } });
  await p.submit('list-form');
  assert.match(p.els['list-note'].textContent, /HTTP 503 · SERVICE_UNAVAILABLE/);
  assert.equal(p.els['detail-panel'].hidden, false);
  p.els['application-id'].value = '999';
  await p.submit('detail-form');
  assert.match(p.els['detail-note'].textContent, /HTTP 404 · APPLICATION_NOT_FOUND/);
});

test('저장된 약정·취소 기록 표시(취소 후에도 약정), 확정 근무 없으면 약정을 만들지 않음, 완료 일시 표시', async () => {
  const p = page();
  await p.show();
  p.els['application-id'].value = '502';
  await p.submit('detail-form');
  assert.match(p.els['detail-heading'].textContent, /확정 후 취소/);
  assert.match(p.els['assignment-note'].textContent, /확정 근무 #702[\s\S]*완료 일시 미완료 · 기록 없음/);
  assert.equal(p.els['agreed-table'].hidden, false);
  const agreed = p.els.agreed.children.map(r => [r.children[0].textContent, r.children[1].textContent]);
  assert.deepEqual(agreed.find(r => r[0] === '작업지'), ['작업지', '약정 당시 작업지']);
  assert.deepEqual(agreed.find(r => r[0] === '휴게 시작 일시'), ['휴게 시작 일시', '휴게 없음']);
  assert.ok(!agreed.some(r => r[0] === '정원' || r[0] === '작업 내용'));
  assert.match(p.els['change-note'].textContent, /확정 후 취소 · 농장 사정[\s\S]*사유 설명 가상 사유[\s\S]*최종 수정 일시 2026-12-05T11:00:00\+09:00/);
  p.server.details[502].assignment = { ...p.server.details[502].assignment, completedAt: '2026-12-10T17:00:00.000009+09:00', change: null };
  await p.submit('detail-form');
  assert.match(p.els['assignment-note'].textContent, /완료 일시 2026-12-10T17:00:00.000009\+09:00/);
  assert.equal(p.els['change-note'].textContent, '개인별 취소·조기 종료 기록 없음');
  p.server.details[502].assignment.change = { changeType: 'EARLY_ENDED', reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: null,
    effectiveAt: '2026-11-29T14:00:00+09:00', recordedAt: null, updatedAt: null };
  await p.submit('detail-form');
  assert.match(p.els['change-note'].textContent, /조기 종료 · 기상 · 기상 코드 RAIN[\s\S]*사유 설명 없음[\s\S]*적용 일시 2026-11-29T14:00:00\+09:00[\s\S]*최초 기록 일시 미확인/);
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  assert.equal(p.els['agreed-table'].hidden, true);
  assert.match(p.els['assignment-note'].textContent, /연결된 확정 근무가 없습니다/);
});

test('목록 표시: 모집 취소·확정 후 취소 구분, 완료 일시, 기존 지원 시각(시간대 미확인), 버전 미확인', async () => {
  const p = page();
  p.server.items = [
    item(601, { displayStatus: 'CANCELLED', cancellationType: 'SCHEDULE_CANCELLED' }),
    item(602, { displayStatus: 'CANCELLED', cancellationType: 'ASSIGNMENT_CANCELLED', assignmentId: 1 }),
    item(603, { displayStatus: 'COMPLETED', completedAt: '2026-12-10T17:00:00+09:00', assignmentId: 2 }),
    item(604, { appliedAt: null, legacyAppliedAt: '2025-05-01T08:30:00', appliedTimeBasis: 'LEGACY_LOCAL_UNKNOWN_ZONE',
      appliedTermsVersion: null, conditionComparisonStatus: 'UNKNOWN', displayStatus: 'UNKNOWN' })];
  await p.show();
  const rows = p.rows('applications');
  assert.equal(rows[0][2], '모집 취소');
  assert.equal(rows[1][2], '확정 후 취소');
  assert.equal(rows[2][2], '근무 완료\n완료 일시 2026-12-10T17:00:00+09:00');
  assert.equal(rows[3][2], '상태 확인 필요');
  assert.equal(rows[3][3], '2025-05-01T08:30:00 · 원본 시각, 시간대 미확인');
  assert.equal(rows[3][4], '접수 미확인 → 최신 1\n지원 당시 버전 미확인');
});

test('시연 준비: CSRF 조회 → 계정 확인 → POST(CSRF 헤더만) → 계정 확인 → 목록 재조회, 201 새 준비·사례별 ID·바로 보기 버튼', async () => {
  const p = page();
  await p.show();
  p.calls.length = 0;
  await p.click('fixture');
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST /demo/api/my-applications/fixtures',
    'GET /api/auth/me', 'GET /api/me/applications?status=ALL&limit=20', 'GET /api/auth/me']);
  const post = p.posts()[0].options;
  assert.deepEqual(post.headers, { 'X-CSRF-TOKEN': TOKEN });
  assert.equal(post.body, undefined);
  assert.deepEqual(p.rows('fixture-cases').map(r => r.slice(0, 5)), [
    ['확정 후 취소\n취소', '2026-12-13', '#3061 · 1회차', '일정 #306 · 확정 53061', '현재'],
    ['재지원의 과거 회차\n미선정', '2026-12-14', '#3091 · 1회차', '일정 #309 · 확정 없음', '과거']]);
  assert.match(p.els['fixture-note'].textContent, /POST HTTP 201 · 공고 #4001 · 새로 준비했습니다. 현재 1건 · 과거 1건/);
  await p.press(p.buttons('fixture-cases', '상세')[0]);
  assert.equal(p.urls().at(-2), 'GET /api/me/applications/3061');
  await p.press(p.buttons('fixture-cases', '과거 회차')[1]);
  assert.equal(p.urls().at(-2), 'GET /api/me/applications/3091/history?limit=20');
});

test('시연 준비 재사용: 200이면 이전 변경·이력 유지 안내', async () => {
  const p = page();
  await p.show();
  await p.click('fixture');
  await p.click('fixture');
  assert.match(p.els['fixture-note'].textContent, /POST HTTP 200 · 공고 #4001 · 오늘 준비한 데이터를 다시 사용합니다. 이전 변경·이력은 유지합니다/);
  assert.equal(p.posts().length, 2);
});

test('준비 성공 후 목록 재조회만 실패(500·통신)해도 준비 결과·사례 ID 유지', async () => {
  for (const reply of [{ status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } }, new TypeError('network')]) {
    const p = page();
    await p.show();
    p.once(startsWith('/api/me/applications?'), reply);
    await p.click('fixture');
    assert.equal(p.rows('fixture-cases').length, 2);
    assert.match(p.els['fixture-note'].textContent, /새로 준비했습니다/);
    assert.match(p.els.notice.textContent, /목록 (재조회는|자동 조회는) 실패했습니다/);
    assert.equal(p.els.notice.dataset.error, 'true');
    assert.equal(p.posts().length, 1);
  }
});

test('CSRF 발급 실패(500·토큰 누락·통신 실패)면 POST 없음', async () => {
  for (const reply of [{ status: 500 }, { status: 200, body: { headerName: 'X-CSRF-TOKEN', token: '' } }, new TypeError('network')]) {
    const p = page();
    await p.show();
    p.once('/api/auth/csrf', reply);
    await p.click('fixture');
    assert.equal(p.posts().length, 0);
    assert.equal(p.els.notice.dataset.error, 'true');
    assert.equal(p.rows('fixture-cases').length, 0);
  }
});

test('CSRF 토큰·쿠키·인증 헤더는 화면·요청 기록에 노출되지 않음, 조회 요청에는 헤더 없음', async () => {
  const p = page();
  await p.show();
  await p.click('fixture');
  assert.ok(!p.allText().includes(TOKEN));
  assert.ok(!p.rows('requests').some(r => r[0].includes('/api/auth/')));
  for (const call of p.calls) {
    const headers = JSON.stringify(call.options.headers ?? {});
    assert.doesNotMatch(headers, /Authorization|Cookie/i);
    if ((call.options.method || 'GET') === 'GET') assert.equal(call.options.headers, undefined);
  }
  assert.deepEqual(p.forbidden, []);
});

test('POST 통신 실패: 자동 재전송·가짜 성공 없음, 안내만 표시', async () => {
  const p = page();
  await p.show();
  p.once('/demo/api/my-applications/fixtures', new TypeError('network'));
  await p.click('fixture');
  assert.equal(p.posts().length, 1);
  assert.match(p.els.notice.textContent, /자동 재전송하지 않습니다/);
  assert.doesNotMatch(p.els['fixture-note'].textContent, /새로 준비했습니다|다시 사용합니다/);
  assert.equal(p.rows('fixture-cases').length, 0);
});

test('POST 응답 형식 오류·409는 성공으로 표시하지 않음', async () => {
  for (const reply of [{ status: 201, body: { jobPostingId: 4001, created: true, scenarios: [{ applicationId: 0 }] } },
    { status: 409, body: { code: 'DEMO_EMPLOYER_UNAVAILABLE', message: 'farm-demo의 demo-employer 고용주 계정을 확인해 주세요.' } }]) {
    const p = page();
    await p.show();
    p.once('/demo/api/my-applications/fixtures', reply);
    await p.click('fixture');
    assert.equal(p.rows('fixture-cases').length, 0);
    assert.doesNotMatch(p.els['fixture-note'].textContent, /새로 준비했습니다/);
    assert.equal(p.els.notice.dataset.error, 'true');
  }
});

test('중복 클릭 방지: POST 대기 중 준비·조회·상세 재클릭은 추가 요청 없음', async () => {
  const p = page();
  await p.show();
  p.once('/demo/api/my-applications/fixtures', 'hold');
  await p.click('fixture');
  const before = p.calls.length;
  assert.equal(p.els.fixture.disabled, true);
  await p.click('fixture');
  await p.submit('list-form');
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  await p.click('open-history');
  assert.equal(p.calls.length, before);
  p.held[0].resolve();
  await settle();
  assert.equal(p.posts().length, 1);
  assert.equal(p.els.fixture.disabled, false);
});

test('401·403: 목록·상세·이력 응답이 인증 오류면 이전 결과·커서·사례를 모두 지우고 계정 안내', async () => {
  const cases = [
    ['목록 401', startsWith('/api/me/applications?'), 401, p => p.submit('list-form')],
    ['상세 403', startsWith('/api/me/applications/503'), 403, p => { p.els['application-id'].value = '503'; return p.submit('detail-form'); }],
    ['이력 403', startsWith('/api/me/applications/501/history'), 403, p => { p.els['application-id'].value = '501'; return p.click('open-history'); }]];
  for (const [name, match, status, act] of cases) {
    const p = page();
    await p.show();
    await p.click('fixture');
    p.els['application-id'].value = '503';
    await p.submit('detail-form');
    await p.change('limit', '1');
    await p.submit('list-form');
    assert.equal(p.els['next-list'].disabled, false, name);
    p.once(match, { status, body: { code: 'WORKER_ROLE_REQUIRED', message: '근로자 본인만 지원 내역을 조회할 수 있습니다.' } });
    await act(p);
    assert.equal(p.rows('applications').length, 0, name);
    assert.equal(p.rows('fixture-cases').length, 0, name);
    assert.equal(p.els['detail-panel'].hidden, true, name);
    assert.equal(p.els['next-list'].disabled, true, name);
    assert.equal(p.els['application-id'].value, '', name);
    assert.equal(p.els.account.textContent, '로그인과 근로자 권한을 다시 확인해 주세요.', name);
  }
});

test('준비 POST의 403 WORKER_ROLE_REQUIRED는 전체 초기화, CSRF 403(본문 없음)은 오류 안내만', async () => {
  const p = page();
  await p.show();
  p.once('/demo/api/my-applications/fixtures', { status: 403, body: { code: 'WORKER_ROLE_REQUIRED', message: '근로자 본인만 시연 데이터를 준비할 수 있습니다.' } });
  await p.click('fixture');
  assert.equal(p.rows('applications').length, 0);
  assert.equal(p.els.fixture.disabled, true);
  const q = page();
  await q.show();
  q.once('/demo/api/my-applications/fixtures', { status: 403 });
  await q.click('fixture');
  assert.match(q.els['fixture-note'].textContent, /HTTP 403/);
  assert.equal(q.rows('applications').length, 3);
});

test('계정 변경: 상세 응답 대기 중 다른 계정으로 바뀌면 이전 계정 상세를 표시하지 않고 모두 지움', async () => {
  const p = page();
  await p.show();
  p.once('/api/me/applications/503', 'hold');
  p.els['application-id'].value = '503';
  await p.submit('detail-form');
  p.once('/api/auth/me', { status: 200, body: { ...WORKER, id: 99 } });
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['detail-panel'].hidden, true);
  assert.equal(p.rows('applications').length, 0);
  assert.match(p.els.account.textContent, /계정이 변경되었습니다/);
  assert.ok(!p.rows('requests').some(r => r[0].includes('/api/me/applications/503')));
});

test('준비 POST 응답 후 계정이 바뀌면 준비 결과를 표시하지 않음', async () => {
  const p = page();
  await p.show();
  let meCount = 0;
  p.once(url => url === '/api/auth/me' && ++meCount === 3, { status: 200, body: { ...WORKER, id: 99 } });
  await p.click('fixture');
  assert.equal(p.posts().length, 1);
  assert.equal(p.rows('fixture-cases').length, 0);
  assert.doesNotMatch(p.els['fixture-note'].textContent, /새로 준비했습니다/);
});

test('pagehide 이후 도착한 응답은 표시하지 않고 화면을 비움, 자동 재전송 없음', async () => {
  const p = page();
  await p.show();
  p.once('/demo/api/my-applications/fixtures', 'hold');
  await p.click('fixture');
  await p.hide();
  const before = p.calls.length;
  p.held[0].resolve();
  await settle();
  assert.equal(p.calls.length, before);
  assert.equal(p.rows('fixture-cases').length, 0);
  assert.equal(p.rows('applications').length, 0);
  assert.equal(p.els.account.textContent, '근로자 로그인을 다시 확인해 주세요.');
  assert.equal(p.els.fixture.disabled, true);
});

test('잘못된 지원 ID 입력은 요청 없이 안내', async () => {
  for (const value of ['0', '-1', '1.5', 'abc', '9007199254740993', '']) {
    const p = page();
    await p.show();
    p.els['application-id'].value = value;
    const before = p.calls.length;
    await p.submit('detail-form');
    assert.ok(p.urls().slice(before).every(u => u === 'GET /api/auth/me'), value);
    assert.match(p.els.notice.textContent, /양수인 본인 지원 ID/);
  }
});

test('서버 문자열은 textContent로만 표시, 브라우저 저장소 미사용', async () => {
  const p = page();
  p.server.items = [item(701, { title: '<img src=x onerror=alert(1)>' })];
  p.server.details[701] = detail(701, { item: { title: '<script>x</script>' }, detail: {
    latestConditions: conditions({ workLocation: '<b>위치</b>' }) } });
  await p.show();
  p.els['application-id'].value = '701';
  await p.submit('detail-form');
  assert.match(p.rows('applications')[0][0], /<img src=x onerror=alert\(1\)>/);
  assert.match(p.els['detail-heading'].textContent, /<script>x<\/script>/);
  assert.equal(p.els.conditions.children.find(r => r.children[0].textContent === '작업지').children[2].textContent, '<b>위치</b>');
  assert.deepEqual(p.forbidden, []);
});

test('목록 응답 형식 오류(current 불일치·커서 누락)는 표시하지 않고 목록 오류 안내', async () => {
  for (const body of [{ items: [item(1, { current: false })], nextCursor: null, hasMore: false },
    { items: [], nextCursor: null, hasMore: true }, { items: null, nextCursor: null, hasMore: false }]) {
    const p = page();
    p.once(startsWith('/api/me/applications?'), { status: 200, body });
    await p.show();
    assert.equal(p.rows('applications').length, 0);
    assert.equal(p.els['list-note'].dataset.error, 'true');
  }
});

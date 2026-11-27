// farm-work-completion.html·farm-job-location.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 모두 가짜이며 상태를 가진 가짜 서버 함수가 응답한다. 실제 브라우저·HTTP·세션·CSRF 검증·DB는 없다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-27-work-completion/work-completion-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const resource = name => readFileSync(path.resolve(here, '../../../src/main/resources/demo', name), 'utf8');
const scriptOf = html => html.match(/<script>([\s\S]*?)<\/script>/)[1];
const COMPLETION = scriptOf(resource('farm-work-completion.html'));
const LOCATION = scriptOf(resource('farm-job-location.html'));
const BASE = '/demo/api/employer/work-assignments';
const TOKEN = 'csrf-token-SECRET-123';
const EMPLOYER = { id: 10, name: 'TEST EMPLOYER', role: 'EMPLOYER' };
const settle = async () => { for (let i = 0; i < 30; i++) await new Promise(r => setImmediate(r)); };

// 가짜 DOM 요소. innerHTML·outerHTML·insertAdjacentHTML 접근은 기록해 실패시킨다.
function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, attributes: {} }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type, event = {}) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {}, ...event })); }
    replaceChildren(...items) { this.children = []; this.append(...items); if (this.tagName === 'select') this._value = items[0]?.value ?? ''; }
    append(...items) { for (const item of items) { item.parent = this; this.children.push(item); } }
    prepend(item) { item.parent = this; this.children.unshift(item); }
    remove() { this.parent.children = this.parent.children.filter(x => x !== this); }
    get lastElementChild() { return this.children.at(-1); }
    get firstElementChild() { return this.children[0]; }
    get options() { return this.children; }
    get value() { return this._value; }
    set value(v) { this._value = String(v); }
    setAttribute(k, v) { this.attributes[k] = v; }
    removeAttribute(k) { delete this.attributes[k]; }
    get allText() { return [this.textContent, ...this.children.map(c => c.allText)].join(' '); }
  }
  for (const name of ['innerHTML', 'outerHTML']) {
    Object.defineProperty(El.prototype, name, { get() { forbidden.push(name); return ''; }, set() { forbidden.push(name); } });
  }
  El.prototype.insertAdjacentHTML = function () { forbidden.push('insertAdjacentHTML'); };
  function Option(text, value) { const option = new El('option'); option.textContent = text; option.value = value; return option; }
  return { El, Option };
}

const trap = (forbidden, name) => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });

// 상태를 가진 가짜 서버: 41(종료 후·미완료), 40(종료 전), 39(취소됨).
function fakeServer() {
  const rows = {
    41: { assignmentId: 41, jobPostingId: 7, title: '사과 수확', workerName: '근로자 가', agreedWorkDate: '2026-11-30', agreedTimeZone: 'GMT+9',
      agreedEndAt: '2026-11-30T17:00:00+09:00', completedAt: null, changeType: null, finished: true },
    40: { assignmentId: 40, jobPostingId: 7, title: '사과 수확', workerName: '근로자 나', agreedWorkDate: '2026-12-30', agreedTimeZone: 'GMT+9',
      agreedEndAt: '2026-12-30T17:00:00+09:00', completedAt: null, changeType: null, finished: false },
    39: { assignmentId: 39, jobPostingId: 8, title: '배 수확', workerName: '근로자 다', agreedWorkDate: null, agreedTimeZone: 'GMT+9',
      agreedEndAt: '2026-11-29T17:00:00+09:00', completedAt: null, changeType: 'CANCELLED', finished: true }
  };
  const clock = { now: '2026-11-30T18:15:30.123456+09:00' };
  const publicRow = row => { const { finished, ...rest } = row; return rest; };
  return {
    rows, clock,
    handle(url, options) {
      if (url === '/api/auth/me') return { status: 200, body: EMPLOYER };
      if (url === '/api/auth/csrf') return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: TOKEN } };
      if (url === BASE) return { status: 200, body: { assignments: Object.values(rows).sort((a, b) => b.assignmentId - a.assignmentId)
        .map(({ assignmentId, jobPostingId, title, workerName, agreedWorkDate }) => ({ assignmentId, jobPostingId, title, workerName, agreedWorkDate })) } };
      let m = url.match(/^\/demo\/api\/employer\/work-assignments\/(\d+)$/);
      if (m) return rows[m[1]] ? { status: 200, body: publicRow(rows[m[1]]) }
        : { status: 404, body: { code: 'WORK_ASSIGNMENT_NOT_FOUND', message: '확정 근무를 찾을 수 없습니다.' } };
      m = url.match(/^\/api\/work-assignments\/(\d+)\/completion$/);
      if (m && options.method === 'POST') {
        if (options.headers?.['X-CSRF-TOKEN'] !== TOKEN) return { status: 403 };
        const row = rows[m[1]];
        if (row.changeType) return { status: 409, body: { code: 'WORK_ALREADY_CANCELLED', message: '취소되거나 조기 종료된 근무는 완료할 수 없습니다.' } };
        if (!row.completedAt && !row.finished) return { status: 409, body: { code: 'WORK_NOT_FINISHED', message: '약정 근무 종료 시각 이후에 완료할 수 있습니다.' } };
        row.completedAt ??= clock.now;
        return { status: 200, body: { assignmentId: row.assignmentId, completedAt: row.completedAt } };
      }
      throw new Error('unexpected ' + (options.method || 'GET') + ' ' + url);
    }
  };
}

function completionPage({ confirm = () => true } = {}) {
  const forbidden = [];
  const { El, Option } = makeDom(forbidden);
  const texts = { member: '고용주 로그인 상태를 확인합니다.', 'completed-at': '상세 조회 전', comparison: '상세를 조회한 후 완료할 수 있습니다.',
    'post-status': '완료 요청 전', 'post-result': '완료 응답을 여기에 표시합니다.', status: '확인 중입니다.', 'read-result': '조회 전' };
  const ids = ['member', 'load', 'assignments', 'empty', 'read', 'assignment-id', 'worker-name', 'posting-title', 'work-date', 'agreed-end',
    'time-zone', 'change-type', 'completed-at', 'complete', 'comparison', 'post-status', 'post-result', 'status', 'read-result', 'history'];
  const els = Object.fromEntries(ids.map(id => {
    const el = new El(id === 'assignments' ? 'select' : id === 'history' ? 'tbody' : 'el', id);
    el.textContent = texts[id] ?? '—';
    return [id, el];
  }));
  els.assignments.replaceChildren(Option('목록을 조회해 주세요', ''));
  els.assignments.disabled = true; els.read.disabled = true; els.complete.disabled = true; els.empty.hidden = true;
  const server = fakeServer();
  const calls = [];
  const overrides = [];   // [{ match(url, options) → bool, reply: spec | Error | 'hold' }] 한 번씩 사용
  const held = [];
  const windowListeners = {};
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: id => { if (!els[id]) throw new Error('missing #' + id); return els[id]; }, createElement: tag => new El(tag),
      cookie: undefined, write: () => forbidden.push('document.write') },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, confirm: message => { calls.push({ confirm: message }); return confirm(message); } },
    Option, AbortController, Number, JSON, Object, Array, Date, String, Error,
    console: trap(forbidden, 'console'), localStorage: trap(forbidden, 'localStorage'), sessionStorage: trap(forbidden, 'sessionStorage'),
    history: trap(forbidden, 'history'), location: trap(forbidden, 'location'),
    fetch: (url, options = {}) => {
      calls.push({ url, options });
      const index = overrides.findIndex(o => o.match(url, options));
      let spec;
      if (index >= 0) { spec = overrides[index].reply; overrides.splice(index, 1); } else spec = server.handle(url, options);
      if (typeof spec === 'function') spec = spec(url, options);
      if (spec === 'hold') return new Promise((resolve, reject) => held.push({ url, resolve: s => resolve(respond(s ?? server.handle(url, options))), reject }));
      return spec instanceof Error ? Promise.reject(spec) : Promise.resolve(respond(spec));
    }
  };
  vm.createContext(context);
  vm.runInContext(COMPLETION, context);
  const api = {
    els, calls, forbidden, server, overrides, held,
    once(match, reply) { overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    show: async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); },
    hide: async () => { windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    select: async id => { els.assignments.value = String(id); els.assignments.fire('change'); await settle(); },
    complete: async () => { els.complete.fire('click'); await settle(); },
    urls: () => calls.filter(c => c.url).map(c => (c.options.method || 'GET') + ' ' + c.url),
    reset: () => { calls.length = 0; },
    allText: () => Object.values(els).map(e => e.allText).join('\n')
  };
  return api;
}

test('초기 표시: /me 확인 후 본인 목록 조회, same-origin·no-store, 목록 옵션과 건수 표시', async () => {
  const p = completionPage();
  await p.show();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET ' + BASE, 'GET /api/auth/me']);
  for (const call of p.calls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.cache, 'no-store');
    assert.equal(call.options.headers, undefined);
    assert.ok(!call.url.includes('?'), '고용주 ID를 쿼리로 보내지 않음');
  }
  assert.deepEqual(p.els.assignments.options.map(o => o.value), ['', '41', '40', '39']);
  assert.equal(p.els.assignments.options[1].textContent, '#41 · 근로자 가 · 2026-11-30 · 사과 수확');
  assert.equal(p.els.assignments.options[3].textContent, '#39 · 근로자 다 · 날짜 미등록 · 배 수확');
  assert.match(p.els.status.textContent, /^목록 HTTP 200 · 본인 공고의 확정 근무 3건/);
  assert.equal(p.els.member.textContent, 'TEST EMPLOYER · 고용주 · 회원 ID 10');
  assert.equal(p.els.complete.disabled, true, '상세 조회 전에는 완료 불가');
});

test('빈 목록: 안내 표시, 선택 비활성', async () => {
  const p = completionPage();
  p.once(BASE, { status: 200, body: { assignments: [] } });
  await p.show();
  assert.equal(p.els.empty.hidden, false);
  assert.equal(p.els.assignments.disabled, true);
  assert.equal(p.els.assignments.options[0].textContent, '본인 공고의 확정 근무 없음');
});

test('상세: 미완료는 "미완료", JSON 원문은 completedAt:null 키 유지, 취소 종류 표시', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  assert.equal(p.els['completed-at'].textContent, '미완료');
  assert.equal(p.els['agreed-end'].textContent, '2026-11-30T17:00:00+09:00');
  assert.equal(p.els['change-type'].textContent, '없음');
  assert.equal(p.els['posting-title'].textContent, '#7 · 사과 수확');
  assert.match(p.els['read-result'].textContent, /"completedAt": null/);
  assert.equal(p.els.complete.disabled, false);
  assert.equal(p.els.complete.textContent, '선택한 근무 완료 처리');

  await p.select(39);
  assert.equal(p.els['change-type'].textContent, 'CANCELLED');
  assert.equal(p.els['work-date'].textContent, '미등록');
});

test('완료: CSRF 조회 → /me → 헤더 포함 본문 없는 POST → /me → 상세 GET 재조회, 저장값 일치 안내와 "완료 일시" 표시', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.reset();
  await p.complete();
  assert.deepEqual(p.urls(), ['GET /api/auth/me', 'GET /api/auth/csrf', 'GET /api/auth/me', 'POST /api/work-assignments/41/completion',
    'GET /api/auth/me', 'GET ' + BASE + '/41', 'GET /api/auth/me']);
  const post = p.calls.find(c => c.options?.method === 'POST');
  assert.deepEqual(JSON.parse(JSON.stringify(post.options.headers)), { 'X-CSRF-TOKEN': TOKEN }, 'vm 컨텍스트 객체라 JSON으로 비교');
  assert.equal(post.options.body, undefined, '요청 본문 없음');
  assert.equal(post.options.credentials, 'same-origin');
  assert.equal(p.els['completed-at'].textContent, '2026-11-30T18:15:30.123456+09:00');
  assert.equal(p.els['post-status'].textContent, '완료 POST HTTP 200 · 상세 GET HTTP 200');
  assert.equal(p.els.comparison.textContent, 'POST 응답과 DB에서 다시 조회한 완료 일시가 같습니다.');
  assert.deepEqual(JSON.parse(p.els['post-result'].textContent), { assignmentId: 41, completedAt: '2026-11-30T18:15:30.123456+09:00' });
  assert.equal(p.els.status.dataset.error, 'false');
  assert.equal(p.els.complete.textContent, '동일 근무 완료 재요청');
  assert.ok(!p.allText().includes(TOKEN), 'CSRF 토큰이 화면·기록 표에 없음');
  assert.ok(!p.allText().includes('X-CSRF-TOKEN'));
  assert.deepEqual(p.els.history.children.slice(0, 2).map(r => r.children.map(td => td.textContent)),
    [['상세 GET · #41', '200', '—'], ['완료 POST · #41', '200', '—']]);
  assert.deepEqual(p.forbidden, [], '저장소·로그·HTML 삽입 미사용');
});

test('재요청: 서버 시각이 바뀌어도 최초 완료 시각 유지 비교 안내', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  await p.complete();
  p.server.clock.now = '2026-12-05T09:00:00+09:00';
  assert.match(p.calls.filter(c => c.confirm).at(-1).confirm, /완료 후에는 취소·정정할 수 없습니다/);
  await p.complete();
  assert.match(p.calls.filter(c => c.confirm).at(-1).confirm, /최초 시각이 유지되는지/);
  assert.equal(p.els['completed-at'].textContent, '2026-11-30T18:15:30.123456+09:00');
  assert.equal(p.els.comparison.textContent, '재요청에도 최초 완료 일시가 유지됐고, DB 재조회 값과 같습니다.');
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 2);
});

test('POST 200과 재조회 GET의 완료 시각이 다르면 일치로 표시하지 않는다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(BASE + '/41', { status: 200, body: { ...p.server.rows[41], finished: undefined, completedAt: '2026-11-30T18:15:31+09:00' } });
  await p.complete();
  assert.match(p.els.status.textContent, /재조회 시각이 응답과 다릅니다/);
  assert.equal(p.els.status.dataset.error, 'true');
  assert.equal(p.els['completed-at'].textContent, '상세 조회 필요');
  assert.equal(p.els.complete.disabled, true);
});

test('재요청에서 서버가 다른 시각을 돌려주면(덮어쓰기) 유지로 표시하지 않는다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  await p.complete();
  p.server.rows[41].completedAt = '2026-12-05T09:00:00+09:00';   // 잘못된 서버가 덮어쓴 상황
  p.once(url => url.endsWith('/completion'), { status: 200, body: { assignmentId: 41, completedAt: '2026-12-05T09:00:00+09:00' } });
  await p.complete();
  assert.match(p.els.status.textContent, /재요청 후 최초 완료 시각이 달라졌습니다/);
  assert.equal(p.els.status.dataset.error, 'true');
});

test('409 오류: 종료 전·취소 근무는 상태코드·코드·서버 메시지를 표시하고 재조회 GET·자동 재전송 없음', async () => {
  for (const [id, code] of [[40, 'WORK_NOT_FINISHED'], [39, 'WORK_ALREADY_CANCELLED']]) {
    const p = completionPage();
    await p.show();
    await p.select(id);
    p.reset();
    await p.complete();
    assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 1);
    assert.ok(!p.urls().includes('GET ' + BASE + '/' + id), '오류 후 자동 상세 재조회 없음');
    assert.equal(p.els['post-status'].textContent, '완료 POST HTTP 409');
    assert.match(p.els.status.textContent, new RegExp('^HTTP 409 · ' + code + ' · .+ 상세를 다시 조회한 뒤 진행하세요\\.$'));
    assert.equal(p.els.status.dataset.error, 'true');
    assert.deepEqual(p.els.history.children[0].children.map(td => td.textContent), ['완료 POST · #' + id, '409', code]);
    assert.equal(p.server.rows[id].completedAt, null);
    assert.equal(p.els.complete.disabled, true, '상세를 다시 조회해야 다시 완료 가능');
  }
});

test('403·HTML 오류 본문은 원문 대신 안내 문구', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url.endsWith('/completion'), { status: 403 });
  await p.complete();
  assert.equal(p.els.status.textContent, 'HTTP 403 · 고용주 권한 또는 CSRF 토큰을 확인해 주세요. 상세를 다시 조회한 뒤 진행하세요.');
  assert.equal(p.els['post-result'].textContent, 'null');
});

test('POST 통신 실패: 가짜 성공 없이 저장 여부 재확인 안내, 자동 재전송 없음', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url.endsWith('/completion'), new TypeError('Failed to fetch'));
  await p.complete();
  await settle();
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 1);
  assert.equal(p.els.status.textContent, '완료 요청 후 저장값 확인을 마치지 못했습니다. 상세를 다시 조회하세요. 자동으로 재전송하지 않습니다.');
  assert.equal(p.els.status.dataset.error, 'true');
  assert.match(p.els['post-status'].textContent, /저장값은 상세를 다시 조회해 확인하세요/);
  assert.equal(p.els['completed-at'].textContent, '상세 조회 필요');
  assert.ok(!p.els.comparison.textContent.includes('같습니다'));
  assert.equal(p.els.load.disabled, false, '오류 후 버튼 복구');
});

test('재조회 GET 실패(네트워크)도 완료로 표시하지 않는다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url === BASE + '/41', new TypeError('Failed to fetch'));
  await p.complete();
  assert.match(p.els.status.textContent, /저장값 확인을 마치지 못했습니다/);
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 1);
});

test('CSRF 조회 실패·형식 오류면 POST를 보내지 않는다', async () => {
  for (const reply of [{ status: 500 }, { status: 200, body: { headerName: '', token: TOKEN } }, { status: 200, body: { headerName: 'X', token: 1 } }, new TypeError('x')]) {
    const p = completionPage();
    await p.show();
    await p.select(41);
    p.once('/api/auth/csrf', reply);
    await p.complete();
    assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 0);
    assert.equal(p.els.status.dataset.error, 'true');
    assert.ok(!p.allText().includes(TOKEN));
  }
});

test('확인 창 취소 시 요청 없음', async () => {
  const p = completionPage({ confirm: () => false });
  await p.show();
  await p.select(41);
  p.reset();
  await p.complete();
  assert.deepEqual(p.urls(), []);
});

test('중복 클릭 방지: POST 대기 중 재클릭·선택 변경은 추가 요청을 만들지 않는다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url.endsWith('/completion'), 'hold');
  await p.complete();
  assert.equal(p.els.complete.disabled, true);
  assert.equal(p.els.load.disabled, true);
  assert.equal(p.els.assignments.disabled, true);
  await p.complete();
  await p.complete();
  p.els.load.fire('click');
  await settle();
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 1);
  assert.equal(p.calls.filter(c => c.url === BASE).length, 1);
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['post-status'].textContent, '완료 POST HTTP 200 · 상세 GET HTTP 200');
  assert.equal(p.els.load.disabled, false);
});

test('선택 변경 시 이전 상세를 즉시 지우고, 상세와 선택이 다르면 완료 불가', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(BASE + '/40', 'hold');
  await p.select(40);
  for (const id of ['assignment-id', 'worker-name', 'agreed-end', 'change-type']) assert.equal(p.els[id].textContent, '—', id);
  assert.equal(p.els['completed-at'].textContent, '상세 조회 필요');
  assert.equal(p.els.complete.disabled, true);
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['worker-name'].textContent, '근로자 나');
  assert.equal(p.els.complete.disabled, false);
  p.els.assignments.value = '';
  p.els.assignments.fire('change');
  await settle();
  assert.equal(p.els.complete.disabled, true);
  assert.equal(p.els['worker-name'].textContent, '—');
});

test('완료 도중 로그인 계정이 바뀌면(POST 전) POST를 보내지 않고 목록·상세를 지운다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  // run 시작 /me(동일) → CSRF → 두 번째 /me(다른 고용주)
  p.once('/api/auth/me', { status: 200, body: EMPLOYER });
  p.once(url => url === '/api/auth/me' && p.calls.some(c => c.url === '/api/auth/csrf'), { status: 200, body: { id: 11, name: 'OTHER', role: 'EMPLOYER' } });
  await p.complete();
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 0);
  assert.match(p.els.status.textContent, /계정이 변경되었습니다/);
  assert.deepEqual(p.els.assignments.options.map(o => o.value), ['']);
  assert.equal(p.els['worker-name'].textContent, '—');
});

test('POST 응답 후 계정이 바뀌면(로그아웃 401) 결과를 일치로 표시하지 않고 재전송하지 않는다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url === '/api/auth/me' && p.calls.some(c => c.options?.method === 'POST'), { status: 401 });
  await p.complete();
  assert.equal(p.calls.filter(c => c.options?.method === 'POST').length, 1);
  assert.equal(p.els.status.textContent, '완료 요청 후 저장값 확인을 마치지 못했습니다. 상세를 다시 조회하세요. 자동으로 재전송하지 않습니다.');
  assert.equal(p.els.member.textContent, '고용주 계정으로 로그인해 주세요.');
  assert.ok(!p.els.comparison.textContent.includes('같습니다'));
});

test('근로자·비로그인은 목록을 조회하지 않는다', async () => {
  for (const me of [{ status: 401 }, { status: 200, body: { id: 5, name: 'W', role: 'WORKER' } }, { status: 200, body: { id: '10', name: 'E', role: 'EMPLOYER' } }]) {
    const p = completionPage();
    p.once('/api/auth/me', me);
    await p.show();
    assert.ok(!p.calls.some(c => c.url === BASE));
    assert.equal(p.els.member.textContent, '고용주 계정으로 로그인해 주세요.');
    assert.equal(p.els.status.dataset.error, 'true');
  }
});

test('상세 404·목록 403 등 오류는 상태코드와 오류 코드·메시지 표시', async () => {
  const p = completionPage();
  p.once(BASE, { status: 403, body: { code: 'EMPLOYER_ROLE_REQUIRED', message: '고용주 본인 공고의 확정 근무만 조회할 수 있습니다.' } });
  await p.show();
  assert.equal(p.els.status.textContent, 'HTTP 403 · EMPLOYER_ROLE_REQUIRED · 고용주 본인 공고의 확정 근무만 조회할 수 있습니다.');
  p.els.load.fire('click');
  await settle();
  p.once(BASE + '/41', { status: 404, body: { code: 'WORK_ASSIGNMENT_NOT_FOUND', message: '확정 근무를 찾을 수 없습니다.' } });
  await p.select(41);
  assert.equal(p.els.status.textContent, 'HTTP 404 · WORK_ASSIGNMENT_NOT_FOUND · 확정 근무를 찾을 수 없습니다.');
  assert.equal(p.els.complete.disabled, true);
});

test('화면 이탈(pagehide) 후 도착한 응답은 표시하지 않고 화면을 비운다', async () => {
  const p = completionPage();
  await p.show();
  await p.select(41);
  p.once(url => url.endsWith('/completion'), 'hold');
  await p.complete();
  await p.hide();
  assert.equal(p.els.member.textContent, '고용주 로그인 상태를 다시 확인해 주세요.');
  assert.deepEqual(p.els.assignments.options.map(o => o.value), ['']);
  p.held[0].resolve();
  await settle();
  assert.equal(p.els['post-result'].textContent, '완료 응답을 여기에 표시합니다.');
  assert.equal(p.els['completed-at'].textContent, '상세 조회 필요');
  assert.equal(p.els.history.children.length, 0);
});

test('서버 문자열(근로자명·공고명)은 textContent로만 표시되어 HTML로 해석되지 않는다', async () => {
  const p = completionPage();
  p.server.rows[41].workerName = '<img src=x onerror=alert(1)>';
  p.server.rows[41].title = '<script>alert(2)</script>';
  await p.show();
  await p.select(41);
  assert.equal(p.els['worker-name'].textContent, '<img src=x onerror=alert(1)>');
  assert.equal(p.els['posting-title'].textContent, '#7 · <script>alert(2)</script>');
  assert.match(p.els.assignments.options[1].textContent, /<img src=x/);
  assert.deepEqual(p.forbidden, []);
});

// ---- farm-job-location.html: 근로자 약정 상세의 완료 일시 표시 ----
function locationPage(detailBody) {
  const forbidden = [];
  const { El } = makeDom(forbidden);
  const els = {};
  const calls = [];
  const context = {
    document: { getElementById: id => (els[id] ??= new El(id === 'assignments' ? 'select' : 'el', id)), createElement: tag => new El(tag) },
    window: { addEventListener() {} },
    Intl, Date, BigInt, Number, JSON, Object, String, Error, Array,
    console: trap(forbidden, 'console'), localStorage: trap(forbidden, 'localStorage'), sessionStorage: trap(forbidden, 'sessionStorage'),
    fetch: async (url, options = {}) => {
      calls.push(url);
      const body = url === '/api/auth/me' ? { id: 5, name: 'TEST WORKER', role: 'WORKER' } : url === '/demo/api/work-assignments/31' ? detailBody : null;
      if (body === null) throw new Error('unexpected ' + url);
      return { ok: true, status: 200, statusText: 'OK', text: async () => JSON.stringify(body) };
    }
  };
  vm.createContext(context);
  vm.runInContext(LOCATION, context);
  return { els, calls, forbidden, async read(id) { els['assignment-id'].value = id; els['assignment-form'].fire('submit'); await settle(); } };
}

const ASSIGNMENT = { assignmentId: 31, workerId: 5, agreedLatitude: null, agreedLongitude: null, agreedEndAt: '2026-11-30T17:00:00+09:00' };

test('좌표 화면: 미완료(completedAt:null)는 "완료 일시: 미완료", 완료는 값 그대로, JSON 원문은 completedAt 키 유지', async () => {
  const pending = locationPage({ ...ASSIGNMENT, completedAt: null });
  await settle();
  await pending.read('31');
  assert.match(pending.els['assignment-result'].textContent, /\n완료 일시: 미완료\n/);
  assert.match(pending.els['response-body'].textContent, /"completedAt":null/);

  const done = locationPage({ ...ASSIGNMENT, completedAt: '2026-11-30T17:30:00.000001+09:00' });
  await settle();
  await done.read('31');
  assert.match(done.els['assignment-result'].textContent, /\n완료 일시: 2026-11-30T17:30:00\.000001\+09:00\n/);
  assert.deepEqual([...pending.forbidden, ...done.forbidden], []);
});

test('좌표 화면: completedAt 키가 없는 응답(V11 이전 서버)은 미완료로 추측하지 않고 오류 안내', async () => {
  const old = locationPage({ ...ASSIGNMENT });
  await settle();
  await old.read('31');
  assert.ok(!old.els['assignment-result'].textContent.includes('미완료'));
  assert.match(old.els.notice.textContent, /완료 일시 정보가 없습니다/);
  assert.equal(old.els.notice.dataset.error, 'true');
});

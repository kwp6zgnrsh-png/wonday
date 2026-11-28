// farm-work-cancellation.html(및 로그인 메뉴 1건은 farm-login.html)의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 가짜이며, 상태를 가진 가짜 서버가 CHANGE-01·02 응답을 흉내 낸다.
// 실제 브라우저·Spring 서버·세션·CSRF 검증·Security 체인·DB는 없다. 가짜 서버의 판단은 실제 API 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-28-change-01-02-cancellation/work-cancellation-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const resource = name => readFileSync(path.resolve(here, '../../../src/main/resources/demo/' + name), 'utf8');
const SCRIPT = resource('farm-work-cancellation.html').match(/<script>([\s\S]*?)<\/script>/)[1];
const LOGIN = resource('farm-login.html');
const EMPLOYER = { id: 10, name: '가상 고용주', role: 'EMPLOYER' };
const OTHER = { id: 11, name: '다른 가상 고용주', role: 'EMPLOYER' };
const WORKER = { id: 123, name: '가상 근로자', role: 'WORKER' };
const P = 4201;
const settle = async () => { for (let i = 0; i < 60; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));
const points = value => Array.from(value).length;

function makeDom(forbidden) {
  class El {
    constructor(tag, id = '') { Object.assign(this, { tagName: tag, id, children: [], textContent: '', disabled: false, hidden: false,
      dataset: {}, listeners: {}, _value: '', parent: null, type: '' }); }
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); }
    fire(type) { (this.listeners[type] ?? []).forEach(fn => fn({ preventDefault() {} })); }
    replaceChildren(...items) { this.children = []; this.append(...items); }
    append(...items) { for (const item of items) { item.parent = this; this.children.push(item); } }
    prepend(item) { item.parent = this; this.children.unshift(item); }
    remove() { this.parent.children = this.parent.children.filter(x => x !== this); }
    get lastElementChild() { return this.children.at(-1); }
    get value() { return this._value; }
    set value(v) { this._value = String(v); }
    focus() {}
    querySelector() { return new El('el'); }
    reset() {}
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

const rain = { reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: '강수로 작업을 취소합니다.' };
const T0 = '2026-11-28T15:00:00.123456+09:00';

function fakeServer() {
  const s = {
    account: EMPLOYER, prepared: 0, csrfIssued: 0, lastToken: null, posts: [], labels: {},
    changes: { 7005: { changeId: 9005, changeType: 'CANCELLED', reasonCode: 'WEATHER', weatherReasonCode: 'RAIN',
      reasonDetail: '[WORK-02 시연] 가상 기상 변경', effectiveAt: '2026-11-27T10:00:00.000001+09:00',
      recordedAt: '2026-11-27T10:00:00.000001+09:00', updatedAt: '2026-11-27T10:00:00.000001+09:00', sourceVersion: '1' } },
    cases: [
      { scenario: 'NORMAL', id: 7001, s: 301, label: '최초 취소·동일 재요청·다른 사유 409', start: '2026-12-10T09:00:00+09:00' },
      { scenario: 'STALE_VERSION', id: 7002, s: 302, label: '다른 정상 확정 근무의 상태 유지 확인', start: '2026-12-11T09:00:00+09:00' },
      { scenario: 'STARTED', id: 7004, s: 304, label: '시작 후 최초 취소 거절 409', start: '2026-11-27T09:00:00+09:00' },
      { scenario: 'CANCELLED', id: 7005, s: 305, label: '기존 취소의 동일 내용 재요청', start: '2026-12-12T09:00:00+09:00' },
      { scenario: 'EARLY_ENDED', id: 7006, s: 306, label: '조기 종료 근무 취소 거절 409', start: '2026-11-26T09:00:00+09:00' },
      { scenario: 'COMPLETED', id: 7007, s: 307, label: '완료 근무 취소 거절 409', start: '2026-11-25T09:00:00+09:00' }]
  };
  s.state = id => s.changes[id]?.changeType ?? (id === 7006 ? 'EARLY_ENDED' : id === 7007 ? 'COMPLETED' : 'CONFIRMED');
  s.caseRows = () => s.cases.map(c => ({ scenario: c.scenario, label: s.labels[c.id] ?? c.label, jobPostingId: P, workScheduleId: c.s,
    assignmentId: c.id, workStatus: s.state(c.id), agreedStartAt: c.start,
    suggestedRequest: s.changes[c.id]?.changeType === 'CANCELLED'
      ? { reasonCode: s.changes[c.id].reasonCode, weatherReasonCode: s.changes[c.id].weatherReasonCode, reasonDetail: s.changes[c.id].reasonDetail }
      : { ...rain } }));
  const norm = v => (v ?? '').trim() || null;
  const error = (status, code, message) => ({ status, body: { code, message } });
  s.handle = (url, options) => {
    const method = options.method || 'GET';
    if (url === '/api/auth/me') return s.account ? { status: 200, body: s.account } : { status: 401 };
    if (url === '/api/auth/csrf') {
      s.lastToken = 'csrf-SECRET-' + (++s.csrfIssued);
      return { status: 200, body: { headerName: 'X-CSRF-TOKEN', parameterName: '_csrf', token: s.lastToken } };
    }
    const csrfOk = options.headers?.['X-CSRF-TOKEN'] === s.lastToken;
    if (url === '/demo/api/work-cancellation/fixtures' && method === 'POST') {
      if (!csrfOk) return { status: 403 };
      s.posts.push({ url, body: options.body ?? null });
      s.prepared++;
      return { status: s.prepared === 1 ? 201 : 200, body: { created: s.prepared === 1, jobPostingId: P, cases: s.caseRows() } };
    }
    let m = url.match(/^\/demo\/api\/employer\/work-assignments\/(\d+)$/);
    if (m && method === 'GET') {
      const id = Number(m[1]);
      return { status: 200, body: { assignmentId: id, jobPostingId: P, title: '[가상] 공고', workerName: WORKER.name, agreedWorkDate: '2026-12-10',
        agreedTimeZone: 'GMT+9', agreedEndAt: '2026-12-10T17:00:00+09:00', completedAt: id === 7007 ? '2026-11-25T18:00:00+09:00' : null,
        changeType: s.changes[id]?.changeType ?? (id === 7006 ? 'EARLY_ENDED' : null) } };
    }
    m = url.match(/^\/api\/work-assignments\/(\d+)\/cancellation$/);
    if (m && method === 'POST') {
      if (!csrfOk || options.headers['Content-Type'] !== 'application/json') return { status: 403 };
      const id = Number(m[1]), body = JSON.parse(options.body);
      s.posts.push({ url, body });
      if (!['WEATHER', 'FARM_CIRCUMSTANCES', 'OTHER'].includes(body.reasonCode) || (body.reasonCode === 'WEATHER') !== (body.weatherReasonCode != null))
        return error(400, 'INVALID_REQUEST', '양수인 근무 ID와 올바른 취소 사유를 입력해 주세요.');
      if (points(norm(body.reasonDetail) ?? '') > 500) return error(400, 'INVALID_REQUEST', '상세 설명은 앞뒤 공백을 제거한 뒤 최대 500자까지 입력할 수 있습니다.');
      if (id === 7007) return error(409, 'WORK_ALREADY_COMPLETED', '완료된 근무는 취소할 수 없습니다.');
      if (id === 7006) return error(409, 'WORK_ALREADY_EARLY_ENDED', '조기 종료된 근무는 시작 전 취소로 바꿀 수 없습니다.');
      const existing = s.changes[id];
      if (existing) {
        if (existing.reasonCode !== body.reasonCode || existing.weatherReasonCode !== (body.weatherReasonCode ?? null)
          || norm(existing.reasonDetail) !== norm(body.reasonDetail))
          return error(409, 'CANCELLATION_REASON_CONFLICT', '이미 기록된 취소 내용과 다릅니다. 사유 보완 기능을 이용해 주세요.');
        return { status: 200, body: { assignmentId: id, ...existing } };
      }
      if (id === 7004) return error(409, 'WORK_ALREADY_STARTED', '약정 근무 시작 시각 전까지만 취소할 수 있습니다.');
      s.changes[id] = { changeId: 9000 + id % 100, changeType: 'CANCELLED', reasonCode: body.reasonCode, weatherReasonCode: body.weatherReasonCode ?? null,
        reasonDetail: norm(body.reasonDetail), effectiveAt: T0, recordedAt: T0, updatedAt: T0, sourceVersion: '1' };
      return { status: 200, body: { assignmentId: id, ...s.changes[id] } };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function harness(script, server, extra = {}) {
  const forbidden = [];
  const { El } = makeDom(forbidden);
  const els = {};
  const get = id => (els[id] ??= new El('el', id));
  const calls = [], overrides = [], held = [], windowListeners = {};
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const context = {
    document: { getElementById: get, querySelector: sel => get(sel.replace(/^#/, '')), createElement: tag => new El(tag),
      write: () => forbidden.push('document.write'), get cookie() { forbidden.push('document.cookie'); return ''; } },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }, location: { search: '' } },
    AbortController, URLSearchParams,
    console: trap(forbidden, 'console'), localStorage: trap(forbidden, 'localStorage'), sessionStorage: trap(forbidden, 'sessionStorage'),
    indexedDB: trap(forbidden, 'indexedDB'),
    fetch: (url, options = {}) => {
      const call = { url, options: plain({ ...options, signal: undefined }) };
      calls.push(call);
      const index = overrides.findIndex(o => o.match(url, options));
      let spec;
      if (index >= 0) { spec = overrides[index].reply; overrides.splice(index, 1); } else spec = server.handle(url, options);
      if (spec && spec.status !== undefined) call.reply = plain(spec);
      if (spec === 'hold') return new Promise((resolve, reject) => held.push({ resolve: s => resolve(respond(s ?? server.handle(url, options))), reject }));
      return spec instanceof Error ? Promise.reject(spec) : Promise.resolve(respond(spec));
    },
    ...extra
  };
  for (const [id, value] of Object.entries(extra.initialHidden ?? {})) get(id).hidden = value;
  vm.createContext(context);
  vm.runInContext(script, context);
  return { els, get, calls, overrides, held, forbidden, windowListeners };
}

function page(account = EMPLOYER) {
  const server = fakeServer();
  server.account = account;
  const h = harness(SCRIPT, server);
  const p = {
    ...h, server, el: h.get,
    once(match, reply) { h.overrides.push({ match: typeof match === 'string' ? (url => url === match) : match, reply }); },
    hide: async () => { h.windowListeners.pagehide.forEach(fn => fn()); await settle(); },
    click: async id => { h.get(id).fire('click'); await settle(); },
    input: async (id, value) => { h.get(id).value = value; h.get(id).fire('input'); await settle(); },
    change: async (id, value) => { h.get(id).value = value; h.get(id).fire('change'); await settle(); },
    row: id => h.get('cases').children.find(r => r.children[1]?.textContent.includes('확정 근무 #' + id)),
    choose: async id => { p.row(id).children[4].children[0].fire('click'); await settle(); },
    urls: () => h.calls.map(c => (c.options.method || 'GET') + ' ' + c.url),
    posts: () => h.calls.filter(c => c.options.method === 'POST'),
    since: n => h.calls.slice(n).map(c => (c.options.method || 'GET') + ' ' + c.url),
    text: id => h.get(id).textContent,
    allText: () => Object.values(h.els).map(e => e.allText).join('\n')
  };
  return p;
}
async function shown(account = EMPLOYER) {
  const p = page(account);
  await settle();
  return p;
}
async function preparedPage() {
  const p = await shown();
  await p.click('prepare');
  return p;
}
const cancelPosts = p => p.posts().filter(c => c.url.endsWith('/cancellation'));
const lastBody = p => JSON.parse(cancelPosts(p).at(-1).options.body);

test('진입: /me 확인만 하고 준비·취소 POST 없음, 고용주에게 준비 버튼만 활성', async () => {
  const p = await shown();
  assert.deepEqual(p.urls(), ['GET /api/auth/me']);
  assert.equal(p.posts().length, 0);
  assert.match(p.text('account'), /가상 고용주 · 고용주 · 회원 ID 10/);
  assert.equal(p.el('prepare').disabled, false);
  for (const id of ['cancel', 'repeat', 'different', 'refresh', 'reason', 'detail', 'weather']) assert.equal(p.el(id).disabled, true, id);
  assert.deepEqual(p.forbidden, []);
});

test('근로자·비로그인: 고용주 전용 동작 비활성, 클릭해도 쓰기 POST 없음', async () => {
  const worker = await shown(WORKER);
  assert.equal(worker.el('prepare').disabled, true);
  assert.match(worker.text('notice'), /고용주 계정으로 로그인해야/);
  await worker.click('prepare');
  assert.equal(worker.posts().length, 0);
  assert.equal(worker.calls.filter(c => c.url === '/api/auth/csrf').length, 0);

  const anonymous = await shown(null);
  assert.equal(anonymous.el('prepare').disabled, true);
  assert.equal(anonymous.text('account'), '로그인이 필요합니다.');
  await anonymous.click('prepare');
  assert.equal(anonymous.posts().length, 0);
});

test('로그인 메뉴: 취소 링크는 고용주에게만 표시 (farm-login.html 스크립트 실행)', async () => {
  const script = LOGIN.match(/<script>([\s\S]*?)<\/script>/)[1];
  assert.ok(LOGIN.includes('id="cancellation-link" hidden>'));
  for (const [account, hidden] of [[EMPLOYER, false], [WORKER, true], [null, true]]) {
    const server = { handle: url => {
      if (url === '/api/auth/me') return account ? { status: 200, body: { ...account, nationalityType: null } } : { status: 401 };
      throw new Error('unexpected ' + url);
    } };
    const h = harness(script, server, { initialHidden: { 'cancellation-link': true } });
    await settle();
    assert.equal(h.get('cancellation-link').hidden, hidden, String(account?.role));
    assert.deepEqual(h.calls.map(c => c.url), ['/api/auth/me']);
  }
});

test('준비 → 사례 표 → 정상 사례 자동 선택 → 요청 입력 연결 → 저장 상태 GET', async () => {
  const p = await preparedPage();
  assert.equal(p.server.prepared, 1);
  assert.match(p.text('fixture-status'), /시연 준비 HTTP 201\n새 가상 공고를 준비했습니다/);
  assert.equal(p.el('cases').children.length, 6);
  assert.equal(p.row(7001).dataset.selected, 'true');
  assert.equal(p.row(7005).children[3].textContent, '시작 전 취소');
  assert.match(p.text('target-note'), /공고 #4201 \/ 일정 #301 \/ 확정 근무 #7001\n약정 시작: 2026-12-10T09:00:00\+09:00/);
  assert.equal(p.el('reason').value, 'WEATHER');
  assert.equal(p.el('weather').value, 'RAIN');
  assert.equal(p.el('detail').value, '강수로 작업을 취소합니다.');
  assert.ok(p.urls().includes('GET /demo/api/employer/work-assignments/7001'));
  assert.match(p.text('stored-state'), /DB 재조회: 확정 근무 #7001 · 정상 확정/);
  for (const id of ['cancel', 'reason', 'detail', 'weather', 'refresh']) assert.equal(p.el(id).disabled, false, id);
  for (const id of ['repeat', 'different']) assert.equal(p.el(id).disabled, true, id);
  // 다른 사례 선택 → 입력 칸이 그 사례의 suggestedRequest로 바뀜
  await p.choose(7005);
  assert.equal(p.el('detail').value, '[WORK-02 시연] 가상 기상 변경');
  assert.equal(p.row(7005).dataset.selected, 'true');
  assert.equal(p.row(7001).dataset.selected, 'false');
});

test('POST마다 CSRF를 새로 발급받아 헤더로 전달, 토큰·쿠키는 화면/저장소/요청 기록에 없음', async () => {
  const p = await preparedPage();
  await p.click('cancel');
  await p.click('repeat');
  await p.click('different');
  const posts = p.posts();
  assert.equal(posts.length, 4);
  for (const post of posts) {
    const index = p.calls.indexOf(post);
    const before = p.calls.slice(0, index);
    const csrfIndex = before.map(c => c.url).lastIndexOf('/api/auth/csrf');
    assert.ok(csrfIndex >= 0);
    assert.ok(!before.slice(csrfIndex).some(c => c.options.method === 'POST'), '이전 POST 이후 새 CSRF 발급');
    assert.equal(post.options.headers['X-CSRF-TOKEN'], p.calls[csrfIndex].reply.body.token);
  }
  assert.deepEqual(new Set(posts.map(c => c.options.headers['X-CSRF-TOKEN'])).size, 4);
  assert.equal(p.server.csrfIssued, 4);
  assert.ok(!p.allText().includes('csrf-SECRET'), '화면 텍스트에 토큰 없음');
  assert.ok(!p.text('request-body').includes('X-CSRF'));
  assert.ok(!p.el('requests').allText.includes('csrf'), '요청 기록에 CSRF 발급·토큰 없음');
  for (const post of posts) assert.equal(post.options.credentials, 'same-origin');
  for (const post of posts) assert.equal(Object.keys(post.options.headers).some(k => /authorization|cookie/i.test(k)), false);
  assert.deepEqual(p.forbidden, []);
});

test('CSRF 실패(오류 응답·토큰 누락·통신 오류) 때 쓰기 POST 없음', async () => {
  const p = await preparedPage();
  const before = p.posts().length;
  p.once('/api/auth/csrf', { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '오류' } });
  await p.click('cancel');
  assert.match(p.text('notice'), /CSRF 토큰을 발급받지 못했습니다/);
  p.once('/api/auth/csrf', { status: 200, body: { headerName: 'X-CSRF-TOKEN', token: '' } });
  await p.click('cancel');
  p.once('/api/auth/csrf', new TypeError('network'));
  await p.click('cancel');
  p.once('/api/auth/csrf', { status: 200, body: { token: 'x' } });
  await p.click('prepare');
  assert.equal(p.posts().length, before);
  assert.equal(p.el('cancel').disabled, false, '다음 시도 가능');
});

test('최초 취소 200 표시와 첫 성공 응답 보관, 취소 응답과 DB 상태 GET을 별도 표시', async () => {
  const p = await preparedPage();
  await p.click('cancel');
  assert.deepEqual(lastBody(p), { reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: '강수로 작업을 취소합니다.' });
  assert.match(p.text('cancel-status'), /^HTTP 200 · 취소 요청\n서버가 취소 결과를 반환했습니다\./);
  assert.match(p.text('comparison'), /처음 받은 성공 응답을 비교 기준으로 보관/);
  assert.equal(p.text('change-id'), '9001');
  assert.equal(p.text('source-version'), '1');
  for (const id of ['effective-at', 'recorded-at', 'updated-at']) assert.equal(p.text(id), T0, id);
  assert.equal(JSON.parse(p.text('cancel-response')).changeId, 9001);
  assert.equal(JSON.parse(p.text('read-response')).changeType, 'CANCELLED');
  assert.match(p.text('read-status'), /저장 상태 조회 HTTP 200/);
  assert.notEqual(p.text('cancel-response'), p.text('read-response'));
  assert.equal(p.row(7001).children[3].textContent, '시작 전 취소');
  for (const id of ['repeat', 'different']) assert.equal(p.el(id).disabled, false, id);
});

test('동일 재요청: 입력 칸을 수정해도 직전 성공 요청을 그대로 재전송, ID·버전·사유·세 시각 비교', async () => {
  const p = await preparedPage();
  await p.click('cancel');
  const first = lastBody(p);
  await p.change('reason', 'OTHER');
  await p.input('detail', '전혀 다른 설명');
  await p.click('repeat');
  assert.deepEqual(lastBody(p), first);
  assert.match(p.text('cancel-status'), /^HTTP 200 · 동일 내용 재요청/);
  assert.match(p.text('comparison'), /기존 결과 유지 확인: 변경 ID·사유·버전·취소\/저장\/수정 시각이 모두 같습니다/);
  assert.equal(p.text('comparison').includes('달라진'), false);
  assert.equal(p.el('comparison').dataset.error, 'false');
  // 서버가 다른 값을 돌려주면 달라진 필드를 표시해야 한다(비교가 실제로 동작하는지)
  p.once(url => url.endsWith('/cancellation'), { status: 200, body: { assignmentId: 7001, ...p.server.changes[7001], sourceVersion: '2',
    updatedAt: '2026-11-28T16:00:00+09:00' } });
  await p.click('repeat');
  assert.match(p.text('comparison'), /달라진 필드: updatedAt, sourceVersion|달라진 필드: .*sourceVersion/);
  assert.equal(p.el('comparison').dataset.error, 'true');
});

test('다른 사유: 유효한 다른 사유 조합으로 409 확인, 마지막 성공 요청·비교 기준을 덮어쓰지 않음', async () => {
  const p = await preparedPage();
  await p.click('cancel');
  const first = lastBody(p);
  await p.click('different');
  const different = lastBody(p);
  assert.deepEqual(different, { reasonCode: 'OTHER', weatherReasonCode: null, reasonDetail: first.reasonDetail });
  assert.match(p.text('cancel-status'), /^HTTP 409 · CANCELLATION_REASON_CONFLICT · 다른 사유 재요청/);
  assert.match(p.text('comparison'), /다른 사유 요청은 409로 거절됐습니다/);
  assert.equal(p.text('change-id'), '9001', '이전 성공 결과 유지');
  assert.equal(p.server.changes[7001].reasonCode, 'WEATHER');
  await p.click('repeat');
  assert.deepEqual(lastBody(p), first, '409 뒤에도 재요청 기준은 직전 성공 요청');
  assert.match(p.text('comparison'), /기존 결과 유지 확인/);
  // OTHER로 성공한 경우 다른 사유는 FARM_CIRCUMSTANCES
  await p.choose(7002);
  await p.change('reason', 'OTHER');
  await p.click('cancel');
  assert.deepEqual(lastBody(p), { reasonCode: 'OTHER', weatherReasonCode: null, reasonDetail: '강수로 작업을 취소합니다.' });
  await p.click('different');
  assert.deepEqual(lastBody(p), { reasonCode: 'FARM_CIRCUMSTANCES', weatherReasonCode: null, reasonDetail: '강수로 작업을 취소합니다.' });
});

test('보조 GET 500·통신 오류가 취소 결과·사례 선택·재요청 버튼을 지우거나 막지 않음', async () => {
  for (const failure of [{ status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } }, new TypeError('network')]) {
    const p = await preparedPage();
    p.once(url => url === '/demo/api/employer/work-assignments/7001', failure);
    await p.click('cancel');
    assert.match(p.text('cancel-status'), /^HTTP 200/);
    assert.equal(p.text('change-id'), '9001');
    assert.match(p.text('target-note'), /확정 근무 #7001/);
    assert.equal(p.row(7001).dataset.selected, 'true');
    assert.match(p.text('stored-state'), /취소 요청 결과와 사례 선택은 유지됩니다|위 취소 응답은 유지됩니다/);
    assert.equal(p.el('stored-state').dataset.error, 'true');
    for (const id of ['repeat', 'different', 'cancel']) assert.equal(p.el(id).disabled, false, id);
    await p.click('repeat');
    assert.equal(cancelPosts(p).length, 2);
    assert.match(p.text('comparison'), /기존 결과 유지 확인/);
  }
});

test('중복 클릭 중 동시 POST 방지', async () => {
  const p = await preparedPage();
  p.once(url => url.endsWith('/cancellation'), 'hold');
  await p.click('cancel');
  await p.click('cancel');
  await p.click('repeat');
  await p.click('prepare');
  assert.equal(p.el('cancel').disabled, true);
  assert.equal(p.el('prepare').disabled, true);
  assert.equal(cancelPosts(p).length, 1);
  p.held.shift().resolve();
  await settle();
  assert.equal(cancelPosts(p).length, 1);
  assert.equal(p.el('cancel').disabled, false);
  assert.match(p.text('cancel-status'), /^HTTP 200/);
});

test('POST 오류·통신 오류 때 자동 재전송 없음, 결과 불확실 안내', async () => {
  const p = await preparedPage();
  p.once(url => url.endsWith('/cancellation'), { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await p.click('cancel');
  assert.match(p.text('cancel-status'), /^HTTP 500 · INTERNAL_SERVER_ERROR/);
  assert.equal(p.el('repeat').disabled, true, '성공 전에는 재요청 불가');
  assert.equal(cancelPosts(p).length, 1);
  p.once(url => url.endsWith('/cancellation'), new TypeError('Failed to fetch'));
  await p.click('cancel');
  await settle();
  assert.equal(cancelPosts(p).length, 2);
  assert.match(p.text('notice'), /결과가 불확실할 수 있습니다.*자동 재전송하지 않습니다/s);
  assert.equal(p.text('change-id'), '—');
});

test('계정 변경: 요청 전 변경·요청 중 변경 모두 이전 사례·응답 제거', async () => {
  const before = await preparedPage();
  await before.click('cancel');
  before.server.account = OTHER;
  const n = before.posts().length;
  await before.click('repeat');
  assert.equal(before.posts().length, n, '요청 전 계정 변경이면 POST 없음');
  assert.equal(before.el('cases').children.length, 0);
  assert.equal(before.text('cancel-response'), '응답 없음');
  assert.equal(before.text('change-id'), '—');
  assert.match(before.text('notice'), /계정이 바뀌어 이전 계정의 사례와 응답을 지웠습니다/);

  const during = await preparedPage();
  during.once(url => url.endsWith('/cancellation'), 'hold');
  await during.click('cancel');
  during.server.account = OTHER;
  during.held.shift().resolve();
  await settle();
  assert.equal(during.el('cases').children.length, 0);
  assert.equal(during.text('cancel-status'), '취소 요청 전');
  assert.equal(during.text('change-id'), '—');
  assert.equal(during.el('repeat').disabled, true);
});

test('pagehide 뒤 도착한 응답이 화면을 다시 채우지 않음', async () => {
  const p = await preparedPage();
  p.once(url => url.endsWith('/cancellation'), 'hold');
  await p.click('cancel');
  await p.hide();
  p.held.shift().resolve();
  await settle();
  assert.equal(p.el('cases').children.length, 0);
  assert.equal(p.text('cancel-status'), '취소 요청 전');
  assert.equal(p.text('cancel-response'), '응답 없음');
  assert.equal(p.text('change-id'), '—');
  assert.equal(p.text('account'), '로그인 상태를 다시 확인해 주세요.');
  assert.equal(p.el('prepare').disabled, true);
});

test('설명 길이는 앞뒤 공백 제거 후 코드 포인트 기준, 501자도 전송되어 서버 400 확인', async () => {
  const p = await preparedPage();
  await p.input('detail', ' \n　' + '😀'.repeat(500) + '\t ');
  assert.match(p.text('detail-count'), /^앞뒤 공백 제거 후 500 \/ 500 코드 포인트$/);
  assert.equal(p.el('detail-count').dataset.error, 'false');
  await p.input('detail', '가'.repeat(250) + '\n\n' + 'a'.repeat(248));
  assert.match(p.text('detail-count'), /500 \/ 500/);
  await p.input('detail', '😀'.repeat(501));
  assert.match(p.text('detail-count'), /501 \/ 500 코드 포인트 · 전송하면 서버에서 400으로 거절합니다/);
  assert.equal(p.el('detail-count').dataset.error, 'true');
  assert.equal(p.el('cancel').disabled, false);
  await p.click('cancel');
  assert.equal(lastBody(p).reasonDetail, '😀'.repeat(501), '잘라 보내지 않음');
  assert.match(p.text('cancel-status'), /^HTTP 400 · INVALID_REQUEST/);
  assert.equal(p.el('repeat').disabled, true);
  // 기상 세부 사유를 비워도 전송 가능 → 서버 400
  await p.input('detail', '');
  await p.change('weather', '');
  await p.click('cancel');
  assert.deepEqual(lastBody(p), { reasonCode: 'WEATHER', weatherReasonCode: null, reasonDetail: '' });
  assert.match(p.text('cancel-status'), /^HTTP 400/);
});

test('사용자 문자열은 HTML로 삽입하지 않고 textContent로 표시', async () => {
  const p = await shown();
  const evil = '<img src=x onerror=alert(1)>';
  p.server.labels[7001] = evil;
  p.server.changes[7005].reasonDetail = '<script>alert(2)</script>';
  await p.click('prepare');
  assert.equal(p.row(7001).children[0].textContent, evil);
  assert.match(p.text('target-note'), /<img src=x onerror=alert\(1\)>/);
  await p.choose(7005);
  assert.equal(p.el('detail').value, '<script>alert(2)</script>');
  await p.click('cancel');
  assert.match(p.text('cancel-response'), /<script>alert\(2\)<\/script>/);
  assert.deepEqual(p.forbidden, []);
});

test('시연 데이터 재사용·이미 취소된 사례를 최초 취소로 오해하게 표시하지 않음', async () => {
  const p = await preparedPage();
  await p.click('prepare');
  assert.match(p.text('fixture-status'), /시연 준비 HTTP 200\n기존 시연 공고와 상태를 재사용합니다/);
  await p.choose(7005);
  assert.match(p.text('target-note'), /이미 취소된 사례입니다\. 「취소 요청 보내기」는 기존 결과 200을 확인합니다\./);
  await p.click('cancel');
  assert.deepEqual(lastBody(p), { reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: '[WORK-02 시연] 가상 기상 변경' });
  assert.match(p.text('cancel-status'), /^HTTP 200/);
  assert.equal(p.text('change-id'), '9005');
  assert.equal(p.text('effective-at'), '2026-11-27T10:00:00.000001+09:00');
  for (const id of ['notice', 'cancel-status', 'comparison', 'target-note', 'fixture-status']) {
    assert.equal(/최초 취소|새로 취소|취소되었습니다/.test(p.text(id)), false, id + ': ' + p.text(id));
  }
  assert.match(p.text('target-note'), /이미 취소된 사례/, '응답 후에도 안내 유지');
  // 시작 경과·조기 종료·완료 사례는 409를 그대로 표시
  for (const [id, code] of [[7004, 'WORK_ALREADY_STARTED'], [7006, 'WORK_ALREADY_EARLY_ENDED'], [7007, 'WORK_ALREADY_COMPLETED']]) {
    await p.choose(id);
    await p.click('cancel');
    assert.match(p.text('cancel-status'), new RegExp('^HTTP 409 · ' + code), String(id));
    assert.equal(p.text('change-id'), '—');
    assert.equal(p.el('repeat').disabled, true);
  }
});

// farm-work-early-end.html(로그인 메뉴 1건은 farm-login.html)의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test).
// document·window·fetch는 가짜이고, 상태를 가진 가짜 서버가 CHANGE-03 응답을 흉내 낸다. 가짜 계정은 스크립트 실행 전에 정한다.
// 실제 브라우저(datetime-local 동작 포함)·Spring 서버·세션·CSRF 검증·Security 체인·DB는 없다. 가짜 서버의 판단은 실제 API 검증이 아니다.
// 실행 (farm-api 폴더): node --test docs/verification/2026-11-28-change-03-early-end/work-early-end-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const resource = name => readFileSync(path.resolve(here, '../../../src/main/resources/demo/' + name), 'utf8');
const SCRIPT = resource('farm-work-early-end.html').match(/<script>([\s\S]*?)<\/script>/)[1];
const LOGIN = resource('farm-login.html');
const EMPLOYER = { id: 10, name: '가상 고용주', role: 'EMPLOYER' };
const OTHER = { id: 11, name: '다른 가상 고용주', role: 'EMPLOYER' };
const WORKER = { id: 123, name: '가상 근로자', role: 'WORKER' };
const P = 4201;
const settle = async () => { for (let i = 0; i < 60; i++) await new Promise(r => setImmediate(r)); };
const plain = value => JSON.parse(JSON.stringify(value));
// 오프셋 포함 ISO 문자열 → 마이크로초 단위 BigInt (Date의 밀리초 한계를 피해서 비교)
const micros = value => {
  const m = /^(.*T\d{2}:\d{2}(?::\d{2})?)(\.\d{1,6})?(Z|[+-]\d{2}:\d{2})$/.exec(value);
  if (!m) return null;
  return BigInt(Date.parse(m[1] + m[3])) * 1000n + BigInt(((m[2] || '.').slice(1) + '000000').slice(0, 6));
};

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

const DETAIL = '기상으로 작업을 조기 종료합니다.';
const REC = '2026-11-29T00:31:00.654321+09:00';
// 가상 기준: 서버 현재 GMT+9 2026-11-29 00:30
function fakeServer() {
  const s = {
    account: EMPLOYER, prepared: 0, csrfIssued: 0, lastToken: null, posts: [], labels: {}, now: micros('2026-11-29T00:30:00.123456+09:00'),
    changes: { 7006: { changeId: 9006, changeType: 'EARLY_ENDED', reasonCode: 'WEATHER', weatherReasonCode: 'RAIN',
      reasonDetail: '[CHANGE-03 시연] 기존 가상 기상 변경', effectiveAt: '2026-11-26T14:00:00+09:00',
      recordedAt: '2026-11-25T09:00:00.000001+09:00', updatedAt: '2026-11-25T09:00:00.000001+09:00', sourceVersion: '1' },
      7005: { changeType: 'CANCELLED' } },
    cases: [
      { scenario: 'NORMAL', id: 7001, s: 301, label: '최초 조기 종료 200·동일 재요청', start: '2026-11-29T00:00:00+09:00', end: '2026-11-29T23:59:59.999999+09:00',
        deadline: '2026-11-30T23:59:59.999999+09:00', actual: '2026-11-29T00:29:00.123456+09:00' },
      { scenario: 'LATE_ALLOWED', id: 7002, s: 302, label: '약정 종료 후 24시간 이내 늦은 입력', start: '2026-11-28T00:00:00+09:00', end: '2026-11-28T23:59:59.999999+09:00',
        deadline: '2026-11-29T23:59:59.999999+09:00', actual: '2026-11-28T13:59:59.999999Z' },
      { scenario: 'NOT_STARTED', id: 7003, s: 303, label: '시작 전 409', start: '2026-11-30T09:00:00+09:00', end: '2026-11-30T17:00:00+09:00',
        deadline: '2026-12-01T17:00:00+09:00', actual: '2026-11-30T09:00:00+09:00' },
      { scenario: 'DEADLINE_PASSED', id: 7004, s: 304, label: '입력 기한 초과 409·문의 안내', start: '2026-11-27T09:00:00+09:00', end: '2026-11-27T17:00:00+09:00',
        deadline: '2026-11-28T17:00:00+09:00', actual: '2026-11-27T16:00:00+09:00' },
      { scenario: 'CANCELLED', id: 7005, s: 305, label: '취소 근무 거절 409', start: '2026-12-01T09:00:00+09:00', end: '2026-12-01T17:00:00+09:00',
        deadline: '2026-12-02T17:00:00+09:00', actual: '2026-12-01T09:00:00+09:00' },
      { scenario: 'EARLY_ENDED', id: 7006, s: 306, label: '기존 조기 종료·기한 이후 동일 재요청', start: '2026-11-26T09:00:00+09:00', end: '2026-11-26T17:00:00+09:00',
        deadline: '2026-11-27T17:00:00+09:00', actual: '2026-11-26T05:00:00Z' },
      { scenario: 'COMPLETED', id: 7007, s: 307, label: '완료 근무 거절 409', start: '2026-11-25T09:00:00+09:00', end: '2026-11-25T17:00:00+09:00',
        deadline: '2026-11-26T17:00:00+09:00', actual: '2026-11-25T16:00:00+09:00' }]
  };
  s.state = id => s.changes[id]?.changeType ?? (id === 7007 ? 'COMPLETED' : 'CONFIRMED');
  s.caseRows = () => s.cases.map(c => ({ scenario: c.scenario, label: s.labels[c.id] ?? c.label, assignmentId: c.id, workScheduleId: c.s,
    workStatus: s.state(c.id), agreedStartAt: c.start, agreedEndAt: c.end, recordingDeadline: c.deadline,
    suggestedRequest: s.changes[c.id]?.changeType === 'EARLY_ENDED'
      ? { actualEndAt: s.changes[c.id].effectiveAt, reasonCode: s.changes[c.id].reasonCode, weatherReasonCode: s.changes[c.id].weatherReasonCode,
        reasonDetail: s.changes[c.id].reasonDetail }
      : { actualEndAt: c.actual, reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: DETAIL } }));
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
    if (url === '/demo/api/work-early-end/fixtures' && method === 'POST') {
      if (!csrfOk) return { status: 403 };
      s.posts.push({ url });
      s.prepared++;
      return { status: s.prepared === 1 ? 201 : 200, body: { created: s.prepared === 1, jobPostingId: P, cases: s.caseRows() } };
    }
    let m = url.match(/^\/demo\/api\/employer\/work-assignments\/(\d+)$/);
    if (m && method === 'GET') {
      const id = Number(m[1]), c = s.cases.find(x => x.id === id);
      return { status: 200, body: { assignmentId: id, jobPostingId: P, title: '[가상] 공고', workerName: WORKER.name, agreedWorkDate: c.start.slice(0, 10),
        agreedTimeZone: 'GMT+9', agreedEndAt: c.end, completedAt: id === 7007 ? '2026-11-25T18:00:00+09:00' : null, changeType: s.changes[id]?.changeType ?? null } };
    }
    m = url.match(/^\/api\/work-assignments\/(\d+)\/early-end$/);
    if (m && method === 'POST') {
      if (!csrfOk || options.headers['Content-Type'] !== 'application/json') return { status: 403 };
      const id = Number(m[1]), body = JSON.parse(options.body), c = s.cases.find(x => x.id === id);
      s.posts.push({ url, body });
      const actual = typeof body.actualEndAt === 'string' ? micros(body.actualEndAt) : null;
      if (actual === null || !['WEATHER', 'FARM_CIRCUMSTANCES', 'OTHER'].includes(body.reasonCode)
        || (body.reasonCode === 'WEATHER') !== (body.weatherReasonCode != null))
        return error(400, 'INVALID_REQUEST', '양수인 근무 ID와 올바른 실제 종료 시각·사유를 입력해 주세요.');
      if (Array.from(norm(body.reasonDetail) ?? '').length > 500) return error(400, 'INVALID_REQUEST', '상세 설명은 앞뒤 공백을 제거한 뒤 최대 500자까지 입력할 수 있습니다.');
      if (id === 7007) return error(409, 'WORK_ALREADY_COMPLETED', '완료된 근무에는 조기 종료를 기록할 수 없습니다.');
      const existing = s.changes[id];
      if (existing?.changeType === 'CANCELLED') return error(409, 'WORK_ALREADY_CANCELLED', '취소된 근무에는 조기 종료를 기록할 수 없습니다.');
      if (existing) {
        if (micros(existing.effectiveAt) !== actual || existing.reasonCode !== body.reasonCode || existing.weatherReasonCode !== (body.weatherReasonCode ?? null)
          || norm(existing.reasonDetail) !== norm(body.reasonDetail))
          return error(409, 'EARLY_END_CONTENT_CONFLICT', '이미 기록된 조기 종료 내용과 다릅니다. 기존 기록은 변경하지 않습니다.');
        return { status: 200, body: { assignmentId: id, ...existing } };
      }
      if (s.now < micros(c.start)) return error(409, 'WORK_NOT_STARTED', '약정 근무 시작 시각부터 조기 종료를 기록할 수 있습니다.');
      if (s.now > micros(c.end) + 86400000000n) return error(409, 'EARLY_END_DEADLINE_PASSED',
        '조기 종료를 직접 기록할 수 있는 시간이 지났습니다. 실제 조기 종료 사실의 확인이 필요하면 담당자에게 문의해 주세요.');
      if (actual < micros(c.start) || actual >= micros(c.end) || actual > s.now)
        return error(400, 'INVALID_REQUEST', '실제 종료 시각은 약정 시작 이상·종료 미만이고 서버 현재 시각보다 미래일 수 없습니다.');
      s.changes[id] = { changeId: 9000 + id % 100, changeType: 'EARLY_ENDED', reasonCode: body.reasonCode, weatherReasonCode: body.weatherReasonCode ?? null,
        reasonDetail: norm(body.reasonDetail), effectiveAt: body.actualEndAt, recordedAt: REC, updatedAt: REC, sourceVersion: '1' };
      return { status: 200, body: { assignmentId: id, ...s.changes[id] } };
    }
    throw new Error('unexpected ' + method + ' ' + url);
  };
  return s;
}

function harness(script, server, initialHidden = {}) {
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
    }
  };
  for (const [id, value] of Object.entries(initialHidden)) get(id).hidden = value;
  vm.createContext(context);
  vm.runInContext(script, context);
  return { els, get, calls, overrides, held, forbidden, windowListeners };
}

// 가짜 계정은 스크립트 실행 전에 정한다(초기 /me 호출 순서 오류 방지).
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
    text: id => h.get(id).textContent,
    allText: () => Object.values(h.els).map(e => e.allText).join('\n')
  };
  return p;
}
async function shown(account = EMPLOYER) { const p = page(account); await settle(); return p; }
async function preparedPage() { const p = await shown(); await p.click('prepare'); return p; }
const writes = p => p.posts().filter(c => c.url.endsWith('/early-end'));
const lastBody = p => JSON.parse(writes(p).at(-1).options.body);

test('진입: /me 확인만, 준비·기록 POST 없음, 고용주에게 준비 버튼만 활성', async () => {
  const p = await shown();
  assert.deepEqual(p.urls(), ['GET /api/auth/me']);
  assert.equal(p.posts().length, 0);
  assert.equal(p.el('prepare').disabled, false);
  for (const id of ['record', 'repeat', 'different', 'different-time', 'refresh', 'actual-end', 'reason', 'detail', 'weather'])
    assert.equal(p.el(id).disabled, true, id);
  assert.deepEqual(p.forbidden, []);
});

test('근로자·비로그인: 준비·기록 불가, 클릭해도 CSRF·POST 없음', async () => {
  for (const account of [WORKER, null]) {
    const p = await shown(account);
    assert.equal(p.el('prepare').disabled, true);
    await p.click('prepare');
    await p.click('record');
    assert.equal(p.posts().length, 0);
    assert.equal(p.calls.filter(c => c.url === '/api/auth/csrf').length, 0);
  }
  const worker = await shown(WORKER);
  assert.match(worker.text('notice'), /고용주 계정으로 로그인해야/);
});

test('로그인 메뉴: 조기 종료 링크는 고용주에게만 표시 (farm-login.html 실행)', async () => {
  const script = LOGIN.match(/<script>([\s\S]*?)<\/script>/)[1];
  assert.ok(LOGIN.includes('id="early-end-link" hidden>'));
  for (const [account, hidden] of [[EMPLOYER, false], [WORKER, true], [null, true]]) {
    const server = { handle: url => {
      if (url === '/api/auth/me') return account ? { status: 200, body: { ...account, nationalityType: null } } : { status: 401 };
      throw new Error('unexpected ' + url);
    } };
    const h = harness(script, server, { 'early-end-link': true });
    await settle();
    assert.equal(h.get('early-end-link').hidden, hidden, String(account?.role));
  }
});

test('준비 → 사례 표 → 정상 자동 선택 → 입력 연결, 수동 선택도 입력 교체', async () => {
  const p = await preparedPage();
  assert.match(p.text('fixture-status'), /시연 준비 HTTP 201\n새 가상 공고를 준비했습니다/);
  assert.equal(p.el('cases').children.length, 7);
  assert.equal(p.row(7001).dataset.selected, 'true');
  assert.match(p.row(7004).children[2].textContent, /직접 입력 마감 2026-11-28T17:00:00\+09:00/);
  assert.match(p.text('target-note'), /확정 근무 #7001\n약정 시작: 2026-11-29T00:00:00\+09:00\n약정 종료: .*\n직접 입력 마감: 2026-11-30T23:59:59.999999\+09:00/);
  assert.equal(p.el('reason').value, 'WEATHER');
  assert.equal(p.el('weather').value, 'RAIN');
  assert.equal(p.el('detail').value, DETAIL);
  assert.ok(p.urls().includes('GET /demo/api/employer/work-assignments/7001'));
  await p.choose(7006);
  assert.equal(p.el('detail').value, '[CHANGE-03 시연] 기존 가상 기상 변경');
  assert.equal(p.el('actual-end-raw').value, '2026-11-26T14:00:00+09:00');
  assert.match(p.text('target-note'), /이미 조기 종료된 사례입니다\. 「조기 종료 기록 보내기」는 기존 결과 200을 확인합니다\./);
});

test('달력·전송 시각: 소수초 6자리 보존, Z를 GMT+9로 표시하되 원문 전송, 편집 시 +09:00 갱신', async () => {
  const p = await preparedPage();
  assert.equal(p.el('actual-end').value, '2026-11-29T00:29:00.123456');
  assert.equal(p.el('actual-end-raw').value, '2026-11-29T00:29:00.123456+09:00');
  await p.choose(7002);
  assert.equal(p.el('actual-end').value, '2026-11-28T22:59:59.999999', 'Z → GMT+9 표시, 마이크로초 유지');
  assert.equal(p.el('actual-end-raw').value, '2026-11-28T13:59:59.999999Z', '전송 시각은 서버 원문');
  await p.click('record');
  assert.equal(lastBody(p).actualEndAt, '2026-11-28T13:59:59.999999Z');
  assert.match(p.text('early-end-status'), /^HTTP 200/);
  // 사용자가 달력을 편집하면 +09:00 전송 시각으로 갱신
  await p.choose(7001);
  await p.input('actual-end', '2026-11-29T00:10');
  assert.equal(p.el('actual-end-raw').value, '2026-11-29T00:10:00+09:00');
  await p.input('actual-end', '2026-11-29T00:10:05');
  assert.equal(p.el('actual-end-raw').value, '2026-11-29T00:10:05+09:00');
  await p.click('record');
  assert.equal(lastBody(p).actualEndAt, '2026-11-29T00:10:05+09:00');
  await p.input('actual-end', '');
  assert.equal(p.el('actual-end-raw').value, '');
  const n = writes(p).length;
  await p.choose(7003);
  await p.input('actual-end', '');
  await p.click('record');
  assert.equal(writes(p).length, n, '전송 시각이 비면 POST 없음');
  assert.match(p.text('notice'), /실제 종료 일시를 선택해 주세요/);
});

test('POST마다 새 CSRF 발급·전달, 토큰·쿠키·보험 키 비노출', async () => {
  const p = await preparedPage();
  await p.click('record');
  await p.click('repeat');
  await p.click('different');
  await p.click('different-time');
  const posts = p.posts();
  assert.equal(posts.length, 5);
  for (const post of posts) {
    const index = p.calls.indexOf(post), before = p.calls.slice(0, index);
    const csrfIndex = before.map(c => c.url).lastIndexOf('/api/auth/csrf');
    assert.ok(csrfIndex >= 0 && !before.slice(csrfIndex).some(c => c.options.method === 'POST'));
    assert.equal(post.options.headers['X-CSRF-TOKEN'], p.calls[csrfIndex].reply.body.token);
    assert.equal(post.options.credentials, 'same-origin');
    assert.equal(Object.keys(post.options.headers).some(k => /authorization|cookie|x-api-key|insurance/i.test(k)), false);
  }
  assert.equal(new Set(posts.map(c => c.options.headers['X-CSRF-TOKEN'])).size, 5);
  assert.ok(!p.allText().includes('csrf-SECRET'));
  assert.ok(!p.el('requests').allText.includes('csrf'));
  assert.ok(!/Bearer|serviceToken|insurance-token/i.test(p.allText()));
  assert.deepEqual(p.forbidden, []);
});

test('CSRF 실패 시 쓰기 POST 없음', async () => {
  const p = await preparedPage();
  const before = p.posts().length;
  for (const reply of [{ status: 500, body: { code: 'X', message: 'x' } }, { status: 200, body: { headerName: 'X-CSRF-TOKEN', token: '' } },
    { status: 200, body: { token: 'only' } }, new TypeError('network')]) {
    p.once('/api/auth/csrf', reply);
    await p.click('record');
  }
  p.once('/api/auth/csrf', { status: 403 });
  await p.click('prepare');
  assert.equal(p.posts().length, before);
  assert.match(p.text('notice'), /CSRF 토큰을 발급받지 못했습니다/);
  assert.equal(p.el('record').disabled, false);
});

test('최초 기록 200, 비교 기준 보관, 기록 POST와 상태 GET 분리', async () => {
  const p = await preparedPage();
  await p.click('record');
  assert.deepEqual(lastBody(p), { actualEndAt: '2026-11-29T00:29:00.123456+09:00', reasonCode: 'WEATHER', weatherReasonCode: 'RAIN', reasonDetail: DETAIL });
  assert.match(p.text('early-end-status'), /^HTTP 200 · 조기 종료 요청\n서버가 조기 종료 결과를 반환했습니다\./);
  assert.match(p.text('comparison'), /처음 받은 성공 응답을 비교 기준으로 보관/);
  assert.equal(p.text('change-id'), '9001');
  assert.equal(p.text('source-version'), '1');
  assert.equal(p.text('effective-at'), '2026-11-29T00:29:00.123456+09:00');
  assert.equal(p.text('recorded-at'), REC);
  assert.notEqual(p.text('effective-at'), p.text('recorded-at'));
  assert.equal(JSON.parse(p.text('early-end-response')).changeId, 9001);
  assert.equal(JSON.parse(p.text('read-response')).changeType, 'EARLY_ENDED');
  assert.equal(p.row(7001).children[3].textContent, '조기 종료');
  for (const id of ['repeat', 'different', 'different-time']) assert.equal(p.el(id).disabled, false, id);
});

test('동일 재요청: 입력 칸 변경과 무관하게 직전 성공 본문 재전송, 비교 로직 실제 동작', async () => {
  const p = await preparedPage();
  await p.click('record');
  const first = lastBody(p);
  await p.change('reason', 'OTHER');
  await p.input('detail', '다른 설명');
  await p.input('actual-end', '2026-11-29T00:01');
  await p.click('repeat');
  assert.deepEqual(lastBody(p), first);
  assert.match(p.text('early-end-status'), /^HTTP 200 · 동일 내용 재요청/);
  assert.match(p.text('comparison'), /기존 결과 유지 확인: 변경 ID·사유·버전·실제 종료\/저장\/수정 시각이 모두 같습니다/);
  assert.equal(p.el('comparison').dataset.error, 'false');
  p.once(url => url.endsWith('/early-end'), { status: 200, body: { assignmentId: 7001, ...p.server.changes[7001], sourceVersion: '2', updatedAt: '2026-11-29T01:00:00+09:00' } });
  await p.click('repeat');
  assert.match(p.text('comparison'), /달라진 필드: .*updatedAt.*sourceVersion|달라진 필드: .*sourceVersion/);
  assert.equal(p.el('comparison').dataset.error, 'true');
});

test('다른 사유·종료 시각 변경 409: 유효한 조합, 정확히 1초+소수초 보존, 기준 유지', async () => {
  const p = await preparedPage();
  await p.click('record');
  const first = lastBody(p);
  await p.click('different');
  assert.deepEqual(lastBody(p), { ...first, reasonCode: 'OTHER', weatherReasonCode: null });
  assert.match(p.text('early-end-status'), /^HTTP 409 · EARLY_END_CONTENT_CONFLICT · 다른 사유 재요청/);
  await p.click('different-time');
  const shifted = lastBody(p);
  assert.equal(shifted.actualEndAt, '2026-11-29T00:29:01.123456+09:00');
  assert.equal(micros(shifted.actualEndAt) - micros(first.actualEndAt), 1000000n);
  assert.deepEqual({ ...shifted, actualEndAt: first.actualEndAt }, first, '시각 외 필드는 그대로');
  assert.match(p.text('early-end-status'), /^HTTP 409 · EARLY_END_CONTENT_CONFLICT · 종료 시각 변경 재요청/);
  assert.match(p.text('comparison'), /다른 내용 요청은 409로 거절됐습니다/);
  assert.equal(p.text('change-id'), '9001');
  assert.equal(p.server.changes[7001].effectiveAt, first.actualEndAt);
  await p.click('repeat');
  assert.deepEqual(lastBody(p), first, '409 뒤에도 재요청 기준은 직전 성공');
  assert.match(p.text('comparison'), /기존 결과 유지 확인/);
  // Z·분 경계: 13:59:59.999999Z(+1초) → 2026-11-28T23:00:00.999999+09:00
  await p.choose(7002);
  await p.click('record');
  await p.click('different-time');
  assert.equal(lastBody(p).actualEndAt, '2026-11-28T23:00:00.999999+09:00');
  // OTHER 성공 뒤 다른 사유는 FARM_CIRCUMSTANCES
  p.server.cases.find(c => c.id === 7003).start = '2026-11-28T00:00:00+09:00';
  await p.choose(7003);
  await p.change('reason', 'OTHER');
  await p.input('actual-end', '2026-11-28T10:00');
  await p.click('record');
  await p.click('different');
  assert.equal(lastBody(p).reasonCode, 'FARM_CIRCUMSTANCES');
  assert.equal(lastBody(p).weatherReasonCode, null);
});

test('기한 초과 409와 문의 안내, 완료 상태로 표시하지 않음; 시작 전·취소·완료 409', async () => {
  const p = await preparedPage();
  await p.choose(7004);
  await p.click('record');
  assert.match(p.text('early-end-status'), /^HTTP 409 · EARLY_END_DEADLINE_PASSED · 조기 종료 요청\n.*담당자에게 문의해 주세요/);
  assert.match(p.text('notice'), /EARLY_END_DEADLINE_PASSED.*담당자에게 문의/);
  assert.equal(p.row(7004).children[3].textContent, '정상 확정');
  assert.doesNotMatch(p.text('stored-state'), /완료$|· 완료/);
  assert.match(p.text('stored-state'), /정상 확정[\s\S]*완료 일시: 없음/);
  assert.equal(p.text('change-id'), '—');
  assert.equal(p.el('repeat').disabled, true);
  for (const [id, code] of [[7003, 'WORK_NOT_STARTED'], [7005, 'WORK_ALREADY_CANCELLED'], [7007, 'WORK_ALREADY_COMPLETED']]) {
    await p.choose(id);
    await p.click('record');
    assert.match(p.text('early-end-status'), new RegExp('^HTTP 409 · ' + code), String(id));
    assert.equal(p.text('change-id'), '—');
  }
});

test('이미 기록된 사례: 기존 결과 200 안내, 저장 시각·ID 표시, "최초 기록"으로 표시하지 않음', async () => {
  const p = await preparedPage();
  await p.click('prepare');
  assert.match(p.text('fixture-status'), /시연 준비 HTTP 200\n기존 시연 공고와 상태를 재사용합니다/);
  await p.choose(7006);
  await p.click('record');
  assert.deepEqual(lastBody(p), { actualEndAt: '2026-11-26T14:00:00+09:00', reasonCode: 'WEATHER', weatherReasonCode: 'RAIN',
    reasonDetail: '[CHANGE-03 시연] 기존 가상 기상 변경' });
  assert.match(p.text('early-end-status'), /^HTTP 200/);
  assert.equal(p.text('change-id'), '9006');
  assert.equal(p.text('recorded-at'), '2026-11-25T09:00:00.000001+09:00');
  for (const id of ['notice', 'early-end-status', 'comparison', 'target-note']) assert.equal(/최초 기록|새로 기록/.test(p.text(id)), false, id);
  assert.match(p.text('target-note'), /이미 조기 종료된 사례/);
});

test('보조 GET 500·통신 오류가 기록 응답·선택·재요청을 지우거나 막지 않음', async () => {
  for (const failure of [{ status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } }, new TypeError('network')]) {
    const p = await preparedPage();
    p.once(url => url === '/demo/api/employer/work-assignments/7001', failure);
    await p.click('record');
    assert.match(p.text('early-end-status'), /^HTTP 200/);
    assert.equal(p.text('change-id'), '9001');
    assert.equal(p.row(7001).dataset.selected, 'true');
    assert.equal(p.el('stored-state').dataset.error, 'true');
    for (const id of ['repeat', 'different', 'different-time', 'record']) assert.equal(p.el(id).disabled, false, id);
    await p.click('repeat');
    assert.equal(writes(p).length, 2);
    assert.match(p.text('comparison'), /기존 결과 유지 확인/);
  }
});

test('중복 클릭 중 동시 POST 방지, POST 오류·통신 오류 때 자동 재전송 없음', async () => {
  const p = await preparedPage();
  p.once(url => url.endsWith('/early-end'), 'hold');
  await p.click('record');
  await p.click('record');
  await p.click('prepare');
  assert.equal(writes(p).length, 1);
  assert.equal(p.el('record').disabled, true);
  p.held.shift().resolve();
  await settle();
  assert.equal(writes(p).length, 1);

  const q = await preparedPage();
  q.once(url => url.endsWith('/early-end'), { status: 500, body: { code: 'INTERNAL_SERVER_ERROR', message: '서버 내부 오류가 발생했습니다.' } });
  await q.click('record');
  assert.match(q.text('early-end-status'), /^HTTP 500/);
  q.once(url => url.endsWith('/early-end'), new TypeError('Failed to fetch'));
  await q.click('record');
  await settle();
  assert.equal(writes(q).length, 2);
  assert.match(q.text('notice'), /결과가 불확실할 수 있습니다.*자동 재전송하지 않습니다/s);
  assert.equal(q.el('repeat').disabled, true);
});

test('계정 변경(요청 전·요청 중) 시 이전 사례·응답 제거, pagehide 뒤 응답 무효화', async () => {
  const before = await preparedPage();
  await before.click('record');
  before.server.account = OTHER;
  const n = before.posts().length;
  await before.click('repeat');
  assert.equal(before.posts().length, n);
  assert.equal(before.el('cases').children.length, 0);
  assert.equal(before.text('change-id'), '—');
  assert.match(before.text('notice'), /계정이 바뀌어 이전 계정의 사례와 응답을 지웠습니다/);

  const during = await preparedPage();
  during.once(url => url.endsWith('/early-end'), 'hold');
  await during.click('record');
  during.server.account = OTHER;
  during.held.shift().resolve();
  await settle();
  assert.equal(during.el('cases').children.length, 0);
  assert.equal(during.text('early-end-status'), '조기 종료 요청 전');
  assert.equal(during.el('repeat').disabled, true);

  const hidden = await preparedPage();
  hidden.once(url => url.endsWith('/early-end'), 'hold');
  await hidden.click('record');
  await hidden.hide();
  hidden.held.shift().resolve();
  await settle();
  assert.equal(hidden.el('cases').children.length, 0);
  assert.equal(hidden.text('early-end-response'), '응답 없음');
  assert.equal(hidden.text('change-id'), '—');
  assert.equal(hidden.el('actual-end-raw').value, '');
  assert.equal(hidden.text('account'), '로그인 상태를 다시 확인해 주세요.');
});

test('설명 길이 코드 포인트 계산, 501자 전송 후 서버 400 표시, 기상 세부 사유 누락 400', async () => {
  const p = await preparedPage();
  await p.input('detail', ' 　' + '😀'.repeat(500) + '\n ');
  assert.match(p.text('detail-count'), /^앞뒤 공백 제거 후 500 \/ 500 코드 포인트$/);
  await p.input('detail', '가'.repeat(501));
  assert.match(p.text('detail-count'), /501 \/ 500 코드 포인트 · 전송하면 서버에서 400으로 거절합니다/);
  assert.equal(p.el('detail-count').dataset.error, 'true');
  await p.click('record');
  assert.equal(lastBody(p).reasonDetail, '가'.repeat(501));
  assert.match(p.text('early-end-status'), /^HTTP 400 · INVALID_REQUEST/);
  await p.input('detail', '');
  await p.change('weather', '');
  await p.click('record');
  assert.equal(lastBody(p).weatherReasonCode, null);
  assert.match(p.text('early-end-status'), /^HTTP 400/);
  assert.equal(p.el('repeat').disabled, true);
});

test('사용자 문자열은 textContent로만 표시', async () => {
  const p = await shown();
  const evil = '<img src=x onerror=alert(1)>';
  p.server.labels[7001] = evil;
  p.server.changes[7006].reasonDetail = '<script>alert(2)</script>';
  await p.click('prepare');
  assert.equal(p.row(7001).children[0].textContent, evil);
  assert.match(p.text('target-note'), /<img src=x onerror=alert\(1\)>/);
  await p.choose(7006);
  assert.equal(p.el('detail').value, '<script>alert(2)</script>');
  await p.click('record');
  assert.match(p.text('early-end-response'), /<script>alert\(2\)<\/script>/);
  assert.deepEqual(p.forbidden, []);
});

// farm-insurance-salary-account.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·window·fetch는 모두 가짜이며 실제 서버에 연결하지 않는다. 실제 브라우저 동작·HTTP·세션은 검증하지 않는다.
// 실행 (저장소 루트): node --test farm-api/docs/verification/2026-11-27-insurance-salary-account/insurance-salary-account-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(process.env.INSURANCE_SALARY_HTML
  ?? path.resolve(here, '../../../src/main/resources/demo/farm-insurance-salary-account.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const ENDPOINT = '/demo/api/insurance/salary-account';
const WORKER = { id: 101, name: 'TEST WORKER', role: 'WORKER' };
const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };

function page() {
  const ids = ['member', 'refresh', 'manage', 'worker-id', 'registered', 'bank-code', 'comparison', 'status', 'result'];
  const els = Object.fromEntries(ids.map(id => [id, { id, textContent: '', disabled: false, hidden: id === 'manage', dataset: {}, listeners: {},
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); } }]));
  const calls = [];
  const forbidden = [];
  const windowListeners = {};
  // 각 URL 응답 큐. 함수면 호출 시점에 결정, Error면 네트워크 오류, 'hold'면 수동 해제.
  const queue = { me: [], account: [] };
  const defaults = { me: { status: 200, body: WORKER }, account: { status: 200, body: { workerId: 101, registered: true, bankCode: '020' } } };
  let held = null;
  const respond = spec => ({ ok: spec.status >= 200 && spec.status < 300, status: spec.status,
    json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } });
  const trap = name => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });
  const context = {
    document: { getElementById: id => { if (!els[id]) throw new Error('missing #' + id); return els[id]; } },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); } },
    AbortController,
    console: trap('console'), localStorage: trap('localStorage'), sessionStorage: trap('sessionStorage'),
    history: trap('history'), location: trap('location'),
    fetch: (url, options = {}) => {
      calls.push({ url, options });
      const key = url === '/api/auth/me' ? 'me' : url === ENDPOINT ? 'account' : null;
      if (!key) throw new Error('unexpected url ' + url);
      let spec = queue[key].length ? queue[key].shift() : defaults[key];
      if (typeof spec === 'function') spec = spec();
      if (spec === 'hold') return new Promise((resolve, reject) => { held = { resolve: s => resolve(respond(s)), reject }; });
      return spec instanceof Error ? Promise.reject(spec) : Promise.resolve(respond(spec));
    }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  const click = async () => { els.refresh.listeners.click.forEach(fn => fn()); await settle(); };
  const show = async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); };
  const view = () => ({ worker: els['worker-id'].textContent, registered: els.registered.textContent, bank: els['bank-code'].textContent,
    comparison: els.comparison.textContent, result: els.result.textContent, status: els.status.textContent });
  return { els, calls, forbidden, windowListeners, queue, defaults, context, click, show, view, release: () => held };
}

test('조회 순서: /me → 본인 데모 조회 → /me 재확인, same-origin·no-store, Authorization·내부 URL·쿼리 없음', async () => {
  const p = page();
  await p.show();
  assert.deepEqual(p.calls.map(c => c.url), ['/api/auth/me', ENDPOINT, '/api/auth/me']);
  for (const call of p.calls) {
    assert.equal(call.options.credentials, 'same-origin');
    assert.equal(call.options.cache, 'no-store');
    assert.equal(call.options.headers, undefined, '헤더(Authorization 포함) 없음');
    assert.ok(!call.url.includes('?') && !call.url.includes('/api/internal/'));
  }
});

test('등록·미등록 표시와 관리 화면 링크 노출, 결과는 세 필드 JSON', async () => {
  const p = page();
  await p.show();
  assert.equal(p.view().registered, '등록됨 (true)');
  assert.equal(p.view().bank, '020');
  assert.equal(p.view().worker, '101');
  assert.deepEqual(JSON.parse(p.view().result), { workerId: 101, registered: true, bankCode: '020' });
  assert.equal(p.els.manage.hidden, false);

  p.queue.account.push({ status: 200, body: { workerId: 101, registered: false, bankCode: null } });
  await p.click();
  assert.equal(p.view().registered, '미등록 (false)');
  assert.equal(p.view().bank, 'null · 미등록');
  assert.deepEqual(JSON.parse(p.view().result), { workerId: 101, registered: false, bankCode: null });
});

test('이전 조회와 비교: 첫 조회 → 미등록→등록 → 은행 변경 → 같은 정보(변화 없음 안내) → 해제', async () => {
  const p = page();
  const steps = [
    [{ workerId: 101, registered: false, bankCode: null }, /^첫 조회입니다/],
    [{ workerId: 101, registered: true, bankCode: '020' }, /^이전 조회: 미등록 → 이번 조회: 등록됨$/],
    [{ workerId: 101, registered: true, bankCode: '088' }, /^은행 코드 변경: 020 → 088$/],
    [{ workerId: 101, registered: true, bankCode: '088' }, /^이전 조회와 등록 여부·은행 코드가 같습니다\. 같은 은행의 계좌번호 변경은/],
    [{ workerId: 101, registered: false, bankCode: null }, /^이전 조회: 등록됨 → 이번 조회: 미등록$/]
  ];
  for (const [body, message] of steps) {
    p.queue.account.push({ status: 200, body });
    await p.click();
    assert.match(p.view().comparison, message);
  }
});

test('비로그인(401)·고용주·잘못된 /me 응답이면 계좌 조회 없이 결과를 지운다', async () => {
  for (const me of [{ status: 401 }, { status: 200, body: { id: 3, name: 'E', role: 'EMPLOYER' } },
    { status: 200, body: { id: 0, name: 'W', role: 'WORKER' } }, { status: 200, body: { id: '101', name: 'W', role: 'WORKER' } }]) {
    const p = page();
    p.queue.me.push(me);
    await p.show();
    assert.ok(!p.calls.some(c => c.url === ENDPOINT));
    assert.equal(p.view().worker, '—');
    assert.equal(p.els.manage.hidden, true);
    assert.equal(p.els.status.dataset.error, 'true');
    assert.equal(p.els.refresh.disabled, false);
  }
});

test('조회 도중 계정이 바뀌면(두 번째 /me 불일치·로그아웃) 결과와 이전 상태를 지운다', async () => {
  for (const after of [{ status: 200, body: { id: 202, name: 'OTHER', role: 'WORKER' } }, { status: 401 },
    { status: 200, body: { id: 101, name: 'W', role: 'EMPLOYER' } }]) {
    const p = page();
    await p.show();
    p.queue.me.push({ status: 200, body: WORKER }, after);
    p.queue.account.push({ status: 200, body: { workerId: 101, registered: true, bankCode: '088' } });
    await p.click();
    assert.equal(p.view().bank, '—');
    assert.equal(p.view().result, 'workerId·registered·bankCode만 표시합니다.');
    assert.match(p.view().status, /계정이 변경되어 결과를 표시하지 않았습니다/);
    assert.ok(!JSON.stringify(p.view()).includes('088'));
    await p.click();
    assert.match(p.view().comparison, /^첫 조회입니다/, '이전 조회 상태도 지워져 비교하지 않음');
  }
});

test('응답 workerId 불일치·형식 오류는 표시하지 않고 미등록으로도 바꾸지 않는다', async () => {
  const bad = [
    { workerId: 202, registered: true, bankCode: '020' },
    { workerId: '101', registered: true, bankCode: '020' },
    { workerId: 101, registered: 'true', bankCode: '020' },
    { workerId: 101, registered: true, bankCode: null },
    { workerId: 101, registered: true, bankCode: '20' },
    { workerId: 101, registered: false, bankCode: '020' },
    { workerId: 101 },
    null
  ];
  for (const body of bad) {
    const p = page();
    await p.show();
    p.queue.account.push({ status: 200, body });
    await p.click();
    const v = p.view();
    assert.equal(v.registered, '조회 필요', JSON.stringify(body));
    assert.ok(!v.result.includes('false') && !v.result.includes('202'));
    assert.match(v.status, /미등록으로 처리하지 않았습니다/);
    assert.equal(p.els.manage.hidden, true);
  }
});

test('서버가 여분 민감 필드를 보내도 세 필드만 표시', async () => {
  const p = page();
  p.queue.account.push({ status: 200, body: { workerId: 101, registered: true, bankCode: '020', accountNumber: '100200300400',
    maskedAccountNumber: '********0400', accountHolderName: 'TEST WORKER', version: 3, ciphertext: 'v1:abc' } });
  await p.show();
  assert.deepEqual(Object.keys(JSON.parse(p.view().result)), ['workerId', 'registered', 'bankCode']);
  const text = JSON.stringify(p.view());
  for (const secret of ['100200300400', '0400', 'v1:abc', 'version', 'maskedAccountNumber', 'accountHolderName']) assert.ok(!text.includes(secret), secret);
});

test('조회 오류(404·503·500·HTML·네트워크)는 registered:false 성공으로 바꾸지 않고, 오류 후 버튼 복구', async () => {
  const cases = [
    [{ status: 404, body: { code: 'WORKER_NOT_FOUND', message: 'raw server text' } }, /^조회 HTTP 404 · 해당 근로자를 찾을 수 없습니다/],
    [{ status: 503, body: { code: 'SERVICE_UNAVAILABLE', message: 'x' } }, /^조회 HTTP 503/],
    [{ status: 500 }, /^조회 HTTP 500 · 서버 연결과 응답을 확인해 주세요/],
    [{ status: 403, body: { code: 'DEMO_ACCESS_DENIED' } }, /^조회 HTTP 403 · 근로자 본인 계정으로 로그인해 주세요/],
    [new Error('network'), /미등록으로 처리하지 않았습니다/]
  ];
  for (const [spec, pattern] of cases) {
    const p = page();
    await p.show();
    p.queue.account.push(spec);
    await p.click();
    const v = p.view();
    assert.match(v.status, pattern);
    assert.equal(p.els.status.dataset.error, 'true');
    assert.notEqual(v.registered, '미등록 (false)');
    assert.ok(!v.result.includes('"registered"'));
    assert.ok(!v.result.includes('raw server text'), '서버 메시지 원문 대신 고정 문구');
    assert.equal(p.els.refresh.disabled, false);
    assert.equal(p.els.manage.hidden, true);
  }
});

test('중복 조회 방지: 응답 대기 중 재클릭 무시, 버튼 비활성 → 완료 후 복구', async () => {
  const p = page();
  await p.show();
  p.calls.length = 0;
  p.queue.account.push('hold');
  await p.click();
  assert.equal(p.els.refresh.disabled, true);
  await p.click();
  await p.click();
  assert.equal(p.calls.filter(c => c.url === ENDPOINT).length, 1);
  p.release().resolve({ status: 200, body: { workerId: 101, registered: false, bankCode: null } });
  await settle();
  assert.equal(p.els.refresh.disabled, false);
  assert.equal(p.view().registered, '미등록 (false)');
});

test('페이지 이탈: 표시·이전 조회 상태 정리, 이탈 뒤 도착한 응답은 다시 표시하지 않음', async () => {
  const p = page();
  await p.show();
  p.queue.account.push('hold');
  await p.click();
  p.windowListeners.pagehide.forEach(fn => fn());
  assert.equal(p.view().bank, '—');
  assert.equal(p.view().result, 'workerId·registered·bankCode만 표시합니다.');
  assert.equal(p.els.refresh.disabled, false);
  p.release().resolve({ status: 200, body: { workerId: 101, registered: true, bankCode: '088' } });
  await settle();
  assert.ok(!JSON.stringify(p.view()).includes('088'));
  assert.equal(p.els.manage.hidden, true);
  p.queue.account.push({ status: 200, body: { workerId: 101, registered: true, bankCode: '020' } });
  await p.show();
  assert.match(p.view().comparison, /^첫 조회입니다/, '이탈 전 조회와 비교하지 않음');
});

test('포커스 처리기 없음, 저장소·로그·URL 접근 없음', async () => {
  const p = page();
  await p.show();
  await p.click();
  assert.deepEqual(Object.keys(p.windowListeners).sort(), ['pagehide', 'pageshow']);
  assert.deepEqual(p.forbidden, []);
});

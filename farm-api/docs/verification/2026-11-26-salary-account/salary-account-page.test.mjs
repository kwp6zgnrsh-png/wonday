// farm-salary-account.html의 인라인 스크립트 원문을 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// document·fetch·window는 최소 가짜 객체이고, 서버는 문서의 버전 정책을 흉내 낸 가짜다.
// 실제 브라우저 렌더링·폼 검증·커서 이동·bfcache·네트워크·서버 CSRF 처리는 검증하지 않는다.
// 실행 (저장소 루트): node --test farm-api/docs/verification/2026-11-26-salary-account/salary-account-page.test.mjs
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const html = readFileSync(process.env.SALARY_HTML ?? path.resolve(here, '../../../src/main/resources/demo/farm-salary-account.html'), 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];
const optionValues = [...html.matchAll(/<option value="([^"]*)">([^<]*)<\/option>/g)].map(m => ({ value: m[1], textContent: m[2] }));
const ENDPOINT = '/api/me/salary-account';
const CSRF = { headerName: 'X-CSRF-TOKEN', token: 'test-csrf-token-0001' };
const settle = async () => { for (let i = 0; i < 20; i++) await new Promise(r => setImmediate(r)); };
const mask = digits => digits.length <= 4 ? '*'.repeat(digits.length) : '*'.repeat(digits.length - 4) + digits.slice(-4);

// 문서 정책을 흉내 낸 가짜 서버. 여러 화면(창)이 공유할 수 있다.
function server({ registered = false, version = 0, me = { id: 101, name: 'TEST WORKER', role: 'WORKER' } } = {}) {
  const s = {
    me, version, account: registered ? { bankCode: '020', digits: '100200300400', holder: 'TEST WORKER' } : null,
    once: {}, held: null, calls: [],
    view() {
      return s.account
        ? { registered: true, bankCode: s.account.bankCode, maskedAccountNumber: mask(s.account.digits), accountHolderName: s.account.holder, version: s.version }
        : { registered: false, bankCode: null, maskedAccountNumber: null, accountHolderName: null, version: s.version };
    },
    handle(url, options) {
      const method = options.method || 'GET';
      const key = method + ' ' + url.split('?')[0];
      if (s.once[key]) { const r = s.once[key]; delete s.once[key]; return typeof r === 'function' ? r() : r; }
      if (url === '/api/auth/me') return typeof s.me === 'function' ? s.me() : s.me ? { status: 200, body: s.me } : { status: 401 };
      if (url === '/api/auth/csrf') return { status: 200, body: CSRF };
      if (method !== 'GET' && options.headers?.[CSRF.headerName] !== CSRF.token) return { status: 403 };
      if (key === 'GET ' + ENDPOINT) return { status: 200, body: s.view() };
      if (key === 'PUT ' + ENDPOINT) {
        const b = JSON.parse(options.body);
        const digits = String(b.accountNumber).replaceAll('-', '');
        if (!/^[0-9]{3}$/.test(b.bankCode) || !/^[0-9]{1,30}$/.test(digits) || !Number.isInteger(b.version)) return { status: 400, body: { code: 'INVALID_REQUEST', message: 'x' } };
        if (b.version !== s.version) return { status: 409, body: { code: 'SALARY_ACCOUNT_VERSION_CONFLICT', message: 'x' } };
        s.account = { bankCode: b.bankCode, digits, holder: b.accountHolderName.trim() };
        s.version++;
        return { status: 200, body: s.view() };
      }
      if (key === 'DELETE ' + ENDPOINT) {
        const v = Number(new URL(url, 'http://x').searchParams.get('version'));
        if (!s.account) return { status: 204 };
        if (v !== s.version) return { status: 409, body: { code: 'SALARY_ACCOUNT_VERSION_CONFLICT', message: 'x' } };
        s.account = null;
        s.version++;
        return { status: 204 };
      }
      throw new Error('unexpected ' + key);
    }
  };
  return s;
}

function el(id, extra = {}) {
  return {
    id, value: '', textContent: '', disabled: false, hidden: false, checked: false, placeholder: '', dataset: {}, listeners: {},
    validationMessage: '', selectionStart: null, selectionEnd: null,
    addEventListener(type, fn) { (this.listeners[type] ??= []).push(fn); },
    fire(type, event = {}) { (this.listeners[type] || []).forEach(fn => fn({ preventDefault() {}, isComposing: false, ...event })); },
    setCustomValidity(message) { this.validationMessage = message; },
    setSelectionRange(a, b) { this.selectionStart = a; this.selectionEnd = b; },
    ...extra
  };
}

function page(srv) {
  const ids = ['account-form', 'bank-code', 'account-number', 'holder', 'status', 'refresh', 'fields', 'remove', 'custom-bank',
    'custom-bank-code', 'auto-format', 'stale-note', 'registered', 'current-bank', 'current-number', 'current-holder', 'version',
    'member', 'result', 'bank-hint', 'number-hint'];
  const els = Object.fromEntries(ids.map(id => [id, el(id)]));
  els['bank-code'].options = optionValues;
  els['auto-format'].checked = true;
  els['account-form'].reset = () => {
    els['bank-code'].value = ''; els['custom-bank-code'].value = ''; els['account-number'].value = ''; els['holder'].value = '';
    els['auto-format'].checked = true;
  };
  els['account-form'].reportValidity = () => {
    const n = els['account-number'];
    return els['bank-code'].value !== '' && n.value !== '' && n.validationMessage === '' && els['holder'].value !== '';
  };
  const calls = [];
  const forbidden = [];
  const windowListeners = {};
  const documentListeners = [];
  const trap = name => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });
  const context = {
    document: {
      getElementById: id => { if (!els[id]) throw new Error('missing #' + id); return els[id]; },
      addEventListener: type => documentListeners.push(type),
      activeElement: null
    },
    window: { addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); } },
    AbortController,
    setTimeout, clearTimeout,
    console: trap('console'), localStorage: trap('localStorage'), sessionStorage: trap('sessionStorage'),
    history: trap('history'), location: trap('location'),
    fetch: (url, options = {}) => {
      calls.push({ url, method: options.method || 'GET', headers: { ...(options.headers || {}) }, body: options.body });
      srv.calls.push(url);
      if (srv.held && srv.held.match(url, options)) {
        return new Promise((resolve, reject) => { srv.held.resolve = spec => resolve(respond(spec)); srv.held.reject = reject; });
      }
      const spec = srv.handle(url, options);
      return spec instanceof Error ? Promise.reject(spec) : Promise.resolve(respond(spec));
    }
  };
  function respond(spec) {
    return { ok: spec.status >= 200 && spec.status < 300, status: spec.status,
      json: async () => { if (spec.body === undefined) throw new SyntaxError('no json'); return JSON.parse(JSON.stringify(spec.body)); } };
  }
  vm.createContext(context);
  vm.runInContext(script, context);
  const state = () => JSON.parse(JSON.stringify(vm.runInContext('({ snapshot, refreshRequired, busy, member })', context)));
  const show = async () => { windowListeners.pageshow.forEach(fn => fn()); await settle(); };
  const submit = async () => { els['account-form'].fire('submit'); await settle(); };
  const typeNumber = async value => { els['account-number'].value = value; els['account-number'].fire('input'); await settle(); };
  const selectBank = async value => { els['bank-code'].value = value; els['bank-code'].fire('change'); await settle(); };
  const shown = () => ['result', 'status', 'member', 'current-number', 'current-bank', 'current-holder'].map(id => els[id].textContent).join('\n');
  return { els, calls, forbidden, windowListeners, documentListeners, context, state, show, submit, typeNumber, selectBank, shown };
}

const urls = p => p.calls.map(c => c.method + ' ' + c.url);
const writes = p => p.calls.filter(c => c.method !== 'GET');

test('조회: 로그인 확인 → GET → 계정 재확인, 마스킹·버전 표시, 은행·예금주만 채우고 번호 칸에는 마스킹 번호를 넣지 않음', async () => {
  const srv = server({ registered: true, version: 1 });
  const p = page(srv);
  await p.show();
  assert.deepEqual(urls(p), ['GET /api/auth/me', 'GET ' + ENDPOINT, 'GET /api/auth/me']);
  assert.equal(p.els['current-number'].textContent, '********0400');
  assert.equal(p.els['version'].textContent, '1');
  assert.equal(p.els['registered'].textContent, '등록됨');
  assert.equal(p.els['bank-code'].value, '020');
  assert.equal(p.els['holder'].value, 'TEST WORKER');
  assert.equal(p.els['account-number'].value, '');
  assert.equal(p.els['fields'].disabled, false);
  assert.equal(p.els['remove'].disabled, false);
  assert.equal(p.els['stale-note'].hidden, true);
  assert.ok(p.calls.every(c => c.url === '/api/auth/me' || c.url === ENDPOINT), '데모 전용 API가 아닌 실제 경로만 사용');
});

test('비로그인·고용주는 입력·해제 비활성, 최신 조회 버튼은 사용 가능', async () => {
  for (const me of [null, { id: 3, name: 'E', role: 'EMPLOYER' }]) {
    const p = page(server({ me }));
    await p.show();
    assert.equal(p.els['fields'].disabled, true);
    assert.equal(p.els['remove'].disabled, true);
    assert.equal(p.els['refresh'].disabled, false);
    assert.ok(!p.calls.some(c => c.url === ENDPOINT), '근로자 확인 전 계좌 조회 없음');
  }
});

test('은행 선택 전 번호 입력 차단, 선택 후 허용', async () => {
  const p = page(server());
  await p.show();
  assert.equal(p.els['bank-code'].value, '');
  assert.equal(p.els['account-number'].disabled, true);
  await p.selectBank('004');
  assert.equal(p.els['account-number'].disabled, false);
  await p.selectBank('custom');
  assert.equal(p.els['account-number'].disabled, true, '기타 코드 3자리 입력 전');
  p.els['custom-bank-code'].value = '999';
  p.els['custom-bank-code'].fire('input');
  assert.equal(p.els['account-number'].disabled, false);
});

test('등록: CSRF 조회 → 계정 확인 → PUT(조회 버전, CSRF 헤더) → 계정 확인. 저장 직전 계좌 재조회 없음, 성공 시 새 버전·번호 칸 비움', async () => {
  const srv = server({ version: 4 });
  const p = page(srv);
  await p.show();
  await p.selectBank('020');
  await p.typeNumber('0123456789012');
  assert.equal(p.els['account-number'].value, '0123-456-789012', '우리 4-3-6 대표 형식');
  p.calls.length = 0;
  await p.submit();
  assert.deepEqual(urls(p), ['GET /api/auth/csrf', 'GET /api/auth/me', 'PUT ' + ENDPOINT, 'GET /api/auth/me']);
  const put = writes(p)[0];
  assert.equal(put.headers[CSRF.headerName], CSRF.token);
  assert.equal(put.headers['Content-Type'], 'application/json');
  assert.deepEqual(JSON.parse(put.body), { bankCode: '020', accountNumber: '0123-456-789012', accountHolderName: 'TEST WORKER', version: 4 });
  assert.equal(p.els['version'].textContent, '5');
  assert.equal(p.els['current-number'].textContent, '*********9012');
  assert.equal(p.els['account-number'].value, '');
  assert.ok(!p.shown().includes('0123456789012') && !p.shown().includes('0123-456-789012'));
  assert.match(p.els['status'].textContent, /^저장 HTTP 200/);
});

test('409: 다른 창이 먼저 저장하면 PUT 409, 자동 재시도 없이 쓰기 차단 → 최신 조회 후 다시 허용·저장 성공', async () => {
  const srv = server({ registered: true, version: 1 });
  const a = page(srv);
  const b = page(srv);
  await a.show();
  await b.show();
  await a.typeNumber('1111222233334');
  await a.submit();
  assert.equal(srv.version, 2);

  await b.typeNumber('5555666677778');
  await b.submit();
  assert.equal(writes(b).length, 1);
  assert.match(b.els['status'].textContent, /^PUT HTTP 409/);
  assert.equal(b.els['fields'].disabled, true);
  assert.equal(b.els['remove'].disabled, true);
  assert.equal(b.els['stale-note'].hidden, false);
  assert.equal(b.els['version'].textContent, '1', '409 응답으로 버전을 자동 갱신하지 않음');
  await b.submit();
  b.els['remove'].fire('click');
  await settle();
  assert.equal(writes(b).length, 1, '차단 중 재전송·해제 없음');

  b.els['refresh'].fire('click');
  await settle();
  assert.equal(b.els['version'].textContent, '2');
  assert.equal(b.els['fields'].disabled, false);
  assert.equal(b.els['account-number'].value, '5555-666-677778', '사용자 입력은 유지');
  await b.submit();
  assert.match(b.els['status'].textContent, /^저장 HTTP 200/);
  assert.equal(srv.version, 3);
});

test('다른 창으로 포커스만 이동: focus·visibilitychange 처리기가 없어 버전 자동 갱신 없음', async () => {
  const srv = server({ registered: true, version: 1 });
  const a = page(srv);
  const b = page(srv);
  await a.show();
  await b.show();
  await a.typeNumber('1234');
  await a.submit();
  const before = b.calls.length;
  assert.deepEqual(Object.keys(b.windowListeners).sort(), ['pagehide', 'pageshow']);
  assert.deepEqual(b.documentListeners, []);
  await settle();
  assert.equal(b.calls.length, before);
  assert.equal(b.els['version'].textContent, '1');
});

test('해제: CSRF → 계정 → DELETE ?version=조회값 → 계정 → 후속 GET, 204 후 미등록·새 버전 표시', async () => {
  const srv = server({ registered: true, version: 6 });
  const p = page(srv);
  await p.show();
  p.calls.length = 0;
  p.els['remove'].fire('click');
  await settle();
  assert.deepEqual(urls(p), ['GET /api/auth/csrf', 'GET /api/auth/me', 'DELETE ' + ENDPOINT + '?version=6', 'GET /api/auth/me',
    'GET /api/auth/me', 'GET ' + ENDPOINT, 'GET /api/auth/me']);
  assert.equal(writes(p)[0].headers[CSRF.headerName], CSRF.token);
  assert.equal(writes(p)[0].body, undefined);
  assert.equal(p.els['registered'].textContent, '미등록');
  assert.equal(p.els['version'].textContent, '7');
  assert.equal(p.els['result'].textContent, 'DELETE · HTTP 204\n응답 본문 없음');
  assert.match(p.els['status'].textContent, /후속 GET으로 최신 상태를 확인했습니다/);
  assert.equal(p.els['status'].dataset.error, 'false');
});

test('해제 204와 후속 조회 실패(HTTP 500·네트워크 오류)를 구분해 표시하고 쓰기를 막는다', async () => {
  for (const [failure, pattern] of [[{ status: 500 }, /해제 HTTP 204 성공 · 후속 조회는 실패/], [new Error('network'), /해제 HTTP 204 성공 · 후속 조회 결과를 확인하지 못했습니다/]]) {
    const srv = server({ registered: true, version: 1 });
    const p = page(srv);
    await p.show();
    srv.once['GET ' + ENDPOINT] = failure;
    p.els['remove'].fire('click');
    await settle();
    assert.equal(srv.account, null, '서버에서는 해제됨');
    assert.match(p.els['status'].textContent, pattern);
    assert.equal(p.els['status'].dataset.error, 'true');
    assert.equal(p.els['fields'].disabled, true);
    assert.equal(p.els['remove'].disabled, true);
    assert.equal(p.els['refresh'].disabled, false);
  }
});

test('해제 버전 정책: 등록 상태의 오래된 DELETE는 409로 차단, 다른 창이 이미 해제했으면 오래된 버전이어도 204', async () => {
  const srv = server({ registered: true, version: 1 });
  const a = page(srv);
  const b = page(srv);
  await a.show();
  await b.show();
  await a.typeNumber('1234');
  await a.submit();
  b.els['remove'].fire('click');
  await settle();
  assert.match(b.els['status'].textContent, /^DELETE HTTP 409/);
  assert.equal(b.els['remove'].disabled, true);
  assert.notEqual(srv.account, null);

  const c = page(srv);
  await c.show();
  a.els['remove'].fire('click');
  await settle();
  assert.equal(srv.account, null);
  c.els['remove'].fire('click');
  await settle();
  assert.equal(c.calls.find(x => x.method === 'DELETE').url, ENDPOINT + '?version=2');
  assert.match(c.els['status'].textContent, /^해제 HTTP 204 · 후속 GET/);
  assert.equal(c.els['version'].textContent, '3');
});

test('중복 전송 방지: 응답 대기 중 저장·해제·조회 무시, 처리 결과 불명(네트워크 오류) 후 자동 재시도 없이 최신 조회 요구', async () => {
  const srv = server({ registered: true, version: 1 });
  const p = page(srv);
  await p.show();
  await p.typeNumber('12345');
  srv.held = { match: (url, options) => options.method === 'PUT' };
  await p.submit();
  assert.equal(p.els['fields'].disabled, true);
  assert.equal(p.els['refresh'].disabled, true);
  await p.submit();
  p.els['remove'].fire('click');
  p.els['refresh'].fire('click');
  await settle();
  assert.equal(writes(p).length, 1);
  srv.held.reject(new Error('network'));
  await settle();
  assert.match(p.els['status'].textContent, /자동 재시도하지 않습니다/);
  assert.equal(p.state().refreshRequired, true);
  assert.equal(p.state().busy, false);
  assert.equal(p.els['fields'].disabled, true);
  assert.equal(p.els['refresh'].disabled, false);
  assert.equal(writes(p).length, 1);
});

test('400 오류 뒤에는 최신 조회 없이 수정·재시도 가능 (버전 그대로)', async () => {
  const srv = server({ version: 2 });
  const p = page(srv);
  await p.show();
  await p.selectBank('020');
  await p.typeNumber('1234');
  srv.once['PUT ' + ENDPOINT] = { status: 400, body: { code: 'INVALID_REQUEST', message: 'raw 1234 ignored' } };
  await p.submit();
  assert.match(p.els['status'].textContent, /^PUT HTTP 400 · 급여 계좌 입력값과 버전을 확인해 주세요/);
  assert.ok(!p.els['result'].textContent.includes('raw 1234 ignored'), '서버 메시지 원문 대신 고정 문구');
  assert.equal(p.els['fields'].disabled, false);
  await p.submit();
  assert.equal(JSON.parse(writes(p)[1].body).version, 2);
});

test('계정 변경: CSRF 후 다른 회원이면 PUT을 보내지 않고 번호·조회 결과·버전을 지운다', async () => {
  const srv = server({ registered: true, version: 1 });
  const p = page(srv);
  await p.show();
  await p.typeNumber('1234');
  srv.me = { id: 202, name: 'OTHER', role: 'WORKER' };
  await p.submit();
  assert.equal(writes(p).length, 0);
  assert.equal(p.els['account-number'].value, '');
  assert.equal(p.els['holder'].value, '');
  assert.equal(p.els['version'].textContent, '—');
  assert.equal(p.els['current-number'].textContent, '—');
  assert.equal(p.state().snapshot, null);
  assert.equal(p.els['fields'].disabled, true);
});

test('페이지 이탈: 입력 번호·조회 결과·버전 정리, 진행 중 요청 결과 무시', async () => {
  const srv = server({ registered: true, version: 1 });
  const p = page(srv);
  await p.show();
  await p.typeNumber('1234');
  srv.held = { match: (url, options) => options.method === 'PUT' };
  await p.submit();
  p.windowListeners.pagehide.forEach(fn => fn());
  assert.equal(p.els['account-number'].value, '');
  assert.equal(p.els['version'].textContent, '—');
  assert.equal(p.els['result'].textContent, '상태 코드와 마스킹된 응답이 여기에 표시됩니다.');
  srv.held.resolve({ status: 200, body: { registered: true, bankCode: '020', maskedAccountNumber: '****', accountHolderName: 'A', version: 2 } });
  await settle();
  assert.equal(p.els['version'].textContent, '—', '이탈 후 도착한 응답은 반영하지 않음');
  assert.equal(p.els['current-number'].textContent, '—');
});

test('형식: 확인된 대표 형식만 하이픈, 형식 없는 은행·자동 표시 해제는 그대로, 잘못된 문자·31자리는 지우거나 자르지 않고 저장 차단', async () => {
  const srv = server();
  const p = page(srv);
  await p.show();
  await p.selectBank('088');
  await p.typeNumber('012345678901');
  assert.equal(p.els['account-number'].value, '012-345-678901', '신한 3-3-6');
  await p.selectBank('081');
  await p.typeNumber('012345678901');
  assert.equal(p.els['account-number'].value, '012345678901', '하나: 대표 형식 미확인');
  await p.selectBank('020');
  p.els['auto-format'].checked = false;
  await p.typeNumber('0123456789012');
  assert.equal(p.els['account-number'].value, '0123456789012');
  p.els['auto-format'].checked = true;
  for (const bad of ['1234a', '1234 5678', '１２３４', '1'.repeat(31)]) {
    await p.typeNumber(bad);
    assert.equal(p.els['account-number'].value, bad, '원문 유지: ' + bad);
    assert.notEqual(p.els['account-number'].validationMessage, '');
    await p.submit();
  }
  assert.equal(writes(p).length, 0);
});

test('결과 표시는 마스킹 응답만: 서버가 마스킹 형식이 아닌 번호를 돌려주면 표시·보관하지 않는다', async () => {
  const srv = server();
  srv.once['GET ' + ENDPOINT] = { status: 200, body: { registered: true, bankCode: '020', maskedAccountNumber: '100200300400', accountHolderName: 'A', version: 1 } };
  const p = page(srv);
  await p.show();
  assert.ok(!p.shown().includes('100200300400'));
  assert.equal(p.state().snapshot, null);
  assert.equal(p.els['fields'].disabled, true);
  assert.match(p.els['status'].textContent, /최신 계좌를 다시 조회해 주세요/);
});

test('비노출: 전체 흐름에서 계좌 원문·요청 본문·CSRF가 화면·로그·저장소·URL에 남지 않는다', async () => {
  const srv = server({ registered: true, version: 1 });
  const p = page(srv);
  await p.show();
  await p.typeNumber('100200300400');
  await p.submit();
  srv.once['PUT ' + ENDPOINT] = { status: 503, body: { code: 'SERVICE_UNAVAILABLE', message: 'x' } };
  await p.typeNumber('100200300400');
  await p.submit();
  p.els['refresh'].fire('click');
  await settle();
  p.els['remove'].fire('click');
  await settle();
  for (const secret of ['100200300400', '1002-003-00400', CSRF.token, CSRF.headerName, 'accountNumber']) {
    assert.ok(!p.shown().includes(secret), '표시됨: ' + secret);
  }
  assert.deepEqual(p.forbidden, []);
  for (const call of p.calls) {
    const query = call.url.split('?')[1];
    assert.ok(query === undefined || /^version=[0-9]+$/.test(query), 'URL: ' + call.url);
  }
});

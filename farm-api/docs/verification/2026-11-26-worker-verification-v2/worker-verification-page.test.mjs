// farm-worker-verification.html의 인라인 스크립트를 원문 그대로 node:vm에서 실행한다 (node 내장 node:test, 설치 없음).
// DOM·fetch·window는 최소 가짜 객체다. 실제 브라우저 렌더링·폼 검증·네트워크·CSRF 서버 처리는 검증하지 않는다.
// 실행: node --test farm-api/docs/verification/2026-11-26-worker-verification-v2/worker-verification-page.test.mjs
// (HTML 경로는 WV2_HTML 환경 변수로 바꿀 수 있다. 기본값은 이 파일 기준 farm-api/src/main/resources/demo/)
import { test } from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import path from 'node:path';
import vm from 'node:vm';

const here = path.dirname(fileURLToPath(import.meta.url));
const htmlPath = process.env.WV2_HTML ?? path.resolve(here, '../../../src/main/resources/demo/farm-worker-verification.html');
const html = readFileSync(htmlPath, 'utf8');
const script = html.match(/<script>([\s\S]*?)<\/script>/)[1];

const IDENTITY = '900101-5000000';
const CSRF_TOKEN = 'test-csrf-token-value';
const CSRF_HEADER = 'X-TEST-CSRF';

function element(id, extra = {}) {
  const listeners = {};
  return {
    id, value: '', textContent: '', disabled: false, dataset: {}, listeners,
    addEventListener(type, fn) { (listeners[type] ??= []).push(fn); },
    ...extra
  };
}

const WORKER_ME = { status: 200, body: { id: 101, name: 'TEST WORKER', role: 'WORKER' } };
function page({ me = WORKER_ME } = {}) {
  const els = {
    '#verification-form': element('verification-form', { reportValidity: () => true }),
    '#fields': element('fields', { disabled: true }),
    '#account': element('account'),
    '#status': element('status'),
    '#result': element('result', { textContent: '결과가 여기에 표시됩니다.' }),
    '#refresh-session': element('refresh-session'),
    '#identity': element('identity'),
    '#name': element('name'),
    '#phone': element('phone'),
    '#nationality': element('nationality', { value: 'DOMESTIC' }),
    '#mismatch': element('mismatch'),
    '#invalid': element('invalid')
  };
  const calls = [];
  const forbidden = [];
  const queue = { me, csrf: { status: 200, body: { headerName: CSRF_HEADER, token: CSRF_TOKEN } }, post: { status: 200, body: {} } };
  const windowListeners = {};
  const response = spec => {
    if (spec instanceof Error) return Promise.reject(spec);
    return Promise.resolve({
      ok: spec.status >= 200 && spec.status < 300,
      status: spec.status,
      json: async () => { if (spec.body === undefined) throw new SyntaxError('not json'); return spec.body; }
    });
  };
  const fetch = (url, options = {}) => {
    calls.push({ url, options });
    if (url === '/api/auth/me') return response(queue.me);
    if (url === '/api/auth/csrf') return response(queue.csrf);
    if (url === '/demo/api/insurance/v2/worker-verifications') return response(typeof queue.post === 'function' ? queue.post() : queue.post);
    throw new Error('unexpected url ' + url);
  };
  const trap = name => new Proxy({}, { get(_, prop) { forbidden.push(name + '.' + String(prop)); return () => {}; } });
  const context = {
    document: { querySelector: selector => { if (!els[selector]) throw new Error('missing ' + selector); return els[selector]; } },
    fetch,
    JSON, Number, String, Error, SyntaxError, Promise,
    console: trap('console'),
    localStorage: trap('localStorage'),
    sessionStorage: trap('sessionStorage'),
    history: trap('history'),
    location: trap('location')
  };
  context.window = {
    addEventListener(type, fn) { (windowListeners[type] ??= []).push(fn); }
  };
  vm.createContext(context);
  vm.runInContext(script, context);
  const state = () => vm.runInContext('({ worker, busy })', context);
  return { els, calls, forbidden, queue, context, windowListeners, state };
}

async function signedIn(options) {
  const p = page(options);
  await p.context.session();
  p.els['#name'].value = 'TEST WORKER';
  p.els['#phone'].value = '010-1234-5678';
  p.els['#nationality'].value = 'FOREIGN';
  p.els['#identity'].value = IDENTITY;
  p.calls.length = 0;
  return p;
}

const posted = p => p.calls.filter(c => c.url === '/demo/api/insurance/v2/worker-verifications');
const sentBody = p => JSON.parse(posted(p).at(-1).options.body);
const shown = p => p.els['#result'].textContent + '\n' + p.els['#status'].textContent + '\n' + p.els['#account'].textContent;

function assertNothingSensitiveShown(p) {
  const text = shown(p);
  for (const secret of [IDENTITY, '9001015000000', '000000000000', CSRF_TOKEN, CSRF_HEADER, 'Bearer', 'identityNumber']) {
    assert.ok(!text.includes(secret), '화면에 표시됨: ' + secret);
  }
}

test('로그인 확인: 근로자면 입력 활성화·이름 채움, 고용주·401·오류면 비활성', async () => {
  const worker = page();
  await worker.context.session();
  assert.equal(worker.els['#fields'].disabled, false);
  assert.equal(worker.els['#name'].value, 'TEST WORKER');
  assert.equal(worker.state().worker, true);
  assert.deepEqual(worker.calls.map(c => [c.url, c.options.credentials, c.options.cache]), [['/api/auth/me', 'same-origin', 'no-store']]);

  const employer = page({ me: { status: 200, body: { id: 3, name: 'EMPLOYER', role: 'EMPLOYER' } } });
  await employer.context.session();
  assert.equal(employer.els['#fields'].disabled, true);
  assert.match(employer.els['#account'].textContent, /고용주/);

  const anonymous = page({ me: { status: 401 } });
  await anonymous.context.session();
  assert.equal(anonymous.els['#fields'].disabled, true);
  assert.match(anonymous.els['#account'].textContent, /로그인이 필요합니다/);

  const broken = page({ me: new Error('network') });
  await broken.context.session();
  assert.equal(broken.els['#fields'].disabled, true);
  assert.equal(broken.els['#refresh-session'].disabled, false);
  assert.equal(broken.state().busy, false);
});

test('정상 확인: CSRF 조회 후 같은 헤더로 POST, 입력값을 그대로 전송', async () => {
  const p = await signedIn();
  p.queue.post = { status: 200, body: { workerId: 101, name: 'TEST WORKER', phone: '01012345678', nationalityType: 'FOREIGN' } };
  await p.context.verify('normal');

  assert.deepEqual(p.calls.map(c => c.url), ['/api/auth/csrf', '/demo/api/insurance/v2/worker-verifications']);
  const post = posted(p)[0].options;
  assert.equal(post.method, 'POST');
  assert.equal(post.credentials, 'same-origin');
  assert.equal(post.cache, 'no-store');
  assert.equal(post.headers['Content-Type'], 'application/json');
  assert.equal(post.headers[CSRF_HEADER], CSRF_TOKEN);
  assert.ok(!('Authorization' in post.headers));
  assert.deepEqual(sentBody(p), { name: 'TEST WORKER', phone: '010-1234-5678', nationalityType: 'FOREIGN', identityNumber: IDENTITY });
  assert.match(p.els['#status'].textContent, /^HTTP 200 · 근로자 확인 성공$/);
  assert.equal(p.els['#status'].dataset.error, 'false');
});

test('불일치 예시: 전송 전화번호의 마지막 숫자만 +1(9→0), 폼 값은 유지', async () => {
  const p = await signedIn();
  await p.context.verify('mismatch');
  assert.equal(sentBody(p).phone, '01012345679');
  assert.equal(sentBody(p).identityNumber, IDENTITY);
  assert.equal(sentBody(p).name, 'TEST WORKER');
  assert.equal(p.els['#phone'].value, '010-1234-5678');

  p.els['#phone'].value = '010 1234 5679';
  await p.context.verify('mismatch');
  assert.equal(sentBody(p).phone, '01012345670');
  assert.equal(p.els['#phone'].value, '010 1234 5679');
});

test('불일치 예시 + 잘못된 전화 형식: 요청하지 않고 오류 안내, 입력 다시 활성화', async () => {
  const p = await signedIn();
  p.els['#phone'].value = '010-12';
  await p.context.verify('mismatch');
  assert.equal(p.calls.length, 0);
  assert.equal(p.els['#status'].dataset.error, 'true');
  assert.equal(p.els['#fields'].disabled, false);
  assert.equal(p.els['#refresh-session'].disabled, false);
  assert.equal(p.state().busy, false);
});

test('입력 오류 예시: 식별번호 12자리 전송, 폼의 원래 번호 유지', async () => {
  const p = await signedIn();
  p.queue.post = { status: 400, body: { code: 'INVALID_REQUEST', message: '필수 입력값과 입력 형식을 확인해 주세요.' } };
  await p.context.verify('invalid');
  assert.equal(sentBody(p).identityNumber, '000000000000');
  assert.equal(sentBody(p).identityNumber.length, 12);
  assert.equal(sentBody(p).phone, '010-1234-5678');
  assert.equal(p.els['#identity'].value, IDENTITY);
  assert.deepEqual(JSON.parse(p.els['#result'].textContent), { code: 'INVALID_REQUEST', message: '필수 입력값과 입력 형식을 확인해 주세요.' });
  assert.match(p.els['#status'].textContent, /^HTTP 400 · 필수 입력값/);
  assert.equal(p.els['#fields'].disabled, false);
});

test('성공 표시는 허용된 4필드만 (서버가 여분 필드를 보내도 숨김)', async () => {
  const p = await signedIn();
  p.queue.post = { status: 200, body: { workerId: 101, name: 'TEST WORKER', phone: '01012345678', nationalityType: 'FOREIGN',
    identityNumber: '9001015000000', identityHmac: 'abc', identityType: 'FOREIGN_REGISTRATION_NUMBER' } };
  await p.context.verify('normal');
  assert.deepEqual(Object.keys(JSON.parse(p.els['#result'].textContent)), ['workerId', 'name', 'phone', 'nationalityType']);
  assertNothingSensitiveShown(p);
});

test('오류 표시: 404·409·503은 code·message만, JSON이 아니면 HTTP_상태와 고정 안내', async () => {
  for (const [status, code] of [[404, 'WORKER_IDENTITY_NOT_MATCHED'], [409, 'WORKER_IDENTITY_AMBIGUOUS'], [503, 'SERVICE_UNAVAILABLE']]) {
    const p = await signedIn();
    p.queue.post = { status, body: { code, message: 'M' + status, detail: IDENTITY } };
    await p.context.verify('normal');
    assert.deepEqual(JSON.parse(p.els['#result'].textContent), { code, message: 'M' + status });
    assert.equal(p.els['#status'].textContent, `HTTP ${status} · M${status}`);
    assertNothingSensitiveShown(p);
  }
  const html = await signedIn();
  html.queue.post = { status: 500 };
  await html.context.verify('normal');
  assert.equal(JSON.parse(html.els['#result'].textContent).code, 'HTTP_500');
  assertNothingSensitiveShown(html);
});

test('401·403 응답 후 근로자 상태 해제·입력 비활성, 기타 오류 후에는 입력 복구', async () => {
  for (const status of [401, 403]) {
    const p = await signedIn();
    p.queue.post = { status, body: { code: 'DEMO_ACCESS_DENIED', message: '근로자 본인 계정으로 로그인해 주세요.' } };
    await p.context.verify('normal');
    assert.equal(p.state().worker, false);
    assert.equal(p.els['#fields'].disabled, true);
    assert.equal(p.els['#refresh-session'].disabled, false);
    await p.context.verify('normal');
    assert.equal(posted(p).length, 1, '근로자 해제 후에는 재요청하지 않음');
  }
  const failed = await signedIn();
  failed.queue.post = new Error('network');
  await failed.context.verify('normal');
  assert.equal(failed.els['#fields'].disabled, false);
  assert.equal(failed.state().busy, false);
  assert.equal(failed.els['#status'].dataset.error, 'true');
});

test('CSRF 조회 실패·형식 오류면 POST하지 않는다', async () => {
  for (const csrf of [{ status: 500 }, { status: 200, body: { token: CSRF_TOKEN } }, { status: 200, body: { headerName: CSRF_HEADER, token: 1 } }, new Error('x')]) {
    const p = await signedIn();
    p.queue.csrf = csrf;
    await p.context.verify('normal');
    assert.equal(posted(p).length, 0);
    assert.equal(p.els['#fields'].disabled, false);
    assertNothingSensitiveShown(p);
  }
});

test('중복 전송 방지: 응답 전 두 번째 요청은 무시, 요청 중 입력·새로고침 비활성', async () => {
  const p = await signedIn();
  let release;
  p.queue.post = () => new Promise(resolve => { release = resolve; }).then(() => ({ status: 200, body: { workerId: 101 } }));
  const fetchPost = p.context.fetch;
  p.context.fetch = (url, options) => {
    if (url !== '/demo/api/insurance/v2/worker-verifications') return fetchPost(url, options);
    p.calls.push({ url, options });
    return p.queue.post().then(spec => ({ ok: true, status: spec.status, json: async () => spec.body }));
  };
  const first = p.context.verify('normal');
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.els['#fields'].disabled, true);
  assert.equal(p.els['#refresh-session'].disabled, true);
  await p.context.verify('mismatch');
  await p.context.verify('invalid');
  await p.context.session();
  release();
  await first;
  assert.equal(posted(p).length, 1);
  assert.equal(p.calls.filter(c => c.url === '/api/auth/me').length, 0);
  assert.equal(p.els['#fields'].disabled, false);
});

test('결과·상태 표시와 저장소·로그·URL에 식별번호·요청 본문·CSRF가 남지 않는다', async () => {
  const p = await signedIn();
  for (const post of [{ status: 200, body: { workerId: 101, name: 'TEST WORKER', phone: '01012345678', nationalityType: 'FOREIGN' } },
    { status: 404, body: { code: 'WORKER_IDENTITY_NOT_MATCHED', message: 'x' } }, { status: 500 }]) {
    p.queue.post = post;
    for (const mode of ['normal', 'mismatch', 'invalid']) {
      await p.context.verify(mode);
      assertNothingSensitiveShown(p);
      assert.ok(!p.els['#result'].textContent.includes('"phone": "010-1234-5678"'));
    }
  }
  assert.deepEqual(p.forbidden, []);
  for (const call of p.calls) assert.ok(!call.url.includes('?'), 'URL 쿼리 없음: ' + call.url);
});

test('페이지 이탈(pagehide) 시 식별번호·이전 결과 정리, 복귀(pageshow) 시 로그인 재확인', async () => {
  const p = await signedIn();
  p.queue.post = { status: 200, body: { workerId: 101, name: 'TEST WORKER', phone: '01012345678', nationalityType: 'FOREIGN' } };
  await p.context.verify('normal');
  assert.match(p.els['#result'].textContent, /workerId/);
  p.windowListeners.pagehide.forEach(fn => fn());
  assert.equal(p.els['#identity'].value, '');
  assert.equal(p.els['#result'].textContent, '결과가 여기에 표시됩니다.');

  p.els['#identity'].value = IDENTITY;
  p.calls.length = 0;
  p.windowListeners.pageshow.forEach(fn => fn());
  await new Promise(resolve => setImmediate(resolve));
  assert.equal(p.els['#identity'].value, '');
  assert.deepEqual(p.calls.map(c => c.url), ['/api/auth/me']);
});

test('버튼·폼 이벤트 연결: submit은 기본 동작을 막고 normal, 두 예시 버튼은 각 모드로 전송', async () => {
  const p = await signedIn();
  let prevented = false;
  p.els['#verification-form'].listeners.submit[0]({ preventDefault() { prevented = true; } });
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.ok(prevented);
  assert.equal(sentBody(p).phone, '010-1234-5678');
  p.els['#mismatch'].listeners.click[0]();
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(sentBody(p).phone, '01012345679');
  p.els['#invalid'].listeners.click[0]();
  await new Promise(resolve => setTimeout(resolve, 0));
  assert.equal(sentBody(p).identityNumber, '000000000000');
  assert.equal(p.els['#refresh-session'].listeners.click[0], p.context.session);
});

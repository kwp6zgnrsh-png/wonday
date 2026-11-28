import assert from 'node:assert/strict'
import { once } from 'node:events'
import { createServer as createHttpServer } from 'node:http'
import { dirname, resolve } from 'node:path'
import { fileURLToPath, pathToFileURL } from 'node:url'

const repo = resolve(dirname(fileURLToPath(import.meta.url)), '..')
const { createServer } = await import(
  pathToFileURL(resolve(repo, 'user-web/node_modules/vite/dist/node/index.js')).href
)

// Exercise the real Vite configs with an isolated upstream; no account or DB writes.
const reward = { id: 7, result: 'ELIGIBLE', reasonCode: 'WOORI_SALARY_ACCOUNT', bankCode: '020' }
const link = { linked: true, consentRequired: false, requiredConsentVersion: 'farm-platform-v1',
  consentVersion: 'farm-platform-v1', consentedAt: '2026-11-27T09:00:00.123456Z' }
const requestKey = 'be7d745b-7632-4272-afc7-dba7f829971e'
const upstream = createHttpServer((request, response) => {
  if (request.method === 'POST' && request.url === '/api/contracts') {
    request.resume()
    response.writeHead(201, {
      'Content-Type': 'application/json',
      Location: '/api/contracts/42',
    })
    response.end(JSON.stringify({ id: 42, status: 'ACTIVE' }))
  } else if (request.method === 'GET' && request.url === '/api/contracts/42') {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ id: 42, status: 'ACTIVE' }))
  } else if (request.method === 'GET' && request.url === '/api/claims') {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify([{ id: 99, status: 'REVIEWING' }]))
  } else if (request.method === 'GET' && request.url === '/api/claims/99') {
    response.writeHead(200, { 'Content-Type': 'application/json' })
    response.end(JSON.stringify({ claim: { id: 99, status: 'REVIEWING' }, work: { assignmentId: 7001 } }))
  } else if (request.method === 'GET' && request.url === '/api/admin/claims?status=REVIEWING&limit=2') {
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ items: [{ id: 101, status: 'REVIEWING' }], hasMore: false, nextBeforeId: null }))
  } else if (request.method === 'GET' && request.url === '/api/admin/claims/101') {
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ claim: { id: 101, status: 'REVIEWING' }, work: { assignmentId: 7001 } }))
  } else if (request.method === 'GET' && request.url === '/api/admin/claims/101/history?limit=2') {
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ items: [{ id: 201, action: 'AUTO_RECEIVED' }], hasMore: false, nextBeforeId: null }))
  } else if (request.method === 'GET' && request.url === '/api/admin/claims/101/weather-station') {
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ selected: true, selection: { claimCaseId: 101, stationCode: '477802A001' } }))
  } else if (request.method === 'POST' && request.url === '/api/admin/claims/101/weather-station') {
    request.resume()
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    assert.equal(request.headers['x-csrf-token'], 'proxy-csrf')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ claimCaseId: 101, stationCode: '477802A001' }))
  } else if (request.method === 'POST' && request.url === '/api/platform/link') {
    request.resume()
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    assert.equal(request.headers['x-csrf-token'], 'proxy-csrf')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify(link))
  } else if (request.method === 'GET' && request.url === '/api/platform/link') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify(link))
  } else if (request.method === 'GET' && request.url === '/api/platform/salary-account') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ registered: true, bankCode: '020' }))
  } else if (request.method === 'POST' && request.url === '/api/rewards/salary-account/checks') {
    request.resume()
    assert.equal(request.headers['idempotency-key'], requestKey)
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify(reward))
  } else if (request.method === 'GET' && request.url === '/api/rewards/salary-account/latest') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify(reward))
  } else if (request.method === 'GET' && request.url === '/api/rewards/salary-account/checks?limit=2&beforeId=8') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ items: [reward], hasMore: false, nextBeforeId: null }))
  } else if (request.method === 'GET' && request.url === '/api/weather/stations') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify([{ stationCode: '477802A001', coordinatesAvailable: false }]))
  } else if (request.method === 'POST' && request.url === '/api/admin/weather/collections') {
    request.resume()
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    assert.equal(request.headers['x-csrf-token'], 'proxy-csrf')
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ id: 11, stationCode: '477802A001', state: 'RECEIVED' }))
  } else if (request.method === 'GET' && request.url === '/api/admin/weather/collection-jobs?state=FAILED') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ items: [{ id: 21, state: 'FAILED' }], hasMore: false, nextBeforeId: null }))
  } else if (request.method === 'GET' && request.url === '/api/admin/weather/collection-jobs/21/attempts') {
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ items: [{ id: 33, state: 'FAILED' }], hasMore: false, nextBeforeId: null }))
  } else if (request.method === 'POST' && request.url === '/api/admin/weather/collection-jobs/21/retry') {
    request.resume()
    assert.equal(request.headers.cookie, 'INSURANCESESSION=proxy-test')
    assert.equal(request.headers['x-csrf-token'], 'proxy-csrf')
    assert.equal(request.headers['idempotency-key'], requestKey)
    response.writeHead(200, { 'Content-Type': 'application/json', 'Cache-Control': 'no-store' })
    response.end(JSON.stringify({ id: 11, stationCode: '477802A001', state: 'RECEIVED' }))
  } else {
    response.writeHead(404)
    response.end()
  }
})
upstream.listen(0, '127.0.0.1')
await once(upstream, 'listening')

try {
  const target = 'http://127.0.0.1:' + upstream.address().port
  for (const web of ['user-web', 'admin-web']) {
    const root = resolve(repo, web)
    const vite = await createServer({
      root,
      configFile: resolve(root, 'vite.config.ts'),
      logLevel: 'error',
      server: {
        host: '127.0.0.1',
        port: 0,
        strictPort: true,
        proxy: {
          '/api/insurance/contracts': { target },
          '/api/insurance/claims': { target },
          '/api/insurance/admin/claims': { target },
          '/api/insurance/platform/salary-account': { target },
          '/api/insurance/platform/link': { target },
          '/api/insurance/rewards/': { target },
          '/api/insurance/weather/': { target },
          '/api/insurance/admin/weather/': { target },
        },
      },
    })
    try {
      await vite.listen()
      const origin = 'http://127.0.0.1:' + vite.httpServer.address().port
      const created = await fetch(origin + '/api/insurance/contracts', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: '{}',
      })
      assert.equal(created.status, 201)
      const contract = await created.json()
      const location = created.headers.get('location')
      assert.equal(location, '/api/insurance/contracts/42')
      const detail = await fetch(new URL(location, origin))
      assert.equal(detail.status, 200)
      assert.match(detail.headers.get('content-type'), /application\/json/)
      assert.deepEqual(await detail.json(), contract)
      console.log('PASS ' + web + ': POST -> rewritten Location -> GET contract JSON')
      const claims = await fetch(origin + '/api/insurance/claims')
      assert.equal(claims.status, 200)
      assert.deepEqual(await claims.json(), [{ id: 99, status: 'REVIEWING' }])
      const claimDetail = await fetch(origin + '/api/insurance/claims/99')
      assert.equal(claimDetail.status, 200)
      assert.deepEqual(await claimDetail.json(), { claim: { id: 99, status: 'REVIEWING' }, work: { assignmentId: 7001 } })
      console.log('PASS ' + web + ': GET claim list and detail JSON')
      const adminHeaders = { Cookie: 'INSURANCESESSION=proxy-test' }
      const adminClaims = await fetch(origin + '/api/insurance/admin/claims?status=REVIEWING&limit=2', { headers: adminHeaders })
      assert.equal(adminClaims.status, 200)
      assert.equal(adminClaims.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await adminClaims.json(), { items: [{ id: 101, status: 'REVIEWING' }], hasMore: false, nextBeforeId: null })
      const adminDetail = await fetch(origin + '/api/insurance/admin/claims/101', { headers: adminHeaders })
      assert.equal(adminDetail.status, 200)
      assert.deepEqual(await adminDetail.json(), { claim: { id: 101, status: 'REVIEWING' }, work: { assignmentId: 7001 } })
      const adminHistory = await fetch(origin + '/api/insurance/admin/claims/101/history?limit=2', { headers: adminHeaders })
      assert.equal(adminHistory.status, 200)
      assert.equal(adminHistory.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await adminHistory.json(), { items: [{ id: 201, action: 'AUTO_RECEIVED' }], hasMore: false, nextBeforeId: null })
      console.log('PASS ' + web + ': admin claim list/detail/history routing, query and session forwarding')
      const selectedStation = await fetch(origin + '/api/insurance/admin/claims/101/weather-station', { headers: adminHeaders })
      assert.equal(selectedStation.status, 200)
      assert.deepEqual(await selectedStation.json(), { selected: true, selection: { claimCaseId: 101, stationCode: '477802A001' } })
      const selectStation = await fetch(origin + '/api/insurance/admin/claims/101/weather-station', {
        method: 'POST', headers: { ...adminHeaders, 'X-CSRF-TOKEN': 'proxy-csrf' },
      })
      assert.equal(selectStation.status, 200)
      assert.equal(selectStation.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await selectStation.json(), { claimCaseId: 101, stationCode: '477802A001' })
      console.log('PASS ' + web + ': claim station GET/POST routing, session and CSRF forwarding')

      const salary = await fetch(origin + '/api/insurance/platform/salary-account')
      assert.equal(salary.status, 200)
      assert.equal(salary.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await salary.json(), { registered: true, bankCode: '020' })
      console.log('PASS ' + web + ': GET own salary account registration JSON')
      const linked = await fetch(origin + '/api/insurance/platform/link', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: 'INSURANCESESSION=proxy-test', 'X-CSRF-TOKEN': 'proxy-csrf' },
        body: '{}',
      })
      assert.equal(linked.status, 200)
      assert.equal(linked.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await linked.json(), link)
      const linkStatus = await fetch(origin + '/api/insurance/platform/link')
      assert.equal(linkStatus.status, 200)
      assert.equal(linkStatus.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await linkStatus.json(), link)
      console.log('PASS ' + web + ': POST farm link, forwarded session/CSRF, GET connection status JSON')
      const checked = await fetch(origin + '/api/insurance/rewards/salary-account/checks', {
        method: 'POST',
        headers: { 'Idempotency-Key': requestKey, Cookie: 'INSURANCESESSION=proxy-test' },
      })
      assert.equal(checked.status, 200)
      assert.equal(checked.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await checked.json(), reward)
      const latest = await fetch(origin + '/api/insurance/rewards/salary-account/latest')
      assert.equal(latest.status, 200)
      assert.equal(latest.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await latest.json(), reward)
      const history = await fetch(origin + '/api/insurance/rewards/salary-account/checks?limit=2&beforeId=8')
      assert.equal(history.status, 200)
      assert.equal(history.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await history.json(), { items: [reward], hasMore: false, nextBeforeId: null })
      console.log('PASS ' + web + ': POST reward check, forwarded session/key, GET latest and paginated history JSON')
      const stations = await fetch(origin + '/api/insurance/weather/stations')
      assert.equal(stations.status, 200)
      assert.equal(stations.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await stations.json(), [{ stationCode: '477802A001', coordinatesAvailable: false }])
      const weather = await fetch(origin + '/api/insurance/admin/weather/collections', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json', Cookie: 'INSURANCESESSION=proxy-test', 'X-CSRF-TOKEN': 'proxy-csrf' },
        body: JSON.stringify({ stationCode: '477802A001', date: '2026-11-25' }),
      })
      assert.equal(weather.status, 200)
      assert.equal(weather.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await weather.json(), { id: 11, stationCode: '477802A001', state: 'RECEIVED' })
      console.log('PASS ' + web + ': weather station lookup and admin collection routing, session and CSRF forwarding')
      const failedJobs = await fetch(origin + '/api/insurance/admin/weather/collection-jobs?state=FAILED')
      assert.equal(failedJobs.status, 200)
      assert.equal(failedJobs.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await failedJobs.json(), { items: [{ id: 21, state: 'FAILED' }], hasMore: false, nextBeforeId: null })
      const attempts = await fetch(origin + '/api/insurance/admin/weather/collection-jobs/21/attempts')
      assert.equal(attempts.status, 200)
      assert.deepEqual(await attempts.json(), { items: [{ id: 33, state: 'FAILED' }], hasMore: false, nextBeforeId: null })
      const retried = await fetch(origin + '/api/insurance/admin/weather/collection-jobs/21/retry', {
        method: 'POST',
        headers: { Cookie: 'INSURANCESESSION=proxy-test', 'X-CSRF-TOKEN': 'proxy-csrf', 'Idempotency-Key': requestKey },
      })
      assert.equal(retried.status, 200)
      assert.equal(retried.headers.get('cache-control'), 'no-store')
      assert.deepEqual(await retried.json(), { id: 11, stationCode: '477802A001', state: 'RECEIVED' })
      console.log('PASS ' + web + ': failed weather jobs, attempt history and retry routing, session/CSRF/request-key forwarding')
    } finally {
      await vite.close()
    }
  }
} finally {
  await new Promise((resolve, reject) =>
    upstream.close((error) => error ? reject(error) : resolve()),
  )
}

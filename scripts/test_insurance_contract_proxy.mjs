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
          '/api/insurance/platform/salary-account': { target },
          '/api/insurance/platform/link': { target },
          '/api/insurance/rewards/': { target },
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
    } finally {
      await vite.close()
    }
  }
} finally {
  await new Promise((resolve, reject) =>
    upstream.close((error) => error ? reject(error) : resolve()),
  )
}

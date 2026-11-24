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
        proxy: { '/api/insurance/contracts': { target }, '/api/insurance/claims': { target } },
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
    } finally {
      await vite.close()
    }
  }
} finally {
  await new Promise((resolve, reject) =>
    upstream.close((error) => error ? reject(error) : resolve()),
  )
}

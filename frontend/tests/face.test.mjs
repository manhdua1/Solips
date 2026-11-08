import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { ApiError, createClient } from '../src/api.ts'

const original = globalThis.fetch
const session = { accessToken: 'mock-only', expiresAt: Date.now() + 3600000, role: 'Admin' }
const status = { employeeId: 'e1', registered: false, sampleCount: 0, revision: 2, consentVersion: 'v1', consentText: 'Employee notice' }
const receipt = { consentToken: 'receipt-only', consentVersion: 'v1', consentedAt: new Date().toISOString(), expiresAt: new Date(Date.now() + 600000).toISOString() }
const images = () => Array.from({ length: 3 }, () => new Blob(['jpeg-placeholder'], { type: 'image/jpeg' }))
const response = (result) => Response.json({ code: 1000, result })
afterEach(() => { globalThis.fetch = original })

test('face routes use bearer/no-store, versioned consent, repeated multipart images without JSON header, and revision delete', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    assert.equal(options.headers.Authorization, 'Bearer mock-only')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.credentials, 'omit')
    return response(url.endsWith('/consent') ? receipt : status)
  }
  const client = createClient(session, () => assert.fail())
  assert.deepEqual(await client.faceStatus('e1'), status)
  assert.deepEqual(await client.faceConsent('e1', 'v1', 2), receipt)
  await client.enrollFace('e1', images(), receipt.consentToken, 2)
  await client.deleteFace('e1', 2)
  assert.deepEqual(calls.map(([url, options]) => [url, options.method]), [
    ['/api/employees/e1/face', 'GET'], ['/api/employees/e1/face/consent', 'POST'], ['/api/employees/e1/face', 'PUT'], ['/api/employees/e1/face', 'DELETE'],
  ])
  assert.deepEqual(JSON.parse(calls[1][1].body), { accepted: true, consentVersion: 'v1', expectedRevision: 2 })
  const upload = calls[2][1]
  assert.ok(upload.body instanceof FormData)
  assert.equal(upload.headers['Content-Type'], undefined)
  assert.deepEqual([...upload.body.keys()], ['images', 'images', 'images', 'consentToken', 'expectedRevision'])
  assert.equal(upload.body.getAll('images').length, 3)
  upload.body.getAll('images').forEach((file, index) => { assert.equal(file.type, 'image/jpeg'); assert.equal(file.name, `sample-${index + 1}.jpg`) })
  assert.equal(upload.body.get('consentToken'), receipt.consentToken)
  assert.equal(upload.body.get('expectedRevision'), '2')
  assert.deepEqual(JSON.parse(calls[3][1].body), { expectedRevision: 2 })
  assert.equal(calls[3][1].headers['Content-Type'], 'application/json')
})

for (const code of [1008, 1009, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1017, 1018, 1019, 1020]) {
  test(`face error ${code} retains code and sanitizes server details`, async () => {
    globalThis.fetch = async () => Response.json({ code, message: 'private trace/token' }, { status: code < 1010 ? 409 : 422 })
    await assert.rejects(createClient(session, () => {}).enrollFace('e1', images(), 'receipt', 2), (failure) => failure instanceof ApiError && failure.code === code && !failure.message.includes('private'))
  })
}

for (const malformed of [null, {}, { ...status, employeeId: 'other' }, { ...status, revision: -1 }, { ...status, revision: 1.5 }, { ...status, consentText: '' }, { ...status, registered: 'true' }, { ...status, sampleCount: 3 }, { ...status, registered: true, sampleCount: 1 }]) {
  test(`rejects malformed face status ${JSON.stringify(malformed)}`, async () => {
    globalThis.fetch = async () => response(malformed)
    await assert.rejects(createClient(session, () => {}).faceStatus('e1'), ApiError)
  })
}
for (const malformed of [{ ...receipt, consentToken: '' }, { ...receipt, consentVersion: 'old' }, { ...receipt, expiresAt: new Date(0).toISOString() }, { ...receipt, consentedAt: 'invalid' }]) {
  test(`rejects malformed or expired receipt ${JSON.stringify(malformed)}`, async () => {
    globalThis.fetch = async () => response(malformed)
    await assert.rejects(createClient(session, () => {}).faceConsent('e1', 'v1', 2), ApiError)
  })
}
test('employee summaries remain optional but reject malformed face fields', async () => {
  const employee = { id: 'e1', code: 'E1', fullName: 'Test', email: 'test@example.invalid', status: 'ACTIVE', departmentId: null }
  const client = createClient(session, () => {})
  for (const fields of [{}, { faceRegistered: true, faceSampleCount: 3 }]) {
    globalThis.fetch = async () => response({ ...employee, ...fields })
    assert.equal((await client.detail('employees', 'e1')).id, 'e1')
  }
  for (const fields of [{ faceRegistered: 'yes' }, { faceSampleCount: -1 }, { faceSampleCount: 1.5 }]) {
    globalThis.fetch = async () => response({ ...employee, ...fields })
    await assert.rejects(client.detail('employees', 'e1'), ApiError)
  }
})
test('face mutations use a 45-second timeout', async () => {
  const originalTimeout = AbortSignal.timeout
  const delays = []
  AbortSignal.timeout = (delay) => { delays.push(delay); return originalTimeout(delay) }
  try {
    globalThis.fetch = async () => response(status)
    const client = createClient(session, () => {})
    await client.enrollFace('e1', images(), 'receipt', 2)
    await client.deleteFace('e1', 2)
    assert.deepEqual(delays, [45000, 45000])
  } finally { AbortSignal.timeout = originalTimeout }
})
test('invalid uploads never reach network', async () => {
  globalThis.fetch = async () => assert.fail('must not upload invalid samples')
  const client = createClient(session, () => {})
  for (const blobs of [[], images().slice(0, 2), [...images(), ...images()], [new Blob([], { type: 'image/jpeg' }), ...images().slice(1)], [new Blob(['png'], { type: 'image/png' }), ...images().slice(1)], [new Blob([new Uint8Array(2 * 1024 * 1024 + 1)], { type: 'image/jpeg' }), ...images().slice(1)]]) {
    await assert.rejects(client.enrollFace('e1', blobs, 'receipt', 2), ApiError)
  }
  await assert.rejects(client.enrollFace('e1', images(), '', 2), ApiError)
  await assert.rejects(client.enrollFace('e1', images(), 'receipt', -1), ApiError)
})
test('face requests preserve expiry/401/403 mechanics and caller abort', async () => {
  let expired = 0
  const client = createClient(session, () => expired++)
  globalThis.fetch = async () => new Response('', { status: 401 })
  await assert.rejects(client.faceStatus('e1'), (failure) => failure.status === 401)
  assert.equal(expired, 1)
  globalThis.fetch = async () => new Response('', { status: 403 })
  await assert.rejects(client.deleteFace('e1', 2), (failure) => failure.status === 403)
  assert.equal(expired, 1)
  globalThis.fetch = async () => assert.fail('must not fetch')
  await assert.rejects(createClient({ ...session, expiresAt: 0 }, () => expired++).faceStatus('e1'), (failure) => failure.status === 401)
  const controller = new AbortController(); controller.abort()
  await assert.rejects(client.faceStatus('e1', controller.signal), { name: 'AbortError' })
})
test('face mutation timeout and network failures remain unknown and are never retried automatically', async () => {
  let calls = 0
  globalThis.fetch = async () => { calls++; throw new DOMException('private', 'TimeoutError') }
  await assert.rejects(createClient(session, () => {}).enrollFace('e1', images(), 'receipt', 2), (failure) => failure.status === 0 && !failure.message.includes('private'))
  assert.equal(calls, 1)
})

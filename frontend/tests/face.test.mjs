import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ApiError, createClient } from '../src/api.ts'

const original = globalThis.fetch
const session = { accessToken: 'mock-only', expiresAt: Date.now() + 3600000, role: 'Admin' }
const status = { employeeId: 'e1', registered: false, sampleCount: 0, revision: 2 }
const images = () => Array.from({ length: 3 }, () => new Blob(['jpeg-placeholder'], { type: 'image/jpeg' }))
const response = (result) => Response.json({ code: 1000, result })
afterEach(() => { globalThis.fetch = original })

test('face routes use bearer/no-store, repeated multipart images without JSON header, and revision delete', async () => {
  const calls = []
  globalThis.fetch = async (url, options) => {
    calls.push([url, options])
    assert.equal(options.headers.Authorization, 'Bearer mock-only')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.credentials, 'omit')
    return response(status)
  }
  const client = createClient(session, () => assert.fail())
  assert.deepEqual(await client.faceStatus('e1'), status)
  await client.enrollFace('e1', images(), 2)
  await client.deleteFace('e1', 2)
  assert.deepEqual(calls.map(([url, options]) => [url, options.method]), [
    ['/api/employees/e1/face', 'GET'], ['/api/employees/e1/face', 'PUT'], ['/api/employees/e1/face', 'DELETE'],
  ])
  const upload = calls[1][1]
  assert.ok(upload.body instanceof FormData)
  assert.equal(upload.headers['Content-Type'], undefined)
  assert.deepEqual([...upload.body.keys()], ['images', 'images', 'images', 'expectedRevision'])
  assert.equal(upload.body.getAll('images').length, 3)
  upload.body.getAll('images').forEach((file, index) => { assert.equal(file.type, 'image/jpeg'); assert.equal(file.name, `sample-${index + 1}.jpg`) })
  assert.equal(upload.body.has('consentToken'), false)
  assert.equal(upload.body.get('expectedRevision'), '2')
  assert.deepEqual(JSON.parse(calls[2][1].body), { expectedRevision: 2 })
  assert.equal(calls[2][1].headers['Content-Type'], 'application/json')
})

for (const code of [1008, 1010, 1011, 1012, 1013, 1014, 1015, 1016, 1018, 1019, 1020]) {
  test(`face error ${code} retains code and sanitizes server details`, async () => {
    globalThis.fetch = async () => Response.json({ code, message: 'private trace/token' }, { status: code < 1010 ? 409 : 422 })
    await assert.rejects(createClient(session, () => {}).enrollFace('e1', images(), 2), (failure) => failure instanceof ApiError && failure.code === code && !failure.message.includes('private'))
  })
}

for (const malformed of [null, {}, { ...status, employeeId: 'other' }, { ...status, revision: -1 }, { ...status, revision: 1.5 }, { ...status, enrolledAt: 'invalid' }, { ...status, enrolledAt: 42 }, { ...status, registered: 'true' }, { ...status, sampleCount: 3 }, { ...status, registered: true, sampleCount: 1 }]) {
  test(`rejects malformed face status ${JSON.stringify(malformed)}`, async () => {
    globalThis.fetch = async () => response(malformed)
    await assert.rejects(createClient(session, () => {}).faceStatus('e1'), ApiError)
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
    await client.enrollFace('e1', images(), 2)
    await client.deleteFace('e1', 2)
    assert.deepEqual(delays, [45000, 45000])
  } finally { AbortSignal.timeout = originalTimeout }
})
test('invalid uploads never reach network', async () => {
  globalThis.fetch = async () => assert.fail('must not upload invalid samples')
  const client = createClient(session, () => {})
  for (const blobs of [[], images().slice(0, 2), [...images(), ...images()], [new Blob([], { type: 'image/jpeg' }), ...images().slice(1)], [new Blob(['png'], { type: 'image/png' }), ...images().slice(1)], [new Blob([new Uint8Array(2 * 1024 * 1024 + 1)], { type: 'image/jpeg' }), ...images().slice(1)]]) {
    await assert.rejects(client.enrollFace('e1', blobs, 2), ApiError)
  }
  for (const revision of [undefined, -1, 1.5, NaN, Infinity, '2']) {
    await assert.rejects(client.enrollFace('e1', images(), revision), ApiError)
  }
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
  await assert.rejects(createClient(session, () => {}).enrollFace('e1', images(), 2), (failure) => failure.status === 0 && !failure.message.includes('private'))
  assert.equal(calls, 1)
})

// Exercise the existing component with browser/React boundaries mocked, without new dependencies.
const componentCode = ts.transpileModule(readFileSync(new URL('../src/FaceManager.tsx', import.meta.url), 'utf8'), {
  compilerOptions: { module: ts.ModuleKind.CommonJS, jsx: ts.JsxEmit.ReactJSX },
}).outputText
function faceDialog(initialStatus = status, failSave = false) {
  const slots = [], effects = [], calls = [], revoked = [], stopped = []
  let cursor = 0, tree, cleanup, closed = false, focused = false, objectId = 0
  const track = { stop: () => stopped.push(true) }
  const media = { active: true, getTracks: () => [track] }
  const video = { readyState: 2, videoWidth: 1280, videoHeight: 720, play: async () => {}, srcObject: null }
  const modal = { showModal() {}, close: () => { closed = true } }
  const jsx = (type, props) => {
    const node = { type, props }
    if (props?.ref) props.ref.current = type === 'video' ? video : modal
    return node
  }
  const react = {
    useRef(value) { const index = cursor++; return slots[index] ??= { current: value } },
    useState(value) { const index = cursor++; if (!(index in slots)) slots[index] = value; return [slots[index], (next) => { slots[index] = next }] },
    useEffect(effect) { const index = cursor++; if (!(index in slots)) { slots[index] = true; effects.push(effect) } },
  }
  let currentStatus = initialStatus
  const client = {
    async faceStatus(id, signal) { calls.push(['GET', id, signal]); return currentStatus },
    async enrollFace(id, blobs, revision, signal) {
      calls.push(['PUT', id, blobs, revision, signal])
      if (failSave) throw new ApiError('Unknown mutation', 0)
      return currentStatus = { ...status, registered: true, sampleCount: 3, revision: revision + 1 }
    },
    async deleteFace(id, revision, signal) { calls.push(['DELETE', id, revision, signal]); return currentStatus = { ...status, revision: revision + 1 } },
  }
  const exports = {}
  runInNewContext(componentCode, {
    exports,
    require: (name) => name === 'react' ? react : name === 'react/jsx-runtime' ? { jsx, jsxs: jsx } : name === './api.ts' ? { errorMessage: (error) => error.message } : {},
    AbortController,
    navigator: { mediaDevices: { getUserMedia: async () => { calls.push(['CAMERA']); return media } } },
    document: {
      activeElement: { isConnected: true, focus: () => { focused = true } },
      createElement: () => ({ getContext: () => ({ drawImage() {} }), toBlob: (resolve) => resolve(images()[0]) }),
    },
    URL: { createObjectURL: () => `blob:${++objectId}`, revokeObjectURL: (url) => revoked.push(url) },
  })
  function render() { cursor = 0; tree = exports.default({ id: 'e1', name: 'Test Employee', client, close() {}, changed() {} }); return tree }
  function nodes(node = tree) {
    if (Array.isArray(node)) return node.flatMap((child) => nodes(child))
    if (!node || typeof node !== 'object') return []
    return [node, ...nodes(node.props?.children ?? null)]
  }
  const text = (node) => Array.isArray(node) ? node.map(text).join('') : node && typeof node === 'object' ? text(node.props?.children) : String(node ?? '')
  const button = (label) => nodes().find((node) => node.type === 'button' && text(node) === label)
  const settle = async () => { for (let i = 0; i < 20; i++) await Promise.resolve(); render() }
  async function click(label) {
    const control = button(label)
    assert.ok(control, `Missing button: ${label}`)
    assert.equal(control.props.disabled, false, `Disabled button: ${label}`)
    await control.props.onClick(); await settle()
  }
  render()
  for (const effect of effects) cleanup = effect()
  return { calls, revoked, stopped, button, click, settle, nodes, unmount: () => cleanup(), get closed() { return closed }, get focused() { return focused } }
}

test('dialog opens camera directly after status, captures exactly three, and saves only explicitly', async () => {
  const ui = faceDialog()
  await ui.settle()
  assert.deepEqual(ui.calls.map(([method]) => method), ['GET'])
  assert.equal(ui.nodes().some((node) => node.type === 'input'), false)
  await ui.click('M\u1edf camera')
  assert.deepEqual(ui.calls.map(([method]) => method), ['GET', 'CAMERA'])
  for (let index = 1; index <= 3; index++) {
    assert.equal(ui.button('L\u01b0u 3 m\u1eabu khu\u00f4n m\u1eb7t').props.disabled, true)
    await ui.click(`Ch\u1ee5p \u1ea3nh ${index}`)
  }
  assert.equal(ui.button('Ch\u1ee5p \u1ea3nh 3').props.disabled, true)
  assert.equal(ui.calls.some(([method]) => method === 'PUT'), false)
  await ui.click('L\u01b0u 3 m\u1eabu khu\u00f4n m\u1eb7t')
  const upload = ui.calls.find(([method]) => method === 'PUT')
  assert.equal(upload[2].length, 3)
  assert.equal(upload[3], 2)
  assert.equal(upload[4].aborted, false)
  assert.equal(ui.revoked.length, 3)
  assert.equal(ui.stopped.length, 1)
  ui.unmount()
  assert.equal(upload[4].aborted, true)
  assert.equal(ui.closed, true)
  assert.equal(ui.focused, true)
})

test('dialog refreshes an unknown save without retrying and permits revision-based reenrollment/delete', async () => {
  const ui = faceDialog({ ...status, registered: true, sampleCount: 3, revision: 7 }, true)
  await ui.settle()
  await ui.click('M\u1edf camera')
  for (let index = 1; index <= 3; index++) await ui.click(`Ch\u1ee5p \u1ea3nh ${index}`)
  await ui.click('L\u01b0u \u0111\u0103ng k\u00fd l\u1ea1i')
  assert.deepEqual(ui.calls.map(([method]) => method), ['GET', 'CAMERA', 'PUT', 'GET'])
  assert.equal(ui.calls[2][3], 7)
  assert.equal(ui.revoked.length, 3)
  assert.equal(ui.button('L\u01b0u \u0111\u0103ng k\u00fd l\u1ea1i').props.disabled, true)
  await ui.click('X\u00f3a d\u1eef li\u1ec7u khu\u00f4n m\u1eb7t\u2026')
  assert.equal(ui.calls.some(([method]) => method === 'DELETE'), false)
  await ui.click('X\u00e1c nh\u1eadn x\u00f3a to\u00e0n b\u1ed9')
  assert.equal(ui.calls.at(-1)[0], 'DELETE')
  assert.equal(ui.calls.at(-1)[2], 7)
  ui.unmount()
})

test('closing the dialog aborts requests, stops camera tracks and revokes unsaved previews', async () => {
  const ui = faceDialog()
  await ui.settle()
  await ui.click('M\u1edf camera')
  await ui.click('Ch\u1ee5p \u1ea3nh 1')
  ui.unmount()
  assert.equal(ui.calls[0][2].aborted, true)
  assert.equal(ui.stopped.length, 1)
  assert.equal(ui.revoked.length, 1)
  assert.equal(ui.calls.some(([method]) => method === 'PUT'), false)
})

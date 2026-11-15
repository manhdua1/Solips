import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { ApiError, createClient, parseFaceQuality } from '../src/api.ts'


const quality = { imageWidth: 1280, imageHeight: 720, faceBox: { x: .3, y: .2, width: .3, height: .5 }, quality: { detScore: .99, faceWidth: 384, faceHeight: 360, faceAreaRatio: .15, laplacianVar: 150, meanBrightness: 120 } }
const deferred = () => { let resolve, reject; const promise = new Promise((yes, no) => { resolve = yes; reject = no }); return { promise, resolve, reject } }
function clock() {
  let time = 0, serial = 0
  const timers = new Map()
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
  return { now: () => time, setTimeout: (fn, delay) => { const id = ++serial; timers.set(id, { fn, at: time + delay }); return id }, clearTimeout: (id) => timers.delete(id),
    async advance(ms) { const end = time + ms; await flush(); while (true) { const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0]; if (!next || next[1].at > end) break; time = next[1].at; timers.delete(next[0]); next[1].fn(); await flush() } time = end; await flush() }, flush }
}
const loopCode = ts.transpileModule(readFileSync(new URL('../src/face-quality-loop.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
function loopWithClock(timer) {
  const exports = {}
  runInNewContext(loopCode, { exports, require: () => ({ ApiError, parseFaceQuality }), AbortController, Date: { now: timer.now }, setTimeout: timer.setTimeout, clearTimeout: timer.clearTimeout })
  return exports.startQualityLoop
}

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
function faceDialog(initialStatus = status, failSave = false, boundaries = {}) {
  const slots = [], effects = [], calls = [], revoked = [], stopped = []
  const timer = clock()
  const probed = []
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
    async faceQuality(id, blob, signal) { calls.push(['PROBE', id, blob, signal]); probed.push(blob); return quality },
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
    require: (name) => name === 'react' ? react : name === 'react/jsx-runtime' ? { jsx, jsxs: jsx } : name === './api.ts' ? { errorMessage: (error) => error.message } : name === './face-quality-loop.ts' ? { startQualityLoop: loopWithClock(timer) } : {},
    AbortController,
    navigator: { mediaDevices: { getUserMedia: async () => { calls.push(['CAMERA']); return boundaries.media ? boundaries.media.promise : media } } },
    document: {
      activeElement: { isConnected: true, focus: () => { focused = true } },
      createElement: () => ({ getContext: () => ({ drawImage() {} }), toBlob: (resolve) => { if (boundaries.encode) boundaries.encode.promise.then(resolve); else resolve(images()[0]) } }),
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
    assert.notEqual(control.props.disabled, true, `Disabled button: ${label}`)
    await control.props.onClick(); await settle()
  }
  render()
  for (const effect of effects) cleanup = effect()
  return { calls, revoked, stopped, button, click, settle, nodes, probed, async advance(ms) { await timer.advance(ms); await settle() }, unmount: () => cleanup(), get closed() { return closed }, get focused() { return focused } }
}

test('dialog opens camera directly after status, captures exactly three, and saves only explicitly', async () => {
  const ui = faceDialog()
  await ui.settle()
  assert.deepEqual(ui.calls.map(([method]) => method), ['GET'])
  assert.equal(ui.nodes().some((node) => node.type === 'input'), false)
  await ui.click('M\u1edf camera')
  assert.deepEqual(ui.calls.map(([method]) => method), ['GET', 'CAMERA'])
  assert.equal(ui.button('L\u01b0u 3 m\u1eabu khu\u00f4n m\u1eb7t').props.disabled, true)
  await ui.advance(6500)
  assert.equal(ui.probed.length, 9)
  await ui.advance(10000)
  assert.equal(ui.probed.length, 9)
  assert.equal(ui.calls.some(([method]) => method === 'PUT'), false)
  await ui.click('L\u01b0u 3 m\u1eabu khu\u00f4n m\u1eb7t')
  const upload = ui.calls.find(([method]) => method === 'PUT')
  assert.equal(upload[2].length, 3)
  assert.equal(upload[2][0], ui.probed[2]); assert.equal(upload[2][1], ui.probed[5]); assert.equal(upload[2][2], ui.probed[8])
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
  await ui.advance(6500)
  await ui.click('L\u01b0u \u0111\u0103ng k\u00fd l\u1ea1i')
  assert.deepEqual(ui.calls.filter(([method]) => method !== 'PROBE').map(([method]) => method), ['GET', 'CAMERA', 'PUT', 'GET'])
  assert.equal(ui.calls.find(([method]) => method === 'PUT')[3], 7)
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
  await ui.advance(1500)
  ui.unmount()
  assert.equal(ui.calls[0][2].aborted, true)
  assert.equal(ui.stopped.length, 1)
  assert.equal(ui.revoked.length, 1)
  assert.equal(ui.calls.some(([method]) => method === 'PUT'), false)
})


test('quality probe uses single multipart field, encoded route, 20s timeout and runtime validation', async () => {
  const timeout = AbortSignal.timeout, delays = [], calls = []
  AbortSignal.timeout = (delay) => { delays.push(delay); return timeout(delay) }
  try {
    globalThis.fetch = async (url, options) => { calls.push([url, options]); return response(quality) }
    const client = createClient(session, () => assert.fail())
    assert.deepEqual(await client.faceQuality('e/1', images()[0]), quality)
    assert.equal(calls[0][0], '/api/employees/e%2F1/face/quality')
    assert.equal(calls[0][1].method, 'POST')
    assert.equal(calls[0][1].headers.Authorization, 'Bearer mock-only')
    assert.equal(calls[0][1].headers['Content-Type'], undefined)
    assert.deepEqual([...calls[0][1].body.keys()], ['image'])
    assert.deepEqual(delays, [20000])
    for (const value of [null, {}, { ...quality, imageWidth: 0 }, { ...quality, imageHeight: 2.5 }, { ...quality, faceBox: { ...quality.faceBox, x: -.1 } }, { ...quality, faceBox: { ...quality.faceBox, width: 0 } }, { ...quality, faceBox: { ...quality.faceBox, y: .9 } }, { ...quality, quality: { ...quality.quality, detScore: '1' } }]) {
      globalThis.fetch = async () => response(value)
      await assert.rejects(client.faceQuality('e1', images()[0]), ApiError)
    }
    for (const value of [NaN, Infinity, -Infinity]) {
      assert.throws(() => parseFaceQuality({ ...quality, faceBox: { ...quality.faceBox, x: value } }), ApiError)
      assert.throws(() => parseFaceQuality({ ...quality, quality: { ...quality.quality, laplacianVar: value } }), ApiError)
    }
    globalThis.fetch = async () => assert.fail('invalid image must not be sent')
    await assert.rejects(client.faceQuality('e1', new Blob()), ApiError)
  } finally { AbortSignal.timeout = timeout }
})

function pipeline(overrides = {}) {
  const timer = clock(), probed = [], sampled = [], errors = [], guidance = []
  const options = {
    encode: async () => ({ blob: images()[0], width: 1280, height: 720 }),
    probe: async (blob) => { probed.push([timer.now(), blob]); return quality },
    sample: (blob) => { sampled.push(blob); return sampled.length === 3 },
    guidance: (message) => guidance.push(message), paused: (error) => errors.push(error), ...overrides,
  }
  const loop = loopWithClock(timer)(options)
  return { timer, loop, probed, sampled, errors, guidance }
}

test('serial cadence, final exact blob, stability reset and one second inter-sample gap', async () => {
  const p = pipeline()
  await p.timer.advance(6500)
  assert.deepEqual(p.probed.map(([time]) => time), [0, 750, 1500, 2500, 3250, 4000, 5000, 5750, 6500])
  assert.deepEqual(p.sampled, [p.probed[2][1], p.probed[5][1], p.probed[8][1]])
  await p.timer.advance(10000)
  assert.equal(p.probed.length, 9)
})

for (const change of ['centerX', 'centerY', 'width', 'height', 'dimensions']) {
  test(`unstable ${change} restarts the three pass window`, async () => {
    let index = 0
    const p = pipeline({ probe: async () => {
      index++
      if (index !== 2) return quality
      if (change === 'dimensions') return { ...quality, imageWidth: 640 }
      const faceBox = { ...quality.faceBox }
      if (change === 'centerX') faceBox.x += .031
      if (change === 'centerY') faceBox.y += .031
      if (change === 'width') faceBox.width *= 1.11
      if (change === 'height') faceBox.height *= 1.11
      return { ...quality, faceBox }
    } })
    await p.timer.advance(1500)
    assert.equal(p.sampled.length, 0)
    if (change === 'dimensions') assert.equal(p.errors.length, 1)
    else { await p.timer.advance(1500); assert.equal(p.sampled.length, 1) }
    p.loop.stop()
  })
}

for (const code of [1012, 1013, 1014, 1015, 1016]) {
  test(`quality ${code} gives guidance and resets stability without pausing`, async () => {
    let index = 0
    const p = pipeline({ probe: async () => { if (++index === 2) throw new ApiError('quality', 422, code); return quality } })
    await p.timer.advance(2250)
    assert.equal(p.sampled.length, 0)
    assert.equal(p.errors.length, 0)
    await p.timer.advance(750)
    assert.equal(p.sampled.length, 1)
    assert.ok(p.guidance.length > 3)
    p.loop.stop()
  })
}
for (const failure of [new ApiError('auth', 401), new ApiError('forbidden', 403), ...[1010, 1011, 1018, 1019, 1020].map((code) => new ApiError('infra', 422, code)), new DOMException('timeout', 'TimeoutError'), new Error('network'), null]) {
  test(`infra/auth/malformed failure pauses with no retry: ${failure?.message ?? 'malformed'}`, async () => {
    let calls = 0
    const p = pipeline({ probe: async () => { calls++; if (failure) throw failure; return {} } })
    await p.timer.advance(10000)
    assert.equal(calls, 1); assert.equal(p.errors.length, 1); assert.equal(p.sampled.length, 0)
  })
}

for (const stage of ['encode', 'probe']) {
  test(`teardown during deferred ${stage} cannot probe/capture and drains before restart`, async () => {
    const wait = deferred()
    let encodes = 0, probes = 0
    const p = pipeline({ encode: async () => { encodes++; return stage === 'encode' ? wait.promise : { blob: images()[0], width: 1280, height: 720 } }, probe: async () => { probes++; return wait.promise } })
    await p.timer.advance(5000)
    assert.equal(encodes, 1); assert.equal(probes, stage === 'probe' ? 1 : 0)
    p.loop.stop()
    let drained = false
    p.loop.drained.then(() => { drained = true })
    await p.timer.flush(); assert.equal(drained, false)
    wait.resolve(stage === 'encode' ? { blob: images()[0], width: 1280, height: 720 } : quality)
    await p.timer.flush(); assert.equal(drained, true)
    await p.timer.advance(10000)
    assert.equal(p.sampled.length, 0); assert.equal(encodes, 1); assert.equal(probes, stage === 'probe' ? 1 : 0)
  })
}

test('probe longer than 2.5s resets geometry stability on the next frame', async () => {
  const wait = deferred()
  let index = 0
  const p = pipeline({ probe: async () => ++index === 2 ? wait.promise : quality })
  await p.timer.advance(750)
  await p.timer.advance(3000)
  wait.resolve(quality); await p.timer.flush(); await p.timer.advance(0)
  assert.equal(p.sampled.length, 0)
  await p.timer.advance(1500)
  assert.equal(p.sampled.length, 1)
  p.loop.stop()
})

test('review retakes only selected slot and still requires explicit Save', async () => {
  const ui = faceDialog()
  await ui.settle(); await ui.click('M\u1edf camera')
  await ui.advance(6500)
  const before = ui.nodes().filter((node) => node.type === 'img').map((node) => node.props.src)
  await ui.click('Ch\u1ee5p l\u1ea1i \u1ea3nh 2')
  const after = ui.nodes().filter((node) => node.type === 'img').map((node) => node.props.src)
  assert.deepEqual(after, [before[0], before[2]])
  assert.equal(ui.revoked.length, 1)
  await ui.click('M\u1edf camera'); await ui.advance(1500)
  assert.equal(ui.calls.some(([method]) => method === 'PUT'), false)
  await ui.click('L\u01b0u 3 m\u1eabu khu\u00f4n m\u1eb7t')
  const upload = ui.calls.find(([method]) => method === 'PUT')
  assert.equal(upload[2][0], ui.probed[2]); assert.equal(upload[2][1], ui.probed[11]); assert.equal(upload[2][2], ui.probed[8])
  ui.unmount()
})


test('close during late getUserMedia stops returned tracks without encoding or probing', async () => {
  const media = deferred(), stopped = []
  const ui = faceDialog(status, false, { media })
  await ui.settle(); await ui.click('M\u1edf camera')
  await ui.click('\u0110\u00f3ng')
  media.resolve({ active: true, getTracks: () => [{ stop: () => stopped.push(true) }] })
  await ui.settle(); await ui.advance(10000)
  assert.equal(stopped.length, 1); assert.equal(ui.probed.length, 0)
  ui.unmount()
})

test('close during late toBlob prevents any new probe or sample URL', async () => {
  const encode = deferred()
  const ui = faceDialog(status, false, { encode })
  await ui.settle(); await ui.click('M\u1edf camera'); await ui.advance(0)
  await ui.click('\u0110\u00f3ng')
  encode.resolve(images()[0]); await ui.settle(); await ui.advance(10000)
  assert.equal(ui.probed.length, 0); assert.equal(ui.revoked.length, 0); assert.equal(ui.stopped.length, 1)
  ui.unmount()
})

test('valid camera dimension change resets the pass window instead of reusing geometry', async () => {
  let frame = 0
  const p = pipeline({
    encode: async () => ({ blob: images()[0], width: ++frame === 1 ? 1280 : 640, height: 720 }),
    probe: async () => ({ ...quality, imageWidth: frame === 1 ? 1280 : 640 }),
  })
  await p.timer.advance(1500); assert.equal(p.sampled.length, 0)
  await p.timer.advance(750); assert.equal(p.sampled.length, 1)
  p.loop.stop()
})

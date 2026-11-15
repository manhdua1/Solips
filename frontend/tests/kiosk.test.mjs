import assert from 'node:assert/strict'
import { afterEach, test } from 'node:test'
import { readFileSync } from 'node:fs'
import { runInNewContext } from 'node:vm'
import ts from 'typescript'
import { KioskError, MAX_KIOSK_IMAGE_BYTES, kioskReasons, parseKioskResult, recognizeKiosk } from '../src/kiosk-api.ts'

const originalFetch = globalThis.fetch
const image = () => new Blob(['test-only-placeholder'], { type: 'image/jpeg' })
const result = (reason = 'NO_FACE') => ({ success: reason === 'MATCHED', reason })
const deferred = () => { let resolve; const promise = new Promise((yes) => { resolve = yes }); return { promise, resolve } }
afterEach(() => { globalThis.fetch = originalFetch })

test('public request has exactly one image, no auth or cookies and no-store', async () => {
  globalThis.fetch = async (url, options) => {
    assert.equal(url, '/api/kiosk/recognize')
    assert.equal(options.method, 'POST')
    assert.equal(options.credentials, 'omit')
    assert.equal(options.cache, 'no-store')
    assert.equal(options.headers, undefined)
    assert.equal(options.redirect, 'error')
    assert.deepEqual([...options.body.keys()], ['image'])
    assert.equal(options.body.get('image').type, 'image/jpeg')
    return Response.json({ code: 1000, result: result('MATCHED') })
  }
  assert.deepEqual(await recognizeKiosk(image(), new AbortController().signal), result('MATCHED'))
})

test('strictly validates every reason, success pairing and result shape', () => {
  for (const reason of kioskReasons) {
    assert.deepEqual(parseKioskResult(result(reason)), result(reason))
    assert.throws(() => parseKioskResult({ success: reason !== 'MATCHED', reason }), KioskError)
  }
  for (const value of [null, [], {}, { success: true, reason: 'OTHER' }, { success: 1, reason: 'MATCHED' }, { ...result(), identity: 'not-allowed' }]) assert.throws(() => parseKioskResult(value), KioskError)
})

test('HTTP, existing error envelopes, malformed JSON/results and network failures reject without retry', async () => {
  for (const response of [Response.json({ code: 1019, message: 'unavailable' }, { status: 503 }), Response.json({ code: 1019 }), Response.json({ code: '1000', result: result() }), Response.json({ code: 1000, result: result('invalid') }), new Response('not-json')]) {
    let calls = 0
    globalThis.fetch = async () => { calls++; return response }
    await assert.rejects(recognizeKiosk(image(), new AbortController().signal), KioskError)
    assert.equal(calls, 1)
  }
  globalThis.fetch = async () => { throw new TypeError('offline') }
  await assert.rejects(recognizeKiosk(image(), new AbortController().signal), KioskError)
})

test('timeout, external abort and pre-abort fail closed', async () => {
  globalThis.fetch = async (_url, { signal }) => new Promise((_resolve, reject) => signal.addEventListener('abort', () => reject(new Error('aborted')), { once: true }))
  await assert.rejects(recognizeKiosk(image(), new AbortController().signal, 5), /quá thời gian/)
  const controller = new AbortController()
  const pending = recognizeKiosk(image(), controller.signal)
  controller.abort()
  await assert.rejects(pending)
  globalThis.fetch = async () => assert.fail('pre-aborted request must not fetch')
  await assert.rejects(recognizeKiosk(image(), controller.signal))
})

test('invalid and oversized images never fetch', async () => {
  globalThis.fetch = async () => assert.fail('must not fetch')
  for (const blob of [new Blob([], { type: 'image/jpeg' }), new Blob(['x'], { type: 'image/png' }), new Blob([new Uint8Array(MAX_KIOSK_IMAGE_BYTES + 1)], { type: 'image/jpeg' })]) await assert.rejects(recognizeKiosk(blob, new AbortController().signal), KioskError)
})

function clock() {
  let time = 0, serial = 0
  const timers = new Map()
  const flush = async () => { for (let i = 0; i < 30; i++) await Promise.resolve() }
  return { now: () => time, setTimeout: (fn, delay) => { const id = ++serial; timers.set(id, { fn, at: time + delay }); return id }, clearTimeout: (id) => timers.delete(id),
    async advance(ms) { const end = time + ms; await flush(); while (true) { const next = [...timers].sort((a, b) => a[1].at - b[1].at)[0]; if (!next || next[1].at > end) break; time = next[1].at; timers.delete(next[0]); next[1].fn(); await flush() } time = end; await flush() }, flush }
}
const loopCode = ts.transpileModule(readFileSync(new URL('../src/kiosk-recognition-loop.ts', import.meta.url), 'utf8'), { compilerOptions: { module: ts.ModuleKind.CommonJS } }).outputText
function moduleWithClock(timer, document) {
  const exports = {}
  runInNewContext(loopCode, { exports, require: () => ({ KioskError, MAX_KIOSK_IMAGE_BYTES, parseKioskResult }), AbortController, Date: { now: timer.now }, setTimeout: timer.setTimeout, clearTimeout: timer.clearTimeout, document })
  return exports
}
function setup(overrides = {}) {
  const timer = clock(), starts = [], results = [], failures = []
  const loop = moduleWithClock(timer).startKioskRecognitionLoop({ encode: async () => { starts.push(timer.now()); return image() }, recognize: async () => result(), result: (value) => results.push(value), paused: (failure) => failures.push(failure), now: timer.now, ...overrides })
  return { timer, starts, results, failures, loop }
}

test('serial cadence is at least 1000ms with quiet no-face and continuing quality feedback', async () => {
  let calls = 0
  const ctx = setup({ recognize: async () => result(['NO_FACE', 'MULTIPLE_FACES', 'FACE_TOO_SMALL', 'IMAGE_BLURRY', 'ILLUMINATION_POOR'][calls++ % 5]) })
  await ctx.timer.advance(4999)
  assert.deepEqual(ctx.starts, [0, 1000, 2000, 3000, 4000])
  assert.equal(ctx.failures.length, 0)
  ctx.loop.stop()
})

for (const reason of ['MATCHED', 'UNKNOWN', 'AMBIGUOUS']) test(`${reason} cools down for 3 seconds after response`, async () => {
  const pending = deferred()
  const ctx = setup({ recognize: () => pending.promise })
  await ctx.timer.advance(2000)
  assert.deepEqual(ctx.starts, [0])
  pending.resolve(result(reason))
  await ctx.timer.flush()
  await ctx.timer.advance(2999)
  assert.deepEqual(ctx.starts, [0])
  await ctx.timer.advance(1)
  assert.deepEqual(ctx.starts, [0, 5000])
  ctx.loop.stop()
})

test('no overlap during slow encoding/request and no queued catch-up', async () => {
  const encode = deferred(), request = deferred()
  let encodes = 0, requests = 0
  const ctx = setup({ encode: () => { encodes++; return encode.promise }, recognize: () => { requests++; return request.promise } })
  await ctx.timer.advance(2500)
  assert.equal(encodes, 1); assert.equal(requests, 0)
  encode.resolve(image()); await ctx.timer.flush()
  await ctx.timer.advance(2500)
  assert.equal(encodes, 1); assert.equal(requests, 1)
  request.resolve(result()); await ctx.timer.flush()
  await ctx.timer.advance(0)
  assert.equal(encodes, 2)
  ctx.loop.stop()
})

test('no enrolled samples and infrastructure/malformed errors pause until explicit restart', async () => {
  for (const recognize of [async () => result('NO_ENROLLED_SAMPLES'), async () => { throw new KioskError('offline') }, async () => ({ success: true, reason: 'NO_FACE' })]) {
    const ctx = setup({ recognize })
    await ctx.timer.advance(10000)
    assert.deepEqual(ctx.starts, [0])
    await ctx.loop.drained
    ctx.loop.stop()
  }
  const restarted = setup()
  await restarted.timer.advance(0)
  assert.deepEqual(restarted.starts, [0])
  restarted.loop.stop()
})

test('stop while encoding ignores late blob and drains before replacement', async () => {
  const pending = deferred()
  let requests = 0
  const ctx = setup({ encode: () => pending.promise, recognize: async () => { requests++; return result() } })
  await ctx.timer.advance(0)
  ctx.loop.stop()
  pending.resolve(image())
  await ctx.loop.drained
  await ctx.timer.advance(10000)
  assert.equal(requests, 0)
  assert.deepEqual(ctx.results, [null])
})

test('stop aborts request, ignores late response and creates no future pipeline', async () => {
  const pending = deferred()
  let signal
  const ctx = setup({ recognize: (_blob, abortSignal) => { signal = abortSignal; return pending.promise } })
  await ctx.timer.advance(0)
  ctx.loop.stop()
  assert.equal(signal.aborted, true)
  pending.resolve(result('MATCHED'))
  await ctx.loop.drained
  await ctx.timer.advance(10000)
  assert.deepEqual(ctx.results, [null])
  assert.deepEqual(ctx.starts, [0])
  assert.deepEqual(ctx.failures, [])
})

test('stop during cooldown cancels the next capture', async () => {
  const ctx = setup({ recognize: async () => result('MATCHED') })
  await ctx.timer.advance(0)
  ctx.loop.stop()
  await ctx.loop.drained
  await ctx.timer.advance(10000)
  assert.deepEqual(ctx.starts, [0])
})

test('aborted capture ignores late encoding and does not encode another JPEG', async () => {
  const controller = new AbortController()
  let callback, encodes = 0
  const canvas = { width: 0, height: 0, getContext: () => ({ drawImage: () => {} }), toBlob: (done) => { callback = done; encodes++ } }
  const { captureKioskFrame } = moduleWithClock(clock(), { createElement: () => canvas })
  const pending = captureKioskFrame({ readyState: 2, videoWidth: 1280, videoHeight: 720 }, controller.signal)
  controller.abort()
  callback(new Blob([new Uint8Array(MAX_KIOSK_IMAGE_BYTES + 1)], { type: 'image/jpeg' }))
  assert.equal(await pending, null)
  assert.equal(encodes, 1)
  assert.equal(canvas.width, 0)
})

test('capture uses native dimensions and unmirrored draw, bounds JPEG and releases canvas', async () => {
  const timer = clock(), calls = []
  const canvas = { width: 0, height: 0, getContext: () => ({ drawImage: (...args) => calls.push(args) }), toBlob: (callback, type, quality) => { assert.equal(type, 'image/jpeg'); callback(quality > .75 ? new Blob([new Uint8Array(MAX_KIOSK_IMAGE_BYTES + 1)], { type }) : image()) } }
  const { captureKioskFrame } = moduleWithClock(timer, { createElement: () => canvas })
  const video = { readyState: 2, videoWidth: 1920, videoHeight: 1080 }
  const blob = await captureKioskFrame(video)
  assert.equal(blob.type, 'image/jpeg')
  assert.deepEqual(calls[0], [video, 0, 0, 1920, 1080])
  assert.equal(canvas.width, 0); assert.equal(canvas.height, 0)
  assert.equal(await captureKioskFrame({ readyState: 1 }), null)
})

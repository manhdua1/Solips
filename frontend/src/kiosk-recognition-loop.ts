import { KioskError, MAX_KIOSK_IMAGE_BYTES, parseKioskResult } from './kiosk-api.ts'
import type { KioskResult } from './kiosk-api.ts'

type Options = {
  encode: (signal: AbortSignal) => Promise<Blob | null>
  recognize: (blob: Blob, signal: AbortSignal) => Promise<KioskResult>
  result: (result: KioskResult | null) => void
  paused: (failure: unknown) => void
  now?: () => number
}

// CSS mirroring affects only the preview; the native-aspect canvas is never flipped.
export async function captureKioskFrame(video: HTMLVideoElement | null, signal?: AbortSignal): Promise<Blob | null> {
  if (signal?.aborted || !video || video.readyState < 2 || !video.videoWidth || !video.videoHeight) return null
  const canvas = document.createElement('canvas')
  canvas.width = video.videoWidth
  canvas.height = video.videoHeight
  try {
    const context = canvas.getContext('2d')
    if (!context) throw new KioskError('Không thể chụp ảnh từ camera. Vui lòng thử lại.')
    context.drawImage(video, 0, 0, canvas.width, canvas.height)
    for (const quality of [0.9, 0.75, 0.55, 0.35]) {
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', quality))
      if (signal?.aborted) return null
      if (!blob || blob.type !== 'image/jpeg' || !blob.size) throw new KioskError('Không thể mã hóa ảnh JPEG. Vui lòng thử lại.')
      if (blob.size <= MAX_KIOSK_IMAGE_BYTES) return blob
    }
    throw new KioskError('Ảnh camera vượt quá 2 MiB. Chọn camera khác rồi thử lại.')
  } finally {
    canvas.width = 0
    canvas.height = 0
  }
}

// One encode/request pipeline, no queued frames. Draining also serializes camera changes.
export function startKioskRecognitionLoop(options: Options) {
  const controller = new AbortController()
  const now = options.now ?? Date.now
  let timer: ReturnType<typeof setTimeout> | undefined
  let running = false
  let resolveDrained: () => void = () => {}
  const drained = new Promise<void>((resolve) => { resolveDrained = resolve })
  const stop = () => {
    controller.abort()
    clearTimeout(timer)
    if (!running) resolveDrained()
  }
  const schedule = (delay: number) => {
    if (!controller.signal.aborted) timer = setTimeout(() => void tick(), delay)
  }
  async function tick() {
    if (controller.signal.aborted) return
    running = true
    const started = now()
    let cooldown = false
    try {
      const blob = await options.encode(controller.signal)
      if (controller.signal.aborted) return
      if (blob) {
        const result = parseKioskResult(await options.recognize(blob, controller.signal))
        if (controller.signal.aborted) return
        options.result(result)
        if (result.reason === 'NO_ENROLLED_SAMPLES') { stop(); return }
        cooldown = ['MATCHED', 'UNKNOWN', 'AMBIGUOUS'].includes(result.reason)
      } else options.result(null)
    } catch (failure) {
      if (!controller.signal.aborted) {
        stop()
        options.paused(failure)
      }
      return
    } finally {
      running = false
      if (controller.signal.aborted) resolveDrained()
    }
    schedule(cooldown ? 3000 : Math.max(0, 1000 - (now() - started)))
  }
  options.result(null)
  schedule(0)
  return { stop, drained }
}

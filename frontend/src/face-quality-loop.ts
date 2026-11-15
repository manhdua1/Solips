import { ApiError, parseFaceQuality } from './api.ts'
import type { FaceQuality } from './api.ts'

export type EncodedFrame = { blob: Blob; width: number; height: number }
type Options = {
  encode: () => Promise<EncodedFrame | null>
  probe: (blob: Blob, signal: AbortSignal) => Promise<FaceQuality>
  sample: (blob: Blob) => boolean
  guidance: (message: string) => void
  paused: (failure: unknown) => void
  now?: () => number
}
const holding = 'Giữ yên trong ánh sáng tốt. Đang kiểm tra độ ổn định…'
const qualityGuidance: Record<number, string> = {
  1012: 'Chưa thấy khuôn mặt. Nhìn vào camera và đưa mặt vào khung.',
  1013: 'Chỉ để một người trong khung hình.',
  1014: 'Khuôn mặt quá nhỏ. Hãy đến gần camera hơn.',
  1015: 'Ảnh bị mờ. Giữ yên và kiểm tra ống kính.',
  1016: 'Điều chỉnh ánh sáng đều trên mặt, tránh ngược sáng.',
}

// Serial encode/probe: only the exact final passing blob is retained by the caller.
export function startQualityLoop(options: Options) {
  const controller = new AbortController()
  const now = options.now ?? Date.now
  let timer: ReturnType<typeof setTimeout> | undefined
  let previous: FaceQuality | undefined, last = 0, first = 0, passes = 0
  let running = false
  let resolveDrained: () => void = () => {}
  const drained = new Promise<void>((resolve) => { resolveDrained = resolve })
  const reset = () => { previous = undefined; passes = 0; first = 0; last = 0 }
  const stop = () => { controller.abort(); clearTimeout(timer); reset(); if (!running) resolveDrained() }
  const schedule = (delay: number) => { if (!controller.signal.aborted) timer = setTimeout(() => void tick(), delay) }
  async function tick() {
    if (controller.signal.aborted) return
    running = true
    const started = now()
    let captured = false
    try {
      const frame = await options.encode()
      if (controller.signal.aborted) return
      if (!frame) { reset(); options.guidance('Đang chờ camera sẵn sàng…'); schedule(750); return }
      const result = parseFaceQuality(await options.probe(frame.blob, controller.signal))
      if (controller.signal.aborted) return
      if (result.imageWidth !== frame.width || result.imageHeight !== frame.height) throw new ApiError('Kích thước ảnh kiểm tra không khớp. Hãy thử lại.')
      const box = result.faceBox, prior = previous?.faceBox
      const stable = previous && prior && started - last <= 2500 && previous.imageWidth === result.imageWidth && previous.imageHeight === result.imageHeight &&
        Math.abs(box.x + box.width / 2 - prior.x - prior.width / 2) <= 0.03 && Math.abs(box.y + box.height / 2 - prior.y - prior.height / 2) <= 0.03 &&
        Math.abs(box.width - prior.width) / prior.width <= 0.1 && Math.abs(box.height - prior.height) / prior.height <= 0.1
      if (!stable) { reset(); first = started }
      previous = result; last = started; passes++
      options.guidance(holding)
      if (passes >= 3 && started - first >= 1500) {
        captured = true; reset()
        if (options.sample(frame.blob)) { stop(); return }
        options.guidance('Đã chụp. Thư giãn rồi giữ yên cho ảnh tiếp theo.')
      }
    } catch (failure) {
      if (controller.signal.aborted) return
      reset()
      if (failure instanceof ApiError && failure.code && qualityGuidance[failure.code] && failure.status !== 401 && failure.status !== 403) options.guidance(qualityGuidance[failure.code])
      else { stop(); options.paused(failure); return }
    } finally {
      running = false
      if (controller.signal.aborted) resolveDrained()
    }
    schedule(captured ? 1000 : Math.max(0, 750 - (now() - started)))
  }
  options.guidance(holding)
  schedule(0)
  return { stop, drained }
}

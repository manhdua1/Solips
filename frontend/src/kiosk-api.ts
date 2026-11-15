export const MAX_KIOSK_IMAGE_BYTES = 2 * 1024 * 1024
export const kioskReasons = ['MATCHED', 'NO_FACE', 'MULTIPLE_FACES', 'FACE_TOO_SMALL', 'IMAGE_BLURRY', 'ILLUMINATION_POOR', 'NO_ENROLLED_SAMPLES', 'UNKNOWN', 'AMBIGUOUS'] as const
export type KioskReason = typeof kioskReasons[number]
export type KioskResult = { success: boolean; reason: KioskReason }

export class KioskError extends Error {
  status: number
  code?: number
  constructor(message: string, status = 0, code?: number) {
    super(message)
    this.name = 'KioskError'
    this.status = status
    this.code = code
  }
}
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const invalid = () => new KioskError('Phản hồi nhận diện không hợp lệ. Vui lòng thử lại.')

export function parseKioskResult(value: unknown): KioskResult {
  if (!record(value) || Object.keys(value).length !== 2 || typeof value.success !== 'boolean' || !kioskReasons.includes(value.reason as KioskReason) || value.success !== (value.reason === 'MATCHED')) throw invalid()
  return { success: value.success, reason: value.reason as KioskReason }
}

export async function recognizeKiosk(image: Blob, signal: AbortSignal, timeoutMs = 15000): Promise<KioskResult> {
  if (image.type !== 'image/jpeg' || image.size === 0 || image.size > MAX_KIOSK_IMAGE_BYTES) throw new KioskError('Ảnh phải là JPEG không quá 2 MiB. Vui lòng kiểm tra camera và thử lại.')
  const controller = new AbortController()
  const abort = () => controller.abort()
  signal.addEventListener('abort', abort, { once: true })
  let timedOut = false
  const timer = setTimeout(() => { timedOut = true; controller.abort() }, timeoutMs)
  try {
    if (signal.aborted) controller.abort()
    controller.signal.throwIfAborted()
    const body = new FormData()
    body.append('image', image, 'kiosk.jpg')
    const response = await fetch('/api/kiosk/recognize', {
      method: 'POST', body, credentials: 'omit', cache: 'no-store', signal: controller.signal,
      redirect: 'error',
    })
    const envelope: unknown = await response.json()
    if (!record(envelope) || !Number.isInteger(envelope.code)) throw invalid()
    if (!response.ok || envelope.code !== 1000) {
      throw new KioskError(`Không thể nhận diện (HTTP ${response.status}, mã ${envelope.code}). Vui lòng thử lại.`, response.status, envelope.code as number)
    }
    return parseKioskResult(envelope.result)
  } catch (failure) {
    if (signal.aborted) throw failure
    if (timedOut) throw new KioskError('Nhận diện quá thời gian. Vui lòng thử lại.')
    if (failure instanceof KioskError) throw failure
    throw new KioskError('Không thể kết nối hoặc đọc phản hồi nhận diện. Vui lòng thử lại.')
  } finally {
    clearTimeout(timer)
    signal.removeEventListener('abort', abort)
  }
}

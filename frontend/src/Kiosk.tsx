import { useEffect, useRef, useState } from 'react'
import './kiosk.css'
import { KioskError, recognizeKiosk } from './kiosk-api.ts'
import type { KioskReason, KioskResult } from './kiosk-api.ts'
import { captureKioskFrame, startKioskRecognitionLoop } from './kiosk-recognition-loop.ts'

const recognitionMessages: Record<KioskReason, string> = {
  MATCHED: 'Chấm công thành công (demo)',
  NO_FACE: 'Đang chờ khuôn mặt…',
  MULTIPLE_FACES: 'Chỉ để một người trong khung hình.',
  FACE_TOO_SMALL: 'Hãy đến gần camera hơn.',
  IMAGE_BLURRY: 'Ảnh bị mờ. Giữ yên và kiểm tra ống kính.',
  ILLUMINATION_POOR: 'Điều chỉnh ánh sáng đều trên mặt, tránh ngược sáng.',
  NO_ENROLLED_SAMPLES: 'Chưa có mẫu khuôn mặt đã đăng ký. Nhờ quản trị viên kiểm tra rồi thử lại.',
  UNKNOWN: 'Chấm công không thành công (demo): chưa nhận diện được.',
  AMBIGUOUS: 'Chấm công không thành công (demo): kết quả chưa rõ ràng.',
}

export interface KioskProps {
  onExit: () => void
  authenticated?: boolean
  role?: string
}

export function FaceMark({ className = '' }: { className?: string }) {
  return (
    <svg className={className} viewBox="0 0 200 220" fill="none" aria-hidden="true">
      <path className="frame" d="M48 24H24v32m128-32h24v32M24 164v32h24m104 0h24v-32" />
      <path className="face" d="M61 91c0-34 15-52 39-52s39 18 39 52v18c0 34-18 59-39 59s-39-25-39-59V91Z" />
      <path className="face" d="M80 96h1m38 0h1m-20 4-4 21h9m-20 17c10 7 20 7 30 0M55 188c11-9 27-14 45-14s34 5 45 14" />
      <path className="guide" d="M7 110h186M100 8v204" />
    </svg>
  )
}

function formatVietnameseClock(date: Date) {
  const hours = String(date.getHours()).padStart(2, '0')
  const minutes = String(date.getMinutes()).padStart(2, '0')
  const seconds = String(date.getSeconds()).padStart(2, '0')
  const timeString = `${hours}:${minutes}:${seconds}`

  const daysOfWeek = ['Chủ Nhật', 'Thứ Hai', 'Thứ Ba', 'Thứ Tư', 'Thứ Năm', 'Thứ Sáu', 'Thứ Bảy']
  const dayName = daysOfWeek[date.getDay()]
  const day = String(date.getDate()).padStart(2, '0')
  const month = String(date.getMonth() + 1).padStart(2, '0')
  const year = date.getFullYear()
  const dateString = `${dayName}, ngày ${day}/${month}/${year}`

  return { timeString, dateString }
}

export default function Kiosk({ onExit, authenticated }: KioskProps) {
  const [status, setStatus] = useState<'requesting' | 'active' | 'denied' | 'not-found' | 'error' | 'unsupported'>('requesting')
  const [errorMessage, setErrorMessage] = useState('')
  const [isMirrored, setIsMirrored] = useState(true)
  const [isFullscreen, setIsFullscreen] = useState(() => (typeof document !== 'undefined' ? Boolean(document.fullscreenElement) : false))
  const [devices, setDevices] = useState<MediaDeviceInfo[]>([])
  const [selectedDeviceId, setSelectedDeviceId] = useState('')
  const [currentTime, setCurrentTime] = useState(() => new Date())
  const [videoResolution, setVideoResolution] = useState<{ width: number; height: number } | null>(null)
  const [retryCount, setRetryCount] = useState(0)

  const videoRef = useRef<HTMLVideoElement | null>(null)
  const streamRef = useRef<MediaStream | null>(null)
  const [recognition, setRecognition] = useState<KioskResult | null>(null)
  const [recognitionError, setRecognitionError] = useState('')
  const [recognitionRetry, setRecognitionRetry] = useState(0)
  const loopRef = useRef<ReturnType<typeof startKioskRecognitionLoop> | null>(null)
  const drainRef = useRef<Promise<void>>(Promise.resolve())

  const stopRecognition = () => {
    if (loopRef.current) {
      loopRef.current.stop()
      drainRef.current = loopRef.current.drained
      loopRef.current = null
    }
  }

  useEffect(() => {
    let disposed = false
    if (status === 'active') {
      void drainRef.current.then(() => {
        if (disposed) return
        setRecognitionError('')
        const loop = startKioskRecognitionLoop({
          encode: (signal) => captureKioskFrame(videoRef.current, signal),
          recognize: recognizeKiosk,
          result: setRecognition,
          paused: (failure) => {
            setRecognition(null)
            setRecognitionError(failure instanceof KioskError ? failure.message : 'Không thể nhận diện. Vui lòng thử lại.')
          },
        })
        loopRef.current = loop
      })
    }
    return () => {
      disposed = true
      stopRecognition()
    }
  }, [status, selectedDeviceId, retryCount, recognitionRetry])

  const restartCamera = () => {
    stopRecognition()
    setStatus('requesting')
    setRecognition(null)
    setRecognitionError('')
    setVideoResolution(null)
    setRetryCount((count) => count + 1)
  }

  const exitKiosk = () => {
    stopRecognition()
    streamRef.current?.getTracks().forEach((track) => track.stop())
    onExit()
  }

  // Real-time digital clock tick
  useEffect(() => {
    const timer = setInterval(() => {
      setCurrentTime(new Date())
    }, 1000)
    return () => clearInterval(timer)
  }, [])

  // Sync fullscreen state
  useEffect(() => {
    const handleFullscreenChange = () => {
      setIsFullscreen(Boolean(document.fullscreenElement))
    }
    document.addEventListener('fullscreenchange', handleFullscreenChange)
    return () => document.removeEventListener('fullscreenchange', handleFullscreenChange)
  }, [])

  // Listen for device plug / unplug
  useEffect(() => {
    if (typeof navigator === 'undefined' || !navigator.mediaDevices?.addEventListener) return
    const handleDeviceChange = async () => {
      try {
        const allDevices = await navigator.mediaDevices.enumerateDevices()
        setDevices(allDevices.filter((d) => d.kind === 'videoinput'))
      } catch {
        // Non-critical device enumeration fallback
      }
    }
    navigator.mediaDevices.addEventListener('devicechange', handleDeviceChange)
    return () => {
      navigator.mediaDevices.removeEventListener('devicechange', handleDeviceChange)
    }
  }, [])

  // Camera stream lifecycle
  useEffect(() => {
    let isMounted = true

    async function initCamera() {
      if (typeof navigator === 'undefined' || !navigator.mediaDevices || !navigator.mediaDevices.getUserMedia) {
        if (isMounted) {
          setStatus('unsupported')
          setErrorMessage('Trình duyệt không hỗ trợ truy cập máy ảnh hoặc kết nối không an toàn (cần HTTPS/localhost).')
        }
        return
      }

      // Stop existing stream cleanly before creating a new one
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop())
        streamRef.current = null
      }

      if (isMounted) {
        setStatus('requesting')
        setErrorMessage('')
      }

      try {
        const constraints: MediaStreamConstraints = {
          video: selectedDeviceId
            ? { deviceId: { exact: selectedDeviceId } }
            : {
                facingMode: 'user',
                width: { ideal: 1280 },
                height: { ideal: 720 },
              },
          audio: false,
        }

        const mediaStream = await navigator.mediaDevices.getUserMedia(constraints)
        if (!isMounted) {
          mediaStream.getTracks().forEach((track) => track.stop())
          return
        }

        streamRef.current = mediaStream
        if (videoRef.current) {
          videoRef.current.srcObject = mediaStream
        }
        setStatus('active')

        // Enumerate video devices for quick selection
        if (navigator.mediaDevices.enumerateDevices) {
          try {
            const allDevices = await navigator.mediaDevices.enumerateDevices()
            const videoInputs = allDevices.filter((d) => d.kind === 'videoinput')
            if (isMounted) {
              setDevices(videoInputs)
            }
          } catch {
            // Enumeration permission fallback
          }
        }
      } catch (err: unknown) {
        if (!isMounted) return
        const error = err as { name?: string; message?: string }
        if (error.name === 'NotAllowedError' || error.name === 'PermissionDeniedError') {
          setStatus('denied')
          setErrorMessage('Trình duyệt đã từ chối quyền truy cập camera. Vui lòng cho phép quyền trong cài đặt trình duyệt để tiếp tục.')
        } else if (error.name === 'NotFoundError' || error.name === 'DevicesNotFoundError') {
          setStatus('not-found')
          setErrorMessage('Không tìm thấy thiết bị camera nào được kết nối với máy tính.')
        } else if (error.name === 'NotReadableError' || error.name === 'TrackStartError') {
          setStatus('error')
          setErrorMessage('Camera đang được sử dụng bởi ứng dụng khác hoặc gặp sự cố phần cứng.')
        } else {
          setStatus('error')
          setErrorMessage(error.message || 'Không thể mở camera. Vui lòng kiểm tra thiết bị và thử lại.')
        }
      }
    }

    const videoElement = videoRef.current
    void initCamera()

    return () => {
      isMounted = false
      if (streamRef.current) {
        streamRef.current.getTracks().forEach((track) => track.stop())
        streamRef.current = null
      }
      if (videoElement) {
        videoElement.srcObject = null
      }
    }
  }, [selectedDeviceId, retryCount])

  const handleLoadedMetadata = () => {
    if (videoRef.current) {
      setVideoResolution({
        width: videoRef.current.videoWidth,
        height: videoRef.current.videoHeight,
      })
    }
  }

  const toggleFullscreen = async () => {
    try {
      if (!document.fullscreenElement) {
        if (document.documentElement.requestFullscreen) {
          await document.documentElement.requestFullscreen()
        }
      } else {
        if (document.exitFullscreen) {
          await document.exitFullscreen()
        }
      }
    } catch {
      // User gesture restrictions or unsupported fullscreen
    }
  }

  const { timeString, dateString } = formatVietnameseClock(currentTime)

  return (
    <div className="kiosk-root" role="region" aria-label="Máy chấm công nhận diện khuôn mặt">
      {/* Header bar */}
      <header className="kiosk-header">
        <div className="kiosk-header-brand">
          <FaceMark className="kiosk-brand-mark" />
          <div className="kiosk-brand-text">
            <span className="kiosk-brand-title">
              solips<span className="brand-dot">.</span>
            </span>
            <span className="kiosk-brand-sub">Máy chấm công</span>
          </div>
        </div>

        {/* Digital real-time clock */}
        <div
          className="kiosk-clock-panel"
          aria-label={`Thời gian hiện tại: ${timeString}, ${dateString}`}
        >
          <div className="kiosk-clock-time">{timeString}</div>
          <div className="kiosk-clock-date">{dateString}</div>
        </div>

        {/* Quick action buttons */}
        <div className="kiosk-header-actions">
          <button
            type="button"
            className="kiosk-action-btn"
            onClick={toggleFullscreen}
            aria-label={isFullscreen ? 'Thu nhỏ toàn màn hình' : 'Mở toàn màn hình'}
            aria-pressed={isFullscreen}
            title={isFullscreen ? 'Thu nhỏ toàn màn hình' : 'Toàn màn hình'}
          >
            {isFullscreen ? (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M8 3v3a2 2 0 0 1-2 2H3m18 0h-3a2 2 0 0 1-2-2V3m0 18v-3a2 2 0 0 1 2-2h3M3 16h3a2 2 0 0 1 2 2v3" />
              </svg>
            ) : (
              <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M8 3H5a2 2 0 0 0-2 2v3m18 0V5a2 2 0 0 0-2-2h-3m0 18h3a2 2 0 0 0 2-2v-3M3 16v3a2 2 0 0 0 2 2h3" />
              </svg>
            )}
            <span className="action-text">{isFullscreen ? 'Thu nhỏ' : 'Toàn màn hình'}</span>
          </button>

          <button
            type="button"
            className="kiosk-action-btn kiosk-action-btn--primary"
            onClick={exitKiosk}
            aria-label={authenticated ? 'Quay lại trang quản trị' : 'Đăng nhập quản trị'}
          >
            <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
              <path d="M15 3h4a2 2 0 0 1 2 2v14a2 2 0 0 1-2 2h-4M10 17l5-5-5-5M15 12H3" />
            </svg>
            <span className="action-text">{authenticated ? 'Về Quản trị' : 'Quản trị / Đăng nhập'}</span>
          </button>
        </div>
      </header>

      {/* Main Viewport Content */}
      <main className="kiosk-main">
        <h1 className="sr-only">Camera máy chấm công</h1>
        {/* Status announcement badge */}
        <div className="kiosk-status-strip" role="status" aria-live="polite">
          <span
            className={`status-indicator-dot status-indicator-dot--${status === 'active' ? 'active' : status === 'requesting' ? 'busy' : 'alert'}`}
            aria-hidden="true"
          />
          <span className="status-indicator-text">
            {status === 'active' && 'Camera đã kết nối'}
            {status === 'requesting' && 'Đang kích hoạt camera…'}
            {status === 'denied' && 'Cần cấp quyền camera'}
            {status === 'not-found' && 'Không tìm thấy camera'}
            {status === 'error' && 'Lỗi kết nối camera'}
            {status === 'unsupported' && 'Trình duyệt không hỗ trợ'}
          </span>
        </div>

        <section
          className={`kiosk-recognition ${recognition?.success ? 'kiosk-recognition--success' : ''}`}
          aria-label="Kết quả nhận diện demo"
        >
          <p className="kiosk-recognition-message" role="status" aria-live="polite" aria-atomic="true">
            {status !== 'active' ? 'Nhận diện sẽ bắt đầu khi camera sẵn sàng.' : recognitionError || (recognition ? recognitionMessages[recognition.reason] : 'Đang chờ khuôn mặt…')}
          </p>
          <p className="kiosk-demo-notice">Chỉ kiểm tra nhận diện, không lưu chấm công</p>
          {status === 'active' && (recognitionError || recognition?.reason === 'NO_ENROLLED_SAMPLES') && (
            <button
              type="button"
              className="kiosk-retry-btn"
              onClick={() => { stopRecognition(); setRecognitionRetry((count) => count + 1) }}
            >
              Thử lại nhận diện
            </button>
          )}
        </section>

        {/* Camera Viewport Area */}
        <div className="kiosk-viewport-container">
          <div className="kiosk-viewport">
            <video
              ref={videoRef}
              autoPlay
              playsInline
              muted
              onLoadedMetadata={handleLoadedMetadata}
              className={`kiosk-video ${isMirrored ? 'kiosk-video--mirrored' : ''}`}
              aria-label="Khung hình camera máy chấm công"
            />

            {/* Face alignment reticle HUD */}
            {status === 'active' && (
              <div className="kiosk-hud" aria-hidden="true">
                <div className="kiosk-reticle">
                  {/* Corner bracket markers */}
                  <span className="reticle-corner reticle-corner--tl" />
                  <span className="reticle-corner reticle-corner--tr" />
                  <span className="reticle-corner reticle-corner--bl" />
                  <span className="reticle-corner reticle-corner--br" />

                  <div className="reticle-face-oval" />
                </div>
              </div>
            )}

            {/* Connecting State */}
            {status === 'requesting' && (
              <div className="kiosk-state-panel" role="status">
                <h2 className="kiosk-state-title">Đang kích hoạt camera…</h2>
                <p className="kiosk-state-desc">
                  Vui lòng bấm Cho phép (Allow) nếu trình duyệt hiển thị hộp thoại yêu cầu quyền camera.
                </p>
              </div>
            )}

            {/* Camera Error or Permission Denied State */}
            {(status === 'denied' || status === 'not-found' || status === 'error' || status === 'unsupported') && (
              <div className="kiosk-state-panel kiosk-state-panel--error" role="alert">
                <div className="kiosk-error-icon" aria-hidden="true">
                  <svg width="46" height="46" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                    <line x1="1" y1="1" x2="23" y2="23" />
                    <path d="M21 21H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h3m3-3h6l2 3h4a2 2 0 0 1 2 2v9.34m-7.72-2.06a4 4 0 1 1-5.56-5.56" />
                  </svg>
                </div>
                <h2 className="kiosk-state-title">
                  {status === 'denied' && 'Không thể truy cập camera'}
                  {status === 'not-found' && 'Không tìm thấy thiết bị camera'}
                  {status === 'unsupported' && 'Trình duyệt không hỗ trợ'}
                  {status === 'error' && 'Lỗi kết nối camera'}
                </h2>
                <p className="kiosk-state-desc">{errorMessage}</p>
                {status !== 'unsupported' && (
                  <button
                    type="button"
                    className="kiosk-retry-btn"
                    onClick={restartCamera}
                  >
                    <svg width="18" height="18" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                      <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
                    </svg>
                    <span>Thử lại kết nối</span>
                  </button>
                )}
              </div>
            )}
          </div>
        </div>

        {/* Camera Toolbar */}
        <div className="kiosk-toolbar">
          <div className="kiosk-toolbar-group">
            {devices.length > 1 && (
              <div className="kiosk-device-select-wrapper">
                <label htmlFor="kiosk-camera-select" className="kiosk-toolbar-label">
                  <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                    <path d="M23 19a2 2 0 0 1-2 2H3a2 2 0 0 1-2-2V8a2 2 0 0 1 2-2h4l2-3h6l2 3h4a2 2 0 0 1 2 2z" />
                    <circle cx="12" cy="13" r="4" />
                  </svg>
                  <span>Camera:</span>
                </label>
                <select
                  id="kiosk-camera-select"
                  value={selectedDeviceId}
                  onChange={(e) => { stopRecognition(); setStatus('requesting'); setVideoResolution(null); setSelectedDeviceId(e.target.value) }}
                  className="kiosk-select"
                  aria-label="Chọn thiết bị camera đầu vào"
                >
                  {devices.map((device, idx) => (
                    <option key={device.deviceId || idx} value={device.deviceId}>
                      {device.label || `Camera ${idx + 1}`}
                    </option>
                  ))}
                </select>
              </div>
            )}
          </div>

          <div className="kiosk-toolbar-group">
            <button
              type="button"
              className={`kiosk-tool-btn ${isMirrored ? 'kiosk-tool-btn--active' : ''}`}
              onClick={() => setIsMirrored((prev) => !prev)}
              aria-pressed={isMirrored}
              title={isMirrored ? 'Đang bật chế độ gương' : 'Đang tắt chế độ gương'}
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="m14 18 4-4-4-4" />
                <path d="M18 14H10a4 4 0 0 1-4-4V6" />
                <path d="M10 6 6 10l4 4" />
              </svg>
              <span>{isMirrored ? 'Lật gương: Bật' : 'Lật gương: Tắt'}</span>
            </button>

            <button
              type="button"
              className="kiosk-tool-btn"
              onClick={restartCamera}
              title="Khởi động lại luồng camera"
              aria-label="Khởi động lại luồng camera"
            >
              <svg width="16" height="16" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round" aria-hidden="true">
                <path d="M21.5 2v6h-6M21.34 15.57a10 10 0 1 1-.57-8.38l5.67-5.67" />
              </svg>
              <span>Làm mới camera</span>
            </button>
          </div>
        </div>

        {/* Instructions for employees */}
        <section className="kiosk-instructions" aria-labelledby="instructions-heading">
          <h2 id="instructions-heading" className="sr-only">Hướng dẫn sử dụng camera</h2>

          <div className="instruction-card">
            <span className="instruction-icon" aria-hidden="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <path d="M20 21v-2a4 4 0 0 0-4-4H8a4 4 0 0 0-4 4v2" />
                <circle cx="12" cy="7" r="4" />
              </svg>
            </span>
            <div className="instruction-details">
              <strong>Nhìn thẳng vào camera</strong>
              <span>Giữ khuôn mặt ở giữa, cách 50–70 cm</span>
            </div>
          </div>

          <div className="instruction-card">
            <span className="instruction-icon" aria-hidden="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="6" cy="15" r="4" />
                <circle cx="18" cy="15" r="4" />
                <path d="M14 15a2 2 0 0 0-4 0" />
                <path d="M2.5 13 4 7c.3-1.2 1.3-2 2.5-2h11c1.2 0 2.2.8 2.5 2l1.5 6" />
              </svg>
            </span>
            <div className="instruction-details">
              <strong>Kiểm tra khung hình</strong>
              <span>Nhận diện tự động, không lưu chấm công</span>
            </div>
          </div>

          <div className="instruction-card">
            <span className="instruction-icon" aria-hidden="true">
              <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <circle cx="12" cy="12" r="5" />
                <line x1="12" y1="1" x2="12" y2="3" />
                <line x1="12" y1="21" x2="12" y2="23" />
                <line x1="4.22" y1="4.22" x2="5.64" y2="5.64" />
                <line x1="18.36" y1="18.36" x2="19.78" y2="19.78" />
                <line x1="1" y1="12" x2="3" y2="12" />
                <line x1="21" y1="12" x2="23" y2="12" />
                <line x1="4.22" y1="19.78" x2="5.64" y2="18.36" />
                <line x1="18.36" y1="5.64" x2="19.78" y2="4.22" />
              </svg>
            </span>
            <div className="instruction-details">
              <strong>Đảm bảo đủ ánh sáng</strong>
              <span>Tránh ánh sáng ngược chói hoặc góc tối</span>
            </div>
          </div>
        </section>
      </main>

      {/* Kiosk Footer */}
      <footer className="kiosk-footer">
        <div className="kiosk-system-badge">
          <span
            className={`system-status-dot system-status-dot--${status === 'active' ? 'ready' : status === 'requesting' ? 'busy' : 'offline'}`}
            aria-hidden="true"
          />
          <span>{status === 'active' ? 'Thiết bị sẵn sàng' : status === 'requesting' ? 'Đang chuẩn bị…' : 'Chưa kết nối'}</span>
        </div>
        <div className="kiosk-meta">
          <span>Solips FaceMark Kiosk</span>
          {videoResolution && <span>• {videoResolution.width} × {videoResolution.height} px</span>}
        </div>
      </footer>
    </div>
  )
}

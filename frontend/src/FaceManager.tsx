import { useEffect, useRef, useState } from 'react'
import { errorMessage } from './api.ts'
import type { Client, FaceStatus } from './api.ts'
import './face.css'

type Capture = { blob: Blob; url: string }
const stop = (stream: MediaStream | null) => stream?.getTracks().forEach((track) => track.stop())

export default function FaceManager({ id, name, client, close, changed }: { id: string; name: string; client: Client; close: () => void; changed: (status: FaceStatus) => void }) {
  const dialog = useRef<HTMLDialogElement>(null)
  const video = useRef<HTMLVideoElement>(null)
  const stream = useRef<MediaStream | null>(null)
  const captures = useRef<Capture[]>([])
  const lifetime = useRef<AbortController | null>(null)
  const generation = useRef(0)
  const busy = useRef(false)
  const [status, setStatus] = useState<FaceStatus | null>(null)
  const [samples, setSamples] = useState<Capture[]>([])
  const [pending, setPending] = useState(true)
  const [camera, setCamera] = useState(false)
  const [error, setError] = useState('')
  const [notice, setNotice] = useState('')
  const [confirmDelete, setConfirmDelete] = useState(false)
  const [needsRefresh, setNeedsRefresh] = useState(true)

  function release() {
    generation.current++
    stop(stream.current); stream.current = null
    if (video.current) video.current.srcObject = null
    captures.current.forEach((sample) => URL.revokeObjectURL(sample.url))
    captures.current = []
  }
  function reset() {
    release(); setSamples([]); setCamera(false); setConfirmDelete(false)
  }
  async function refresh(signal: AbortSignal) {
    const result = await client.faceStatus(id, signal)
    if (signal.aborted) return
    setStatus(result); setNeedsRefresh(false); changed(result)
    return result
  }
  useEffect(() => {
    const previous = document.activeElement as HTMLElement | null
    const controller = new AbortController()
    lifetime.current = controller
    const element = dialog.current!
    element.showModal()
    void refresh(controller.signal).catch((failure) => { if (!controller.signal.aborted) setError(errorMessage(failure)) }).finally(() => { if (!controller.signal.aborted) setPending(false) })
    return () => {
      controller.abort(); release(); element.close()
      if (previous?.isConnected) previous.focus()
    }
    // The parent keys this dialog by employee; callbacks do not restart its lifetime.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [client, id])

  async function run(action: (signal: AbortSignal) => Promise<void>) {
    if (busy.current || pending || !lifetime.current) return
    busy.current = true; setPending(true); setError(''); setNotice('')
    const signal = lifetime.current.signal
    try { await action(signal) }
    catch (failure) { if (!signal.aborted) setError(errorMessage(failure)) }
    finally { if (!signal.aborted) { busy.current = false; setPending(false) } }
  }
  async function begin(signal: AbortSignal) {
    if (!status || needsRefresh) return
    if (!navigator.mediaDevices?.getUserMedia) throw new Error('camera unavailable')
    const attempt = ++generation.current
    let media: MediaStream
    try { media = await navigator.mediaDevices.getUserMedia({ video: { width: { ideal: 1280 }, height: { ideal: 720 }, facingMode: 'user' }, audio: false }) }
    catch {
      if (!signal.aborted && attempt === generation.current) { reset(); setError('Không mở được camera. Kiểm tra quyền camera, kết nối HTTPS và thiết bị rồi thử lại.') }
      return
    }
    if (signal.aborted || attempt !== generation.current) { stop(media); return }
    stream.current = media
    if (video.current) { video.current.srcObject = media; await video.current.play() }
    if (!signal.aborted && attempt === generation.current) setCamera(true)
  }
  async function capture() {
    if (busy.current || !video.current || !camera || samples.length >= 3) return
    const source = video.current
    if (source.readyState < 2 || !source.videoWidth || !stream.current?.active) { setError('Camera chưa sẵn sàng. Vui lòng thử lại.'); return }
    const attempt = generation.current
    await run(async () => {
      const canvas = document.createElement('canvas')
      const ratio = Math.min(1, 1280 / source.videoWidth, 960 / source.videoHeight)
      canvas.width = Math.round(source.videoWidth * ratio); canvas.height = Math.round(source.videoHeight * ratio)
      const context = canvas.getContext('2d')
      if (!context) throw new Error('canvas unavailable')
      context.drawImage(source, 0, 0, canvas.width, canvas.height)
      const blob = await new Promise<Blob | null>((resolve) => canvas.toBlob(resolve, 'image/jpeg', 0.88))
      canvas.width = 0; canvas.height = 0
      if (lifetime.current?.signal.aborted || attempt !== generation.current) return
      if (!blob || blob.type !== 'image/jpeg' || !blob.size || blob.size > 2 * 1024 * 1024) { setError('Không tạo được ảnh JPEG tối đa 2 MiB. Vui lòng chụp lại.'); return }
      const next = [...captures.current, { blob, url: URL.createObjectURL(blob) }]
      captures.current = next; setSamples(next)
    })
  }
  async function mutate(remove: boolean, signal: AbortSignal) {
    if (!status || needsRefresh) return
    if (!remove && samples.length !== 3) { reset(); return }
    stop(stream.current); stream.current = null; setCamera(false)
    try {
      const result = remove ? await client.deleteFace(id, status.revision, signal) : await client.enrollFace(id, samples.map((sample) => sample.blob), status.revision, signal)
      if (signal.aborted) return
      reset(); setStatus(result); changed(result)
      setNotice(remove ? 'Đã xóa toàn bộ mẫu khuôn mặt.' : 'Đã lưu 3 mẫu khuôn mặt. Đây không phải kết quả nhận diện hay chấm công.')
    } catch (failure) {
      if (signal.aborted) return
      reset(); setNeedsRefresh(true)
      const message = errorMessage(failure)
      try {
        await refresh(signal)
        if (!signal.aborted) setError(`${message} Trạng thái hiện tại đã được tải lại; hãy kiểm tra trước khi thao tác tiếp.`)
      } catch {
        if (!signal.aborted) setError(`${message} Chưa xác minh được kết quả. Tải lại trạng thái trước khi thử tiếp; không tự động gửi lại.`)
      }
    }
  }

  return <dialog ref={dialog} className="admin-dialog face-dialog" aria-labelledby="face-title" onCancel={(event) => { event.preventDefault(); close() }}>
    <div className="dialog-heading"><div><p className="face-eyebrow">Hồ sơ khuôn mặt</p><h2 id="face-title">{name}</h2></div><button type="button" onClick={close} autoFocus>Đóng</button></div>
    <div aria-busy={pending}>
      <p className="face-status" role="status">{pending ? 'Đang xử lý…' : notice || (needsRefresh ? 'Chưa xác minh trạng thái.' : status?.registered ? `Đã đăng ký · ${status.sampleCount} mẫu · phiên bản ${status.revision}` : 'Chưa đăng ký khuôn mặt')}</p>
      {error && <p className="error-message" role="alert">{error}</p>}
      <button type="button" disabled={pending} onClick={() => void run(async (signal) => { reset(); setNeedsRefresh(true); await refresh(signal) })}>Tải lại trạng thái</button>
      {status && !needsRefresh && <>
        <section className="face-capture" aria-labelledby="capture-title"><h3 id="capture-title">Chụp và kiểm tra 3 ảnh</h3><p>Một người trong khung, đủ sáng, nhìn thẳng rồi nghiêng nhẹ giữa các lần chụp. Ảnh chỉ là bản xem trước; máy chủ sẽ kiểm tra chất lượng. Không kiểm tra liveness.</p>
          <p>Chỉ lưu sau khi đủ 3 ảnh và bạn chọn lưu.</p>
          {!camera && <button type="button" className="primary-button" disabled={pending} onClick={() => void run(async (signal) => {
            try { await begin(signal) } catch (failure) { reset(); throw failure }
          })}>Mở camera</button>}
          <div className="face-viewport"><video ref={video} muted playsInline autoPlay aria-label="Xem trước camera đăng ký khuôn mặt" />{!camera && <span>Camera đang tắt</span>}</div>
          <div className="face-capture-tools"><span role="status">{samples.length}/3 ảnh tạm · JPEG ≤ 2 MiB/ảnh</span><button type="button" disabled={pending || !camera || samples.length >= 3} onClick={() => void capture()}>Chụp ảnh {Math.min(samples.length + 1, 3)}</button></div>
          <ol className="face-previews">{samples.map((sample, index) => <li key={sample.url}><img src={sample.url} alt={`Ảnh tạm ${index + 1} của ${name}`} /><button type="button" disabled={pending} onClick={() => { URL.revokeObjectURL(sample.url); const next = captures.current.filter((item) => item !== sample); captures.current = next; setSamples(next) }}>Chụp lại ảnh {index + 1}</button></li>)}</ol>
          <p className="delete-note">Ảnh tạm chỉ ở bộ nhớ phiên này, được giải phóng khi đóng. Máy chủ lưu mẫu đặc trưng, không giữ ảnh gốc. Đăng ký lại chỉ thay thế mẫu cũ khi lưu thành công.</p>
          <div className="dialog-actions"><button type="button" disabled={pending || (!camera && samples.length === 0)} onClick={reset}>Hủy ảnh và tắt camera</button><button type="button" className="primary-button" disabled={pending || samples.length !== 3} onClick={() => void run((signal) => mutate(false, signal))}>{status.registered ? 'Lưu đăng ký lại' : 'Lưu 3 mẫu khuôn mặt'}</button></div>
        </section>
        {status.registered && <section className="face-delete" aria-labelledby="face-delete-title"><h3 id="face-delete-title">Xóa dữ liệu khuôn mặt</h3><p>Xóa toàn bộ mẫu đặc trưng. Không thể hoàn tác.</p>{confirmDelete ? <div className="dialog-actions"><button type="button" disabled={pending} onClick={() => setConfirmDelete(false)}>Giữ lại dữ liệu</button><button type="button" className="danger-button" disabled={pending} onClick={() => void run((signal) => mutate(true, signal))}>Xác nhận xóa toàn bộ</button></div> : <button type="button" disabled={pending} onClick={() => { reset(); setConfirmDelete(true) }}>Xóa dữ liệu khuôn mặt…</button>}</section>}
      </>}
    </div>
  </dialog>
}

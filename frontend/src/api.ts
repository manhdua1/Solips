import type { Session } from './session.ts'
import { expiredNotice } from './session.ts'

export type Resource = 'employees' | 'departments'
export type EmployeeStatus = 'ACTIVE' | 'INACTIVE' | 'RESIGNED'
export interface Employee { id: string; code: string; fullName: string; email: string; departmentId: string | null; status: EmployeeStatus; faceRegistered?: boolean; faceSampleCount?: number }
export interface FaceStatus { employeeId: string; registered: boolean; sampleCount: number; revision: number; consentVersion: string; consentText: string }
export interface FaceReceipt { consentToken: string; consentVersion: string; consentedAt: string; expiresAt: string }
export interface Department { id: string; name: string; managerId: string | null }
export interface Entities { employees: Employee; departments: Department }
export interface Page<T> { content: T[]; page: number; size: number; totalElements: number; totalPages: number }
export type EmployeeInput = Omit<Employee, 'id' | 'faceRegistered' | 'faceSampleCount'>
export type DepartmentInput = Omit<Department, 'id'>
export class ApiError extends Error {
  status: number
  code?: number
  constructor(message: string, status = 0, code?: number) { super(message); this.name = 'ApiError'; this.status = status; this.code = code }
}
const faceErrors: Record<number, string> = {
  1008: 'Dữ liệu khuôn mặt vừa thay đổi. Đã yêu cầu tải lại trạng thái.',
  1009: 'Nội dung đồng ý đã thay đổi. Vui lòng đọc và xác nhận lại.',
  1010: 'Ảnh vượt giới hạn tải lên. Hãy chụp lại ảnh nhỏ hơn 2 MiB.',
  1011: 'Định dạng ảnh không được hỗ trợ. Hãy chụp lại ảnh JPEG.',
  1012: 'Không tìm thấy khuôn mặt. Hãy nhìn thẳng vào camera và chụp lại.',
  1013: 'Có nhiều khuôn mặt. Chỉ để một người trong khung hình.',
  1014: 'Khuôn mặt quá nhỏ. Hãy đến gần camera hơn.',
  1015: 'Ảnh bị mờ. Giữ yên và chụp lại.',
  1016: 'Ánh sáng chưa phù hợp. Hãy điều chỉnh ánh sáng và chụp lại.',
  1017: 'Xác nhận đồng ý không còn hợp lệ. Vui lòng xác nhận lại.',
  1018: 'Dịch vụ xử lý ảnh gặp lỗi. Vui lòng thử lại sau.',
  1019: 'Dịch vụ xử lý ảnh chưa sẵn sàng. Vui lòng thử lại sau.',
  1020: 'Xử lý ảnh quá thời gian. Cần kiểm tra trạng thái trước khi thử lại.',
}
const invalid = () => new ApiError('Phản hồi hệ thống không hợp lệ. Vui lòng thử lại.')
const record = (value: unknown): value is Record<string, unknown> => !!value && typeof value === 'object' && !Array.isArray(value)
const nullableId = (value: unknown) => value == null || typeof value === 'string'
function entity<K extends Resource>(kind: K, value: unknown): Entities[K] {
  if (!record(value) || typeof value.id !== 'string' || !value.id) throw invalid()
  if (kind === 'employees') {
    if (!['code', 'fullName', 'email'].every((key) => typeof value[key] === 'string') || !nullableId(value.departmentId) || !['ACTIVE', 'INACTIVE', 'RESIGNED'].includes(String(value.status))) throw invalid()
    if (value.faceRegistered !== undefined && typeof value.faceRegistered !== 'boolean') throw invalid()
    if (value.faceSampleCount !== undefined && (!Number.isSafeInteger(value.faceSampleCount) || Number(value.faceSampleCount) < 0)) throw invalid()
    return { ...value, departmentId: value.departmentId ?? null } as unknown as Entities[K]
  }
  if (typeof value.name !== 'string' || !nullableId(value.managerId)) throw invalid()
  return { ...value, managerId: value.managerId ?? null } as unknown as Entities[K]
}
export function errorMessage(error: unknown) {
  return error instanceof ApiError ? error.message : 'Không thể hoàn tất yêu cầu. Vui lòng thử lại.'
}
export function createClient(session: Session, onUnauthorized: (notice: string) => void) {
  async function request(path: string, method: string, signal?: AbortSignal, body?: unknown, noResult = false, timeout = 15000): Promise<unknown> {
    signal?.throwIfAborted()
    if (Date.now() >= session.expiresAt) { onUnauthorized(expiredNotice); throw new ApiError(expiredNotice, 401) }
    let response: Response
    try {
      response = await fetch(`/api/${path}`, { method, credentials: 'omit', cache: 'no-store', headers: { Accept: 'application/json', Authorization: `Bearer ${session.accessToken}`, ...(body === undefined || body instanceof FormData ? {} : { 'Content-Type': 'application/json' }) }, body: body === undefined ? undefined : body instanceof FormData ? body : JSON.stringify(body), signal: signal ? AbortSignal.any([signal, AbortSignal.timeout(timeout)]) : AbortSignal.timeout(timeout) })
    } catch (error) {
      signal?.throwIfAborted()
      throw new ApiError(error instanceof DOMException && error.name === 'TimeoutError' ? 'Yêu cầu quá thời gian. Vui lòng thử lại.' : 'Không thể kết nối đến hệ thống. Vui lòng thử lại.')
    }
    signal?.throwIfAborted()
    if (response.status === 401) { onUnauthorized(expiredNotice); throw new ApiError(expiredNotice, 401) }
    if (response.status === 403) throw new ApiError('Bạn không có quyền thực hiện thao tác này (403).', 403)
    if (!response.ok) {
      let failure: unknown
      try { failure = await response.json() } catch { /* Use sanitized HTTP fallback. */ }
      if (record(failure) && typeof failure.code === 'number' && faceErrors[failure.code]) throw new ApiError(faceErrors[failure.code], response.status, failure.code)
      const messages: Record<number, string> = { 400: 'Dữ liệu không hợp lệ. Vui lòng kiểm tra các trường.', 404: 'Bản ghi không còn tồn tại. Hãy tải lại danh sách.', 409: 'Dữ liệu bị trùng hoặc vừa thay đổi. Hãy kiểm tra và thử lại.', 429: 'Có quá nhiều yêu cầu. Vui lòng thử lại sau.' }
      throw new ApiError(messages[response.status] ?? 'Không thể hoàn tất yêu cầu. Vui lòng thử lại.', response.status)
    }
    let data: unknown
    try { data = await response.json() } catch { throw invalid() }
    signal?.throwIfAborted()
    if (!record(data) || data.code !== 1000 || (!noResult && data.result == null)) throw invalid()
    return data.result
  }
  function faceStatus(value: unknown, id: string): FaceStatus {
    if (!record(value) || value.employeeId !== id || typeof value.registered !== 'boolean' || !['sampleCount', 'revision'].every((key) => Number.isSafeInteger(value[key]) && Number(value[key]) >= 0) || typeof value.consentVersion !== 'string' || !value.consentVersion.trim() || typeof value.consentText !== 'string' || !value.consentText.trim() || (value.registered ? value.sampleCount !== 3 : value.sampleCount !== 0)) throw invalid()
    return value as unknown as FaceStatus
  }
  const facePath = (id: string) => `employees/${encodeURIComponent(id)}/face`
  return {
    async faceStatus(id: string, signal?: AbortSignal) { return faceStatus(await request(facePath(id), 'GET', signal), id) },
    async faceConsent(id: string, consentVersion: string, expectedRevision: number, signal?: AbortSignal): Promise<FaceReceipt> {
      const value = await request(`${facePath(id)}/consent`, 'POST', signal, { accepted: true, consentVersion, expectedRevision })
      if (!record(value) || typeof value.consentToken !== 'string' || !value.consentToken || value.consentVersion !== consentVersion || typeof value.consentedAt !== 'string' || !Number.isFinite(Date.parse(value.consentedAt)) || typeof value.expiresAt !== 'string' || !(Date.parse(value.expiresAt) > Date.now())) throw invalid()
      return value as unknown as FaceReceipt
    },
    async enrollFace(id: string, images: Blob[], consentToken: string, expectedRevision: number, signal?: AbortSignal) {
      if (images.length !== 3 || images.some((image) => image.type !== 'image/jpeg' || image.size === 0 || image.size > 2 * 1024 * 1024) || !consentToken || !Number.isSafeInteger(expectedRevision) || expectedRevision < 0) throw new ApiError('Cần đúng 3 ảnh JPEG, mỗi ảnh tối đa 2 MiB và xác nhận đồng ý hợp lệ.', 400)
      const body = new FormData()
      images.forEach((image, index) => body.append('images', image, `sample-${index + 1}.jpg`))
      body.append('consentToken', consentToken)
      body.append('expectedRevision', String(expectedRevision))
      return faceStatus(await request(facePath(id), 'PUT', signal, body, false, 45000), id)
    },
    async deleteFace(id: string, expectedRevision: number, signal?: AbortSignal) { return faceStatus(await request(facePath(id), 'DELETE', signal, { expectedRevision }, false, 45000), id) },
    async list<K extends Resource>(kind: K, page: number, size: number, signal?: AbortSignal): Promise<Page<Entities[K]>> {
      const data = await request(`${kind}?page=${page}&size=${size}`, 'GET', signal)
      if (!record(data) || !Array.isArray(data.content) || !['page', 'size', 'totalElements', 'totalPages'].every((key) => typeof data[key] === 'number' && Number.isSafeInteger(data[key]) && Number(data[key]) >= 0) || data.page !== page || data.size !== size || data.content.length > size) throw invalid()
      return { ...data, content: data.content.map((item) => entity(kind, item)) } as unknown as Page<Entities[K]>
    },
    async all<K extends Resource>(kind: K, signal?: AbortSignal): Promise<Entities[K][]> {
      const result: Entities[K][] = []
      let page = 0
      while (true) {
        const data = await this.list(kind, page, 100, signal)
        result.push(...data.content)
        if (page + 1 >= data.totalPages) return [...new Map(result.map((item) => [item.id, item])).values()]
        if (!data.content.length) throw invalid()
        page++
      }
    },
    async detail<K extends Resource>(kind: K, id: string, signal?: AbortSignal): Promise<Entities[K]> { return entity(kind, await request(`${kind}/${encodeURIComponent(id)}`, 'GET', signal)) },
    async save(kind: Resource, id: string | null, input: EmployeeInput | DepartmentInput, signal?: AbortSignal) { return entity(kind, await request(`${kind}${id ? `/${encodeURIComponent(id)}` : ''}`, id ? 'PUT' : 'POST', signal, input)) },
    async remove(kind: Resource, id: string, signal?: AbortSignal) { await request(`${kind}/${encodeURIComponent(id)}`, 'DELETE', signal, undefined, true) },
  }
}
export type Client = ReturnType<typeof createClient>

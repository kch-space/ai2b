export interface HealthResponse {
  status: 'UP'
  service: string
  timestamp: string
  requestId: string
}

function isHealthResponse(value: unknown): value is HealthResponse {
  if (typeof value !== 'object' || value === null) return false
  const data = value as Record<string, unknown>
  return data.status === 'UP'
    && typeof data.service === 'string' && data.service.length > 0
    && typeof data.timestamp === 'string' && Number.isFinite(Date.parse(data.timestamp))
    && typeof data.requestId === 'string' && data.requestId.length > 0
}

export async function getHealth(signal: AbortSignal): Promise<HealthResponse> {
  const response = await fetch('/api/v1/health', {
    headers: { Accept: 'application/json' },
    signal,
    cache: 'no-store',
  })
  if (!response.ok) throw new Error(`后端返回 HTTP ${response.status}`)
  const data: unknown = await response.json()
  if (!isHealthResponse(data)) throw new Error('后端健康响应格式不正确')
  return data
}

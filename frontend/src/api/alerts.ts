export interface Evidence {
  source?: string; quality?: string; baselinePrice?: string; latestPrice?: string; changeRatio?: string
  dataTime?: string; baselineTime?: string; triggerRatio?: string; escalationRatio?: string; recoveryRatio?: string; ruleVersion?: number
}
export interface AlertEvent {
  id: string; symbol: string; rule_code: string; version_number: number; status: 'ACTIVE' | 'RESOLVED'; severity: 'P0' | 'P1'
  opened_at: string; last_seen_at: string; resolved_at: string | null; hit_count: number; evidence: Evidence
}
export interface EventPage { items: AlertEvent[]; total: number; page: number; pageSize: number; channel: 'MOCK' | 'MOCK+TELEGRAM'; simulation: boolean; serverTime: string }
export interface Rule {
  id: number; rule_code: string; version_number: number; direction: 'UP' | 'DOWN'; window_minutes: number
  trigger_ratio: string; escalation_ratio: string; recovery_ratio: string; recovery_windows: number; cooldown_seconds: number; reminder_delta: string
}
export interface RuleState {
  symbol: string; rule_code: string; outcome: 'MATCH' | 'NO_MATCH' | 'UNKNOWN'; reason: string
  evaluated_at: string; last_window: string | null; recovery_count: number; change_ratio: string | null
}
export interface RuleResponse { items: Rule[]; states: RuleState[]; enabled: boolean; simulation: boolean }
export interface EventHistory { id: string; action: string; severity: string; data_time: string; created_at: string; evidence: Evidence }
export interface Notification {
  id: string; history_id: string; channel: 'MOCK' | 'TELEGRAM'; status: 'PENDING' | 'PROCESSING' | 'SIMULATED' | 'SENT' | 'CANCELLED' | 'DEAD'
  attempts: number; last_error: string | null; provider_message_id: string | null; sent_at: string | null
}
export interface NotificationAttempt { outbox_id: string; attempt_number: number; outcome: string; error_message: string | null; created_at: string }
export interface EventDetail { event: AlertEvent; history: EventHistory[]; notifications: Notification[]; attempts: NotificationAttempt[]; simulation: boolean }
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const date = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const decimal = (v: unknown): v is string => typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v)
function event(v: unknown): v is AlertEvent {
  return object(v) && typeof v.id === 'string' && /^[\da-f-]{36}$/i.test(v.id)
    && ['BTCUSDT', 'ETHUSDT'].includes(String(v.symbol)) && ['PRICE_SURGE', 'PRICE_DROP'].includes(String(v.rule_code))
    && Number.isInteger(v.version_number) && ['ACTIVE', 'RESOLVED'].includes(String(v.status)) && ['P0', 'P1'].includes(String(v.severity))
    && date(v.opened_at) && date(v.last_seen_at) && (v.resolved_at === null || date(v.resolved_at))
    && Number.isInteger(v.hit_count) && object(v.evidence) && decimal(v.evidence.changeRatio)
}
async function request(path: string, signal: AbortSignal): Promise<unknown> {
  const response = await fetch(`/api/v1/alerts${path}`, { headers: { Accept: 'application/json' }, signal, cache: 'no-store' })
  if (!response.ok) {
    let message = `预警接口返回 HTTP ${response.status}`
    try { const body: unknown = await response.json(); if (object(body) && typeof body.message === 'string') message = body.message } catch { /* status fallback */ }
    throw new Error(message)
  }
  return response.json()
}
export async function getEvents(page: number, status: string, signal: AbortSignal): Promise<EventPage> {
  const data = await request(`/events?page=${page}&pageSize=20&status=${encodeURIComponent(status)}`, signal)
  if (!object(data) || typeof data.simulation !== 'boolean' || !['MOCK', 'MOCK+TELEGRAM'].includes(String(data.channel)) || !Array.isArray(data.items) || !data.items.every(event)
    || !Number.isInteger(data.total) || Number(data.total) < 0 || !Number.isInteger(data.page) || !Number.isInteger(data.pageSize)
    || !date(data.serverTime)) throw new Error('预警列表响应格式不正确')
  return data as unknown as EventPage
}
export async function getRules(signal: AbortSignal): Promise<RuleResponse> {
  const data = await request('/rules', signal)
  if (!object(data) || typeof data.simulation !== 'boolean' || typeof data.enabled !== 'boolean' || !Array.isArray(data.items)
    || !data.items.every(r => object(r) && typeof r.rule_code === 'string' && Number.isInteger(r.id)
      && Number.isInteger(r.version_number) && Number.isInteger(r.window_minutes) && Number.isInteger(r.recovery_windows)
      && Number.isInteger(r.cooldown_seconds) && decimal(r.trigger_ratio) && decimal(r.escalation_ratio) && decimal(r.recovery_ratio))
    || !Array.isArray(data.states) || !data.states.every(s => object(s) && typeof s.symbol === 'string' && typeof s.rule_code === 'string'
      && ['UNKNOWN', 'MATCH', 'NO_MATCH'].includes(String(s.outcome)) && typeof s.reason === 'string' && date(s.evaluated_at))) throw new Error('规则响应格式不正确')
  return data as unknown as RuleResponse
}
export async function getEvent(id: string, signal: AbortSignal): Promise<EventDetail> {
  const data = await request(`/events/${encodeURIComponent(id)}`, signal)
  if (!object(data) || typeof data.simulation !== 'boolean' || !event(data.event) || !Array.isArray(data.history)
    || !data.history.every(h => object(h) && typeof h.id === 'string' && typeof h.action === 'string' && date(h.data_time) && object(h.evidence))
    || !Array.isArray(data.notifications) || !data.notifications.every(n => object(n) && typeof n.id === 'string'
      && ['MOCK', 'TELEGRAM'].includes(String(n.channel)) && ['PENDING', 'PROCESSING', 'SIMULATED', 'SENT', 'CANCELLED', 'DEAD'].includes(String(n.status)) && Number.isInteger(n.attempts))
    || !Array.isArray(data.attempts) || !data.attempts.every(a => object(a) && typeof a.outbox_id === 'string'
      && Number.isInteger(a.attempt_number) && typeof a.outcome === 'string' && date(a.created_at))) throw new Error('事件详情响应格式不正确')
  return data as unknown as EventDetail
}

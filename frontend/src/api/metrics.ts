import type { MarketSymbol } from './klines'

export const metricTypes = ['OPEN_INTEREST', 'FUNDING_SNAPSHOT', 'FUNDING_HISTORY', 'GLOBAL_ACCOUNT_RATIO', 'TOP_ACCOUNT_RATIO', 'TOP_POSITION_RATIO'] as const
export type MetricType = typeof metricTypes[number]
export interface MetricPoint { eventTime: string; receivedAt: string; values: Record<string, string> }
export interface MetricStream {
  type: MetricType; authentication: 'PUBLIC' | 'API_KEY'; period: '5m' | 'OBSERVATION'
  start: string; end: string; items: MetricPoint[]; latest: MetricPoint | null
  expectedPoints: number | null; missingPoints: number | null; stale: boolean
  quality: 'UNAVAILABLE' | 'STALE' | 'GAPPED' | 'READY'
  sync: { lastAttemptAt: string | null; lastSuccessAt: string | null; nextAllowedAt: string; failures: number; errorCode: string | null; error: string | null }
}
export interface MetricPage {
  symbol: MarketSymbol; baseAsset: string; quoteAsset: string; running: boolean
  sourceGate: { until: string; error: string | null }; streams: MetricStream[]; serverTime: string; requestId: string
}
const object = (v: unknown): v is Record<string, unknown> => typeof v === 'object' && v !== null
const time = (v: unknown): v is string => typeof v === 'string' && Number.isFinite(Date.parse(v))
const optionalTime = (v: unknown) => v === null || time(v)
const optionalText = (v: unknown) => v === null || typeof v === 'string'
const decimal = (v: unknown): v is string => typeof v === 'string' && /^-?\d+(\.\d+)?$/.test(v) && Number.isFinite(Number(v))
const fields: Record<MetricType, string[]> = {
  OPEN_INTEREST: ['quantity', 'notional'], FUNDING_SNAPSHOT: ['rate', 'markPrice', 'indexPrice', 'nextFundingTime'],
  FUNDING_HISTORY: ['rate', 'markPrice'], GLOBAL_ACCOUNT_RATIO: ['longShare', 'shortShare', 'ratio'],
  TOP_ACCOUNT_RATIO: ['longShare', 'shortShare', 'ratio'], TOP_POSITION_RATIO: ['longShare', 'shortShare', 'ratio'],
}
function point(v: unknown, type: MetricType): v is MetricPoint {
  return object(v) && time(v.eventTime) && time(v.receivedAt) && object(v.values)
    && fields[type].every(field => field === 'nextFundingTime' ? time(v.values && (v.values as Record<string, unknown>)[field])
      : decimal((v.values as Record<string, unknown>)[field])
        && (field === 'rate' || Number((v.values as Record<string, unknown>)[field]) >= 0))
}
function stream(v: unknown): v is MetricStream {
  if (!object(v) || !metricTypes.includes(v.type as MetricType)) return false
  const type = v.type as MetricType
  const periodic = !['FUNDING_SNAPSHOT', 'FUNDING_HISTORY'].includes(type)
  return ['PUBLIC', 'API_KEY'].includes(String(v.authentication)) && v.period === (periodic ? '5m' : 'OBSERVATION')
    && time(v.start) && time(v.end) && Date.parse(v.start) <= Date.parse(v.end)
    && Array.isArray(v.items) && v.items.length <= 500 && v.items.every(item => point(item, type))
    && v.items.every((item, index, items) => Date.parse(item.eventTime) >= Date.parse(String(v.start))
      && Date.parse(item.eventTime) <= Date.parse(String(v.end)) && (!periodic || Date.parse(item.eventTime) % 300000 === 0)
      && (index === 0 || Date.parse(item.eventTime) > Date.parse(items[index - 1].eventTime)))
    && (v.items.length === 0 ? v.latest === null : point(v.latest, type) && v.latest.eventTime === v.items.at(-1)!.eventTime
      && JSON.stringify(v.latest.values) === JSON.stringify(v.items.at(-1)!.values))
    && (periodic ? v.expectedPoints === 288 && Number.isInteger(v.missingPoints) && Number(v.missingPoints) >= 0
      && v.items.length + Number(v.missingPoints) === 288 : v.expectedPoints === null && v.missingPoints === null)
    && typeof v.stale === 'boolean' && ['UNAVAILABLE', 'STALE', 'GAPPED', 'READY'].includes(String(v.quality))
    && object(v.sync) && optionalTime(v.sync.lastAttemptAt) && optionalTime(v.sync.lastSuccessAt) && time(v.sync.nextAllowedAt)
    && Number.isInteger(v.sync.failures) && Number(v.sync.failures) >= 0 && optionalText(v.sync.errorCode) && optionalText(v.sync.error)
}
async function read(response: Response): Promise<unknown> {
  const data: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new Error(object(data) && typeof data.message === 'string' ? data.message : `后端返回 HTTP ${response.status}`)
  return data
}
export async function getMetrics(symbol: MarketSymbol, signal: AbortSignal): Promise<MetricPage> {
  const data = await read(await fetch(`/api/v1/instruments/${symbol}/metrics`, { signal, cache: 'no-store', headers: { Accept: 'application/json' } }))
  if (!object(data) || data.symbol !== symbol || typeof data.baseAsset !== 'string' || typeof data.quoteAsset !== 'string'
    || typeof data.running !== 'boolean' || !object(data.sourceGate) || !time(data.sourceGate.until) || !optionalText(data.sourceGate.error)
    || !Array.isArray(data.streams) || data.streams.length !== metricTypes.length || !data.streams.every(stream)
    || new Set(data.streams.map(s => s.type)).size !== metricTypes.length || !time(data.serverTime) || typeof data.requestId !== 'string') {
    throw new Error('市场指标响应格式不正确')
  }
  return data as unknown as MetricPage
}
export async function collectMetrics(symbol: MarketSymbol, signal: AbortSignal): Promise<void> {
  const data = await read(await fetch(`/api/v1/instruments/${symbol}/metrics/sync`, {
    method: 'POST', signal, headers: { Accept: 'application/json', 'X-AI2B-Action': 'collect-metrics' },
  }))
  if (!object(data) || data.accepted !== true || typeof data.requestId !== 'string') throw new Error('指标采集响应格式不正确')
}
/** Shift a decimal string two places without rounding through JavaScript floating point. */
export function percent(value: string | undefined): string {
  if (value === undefined || !decimal(value)) return '—'
  const negative = value.startsWith('-')
  const [whole = '0', fraction = ''] = (negative ? value.slice(1) : value).split('.')
  const padded = fraction.padEnd(2, '0')
  const integer = (whole + padded.slice(0, 2)).replace(/^0+(?=\d)/, '')
  const remainder = padded.slice(2).replace(/0+$/, '')
  return `${negative && Number(value) !== 0 ? '-' : ''}${integer}${remainder ? '.' + remainder : ''}%`
}

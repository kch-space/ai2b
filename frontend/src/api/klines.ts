import type { Instrument } from './instruments'

export type MarketSymbol = 'BTCUSDT' | 'ETHUSDT'
export interface Kline {
  openTime: string
  closeTime: string
  open: string
  high: string
  low: string
  close: string
  volume: string
  quoteVolume: string
  trades: number
  takerBuyVolume: string
  takerBuyQuoteVolume: string
  receivedAt: string
}
export interface CollectorStatus {
  outcome: 'IDLE' | 'QUEUED' | 'RUNNING' | 'SUCCESS' | 'CATCHING_UP' | 'ERROR'
  running: boolean
  lastAttemptAt: string | null
  nextAllowedAt: string
  error: string | null
  lastWriteAt: string | null
  coverageStart: string | null
  lastContiguousOpenTime: string | null
}
export interface KlinePage {
  instrument: Instrument
  interval: '1m'
  start: string
  end: string
  items: Kline[]
  expectedBars: number
  missingBars: number
  gaps: { start: string; end: string; minutes: number }[]
  stale: boolean
  quality: 'UNAVAILABLE' | 'GAPPED' | 'COMPLETE'
  collector: CollectorStatus
  serverTime: string
  requestId: string
}

function object(value: unknown): value is Record<string, unknown> {
  return typeof value === 'object' && value !== null
}
function time(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}
function decimal(value: unknown): value is string {
  return typeof value === 'string' && /^\d+(\.\d+)?$/.test(value) && Number.isFinite(Number(value))
}
function collector(value: unknown): value is CollectorStatus {
  return object(value) && ['IDLE', 'QUEUED', 'RUNNING', 'SUCCESS', 'CATCHING_UP', 'ERROR'].includes(String(value.outcome))
    && typeof value.running === 'boolean' && (value.error === null || typeof value.error === 'string')
    && time(value.nextAllowedAt)
    && ['lastAttemptAt', 'lastWriteAt', 'coverageStart', 'lastContiguousOpenTime']
      .every(key => value[key] === null || time(value[key]))
}
function candle(value: unknown): value is Kline {
  return object(value) && time(value.openTime) && time(value.closeTime) && time(value.receivedAt)
    && Date.parse(value.closeTime) - Date.parse(value.openTime) === 59999
    && Date.parse(value.receivedAt) > Date.parse(value.closeTime)
    && ['open', 'high', 'low', 'close', 'volume', 'quoteVolume', 'takerBuyVolume', 'takerBuyQuoteVolume']
      .every(key => decimal(value[key]))
    && ['open', 'high', 'low', 'close'].every(key => Number(value[key]) > 0)
    && Number.isSafeInteger(value.trades) && Number(value.trades) >= 0
}
async function read(response: Response): Promise<unknown> {
  const data: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new Error(object(data) && typeof data.message === 'string'
    ? data.message : `后端返回 HTTP ${response.status}`)
  return data
}

export async function getKlines(symbol: MarketSymbol, hours: number, signal: AbortSignal): Promise<KlinePage> {
  const data = await read(await fetch(`/api/v1/instruments/${symbol}/klines?hours=${hours}`, {
    signal, cache: 'no-store', headers: { Accept: 'application/json' },
  }))
  if (!object(data) || !object(data.instrument) || data.instrument.symbol !== symbol
    || !decimal(data.instrument.priceTickSize) || !decimal(data.instrument.quantityStepSize)
    || data.interval !== '1m' || !time(data.start) || !time(data.end) || !time(data.serverTime)
    || !Array.isArray(data.items) || data.items.length > 1440 || !data.items.every(candle)
    || data.items.some((item, index, items) => Date.parse(item.openTime) < Date.parse(String(data.start))
      || Date.parse(item.openTime) > Date.parse(String(data.end))
      || (index > 0 && Date.parse(item.openTime) <= Date.parse(items[index - 1].openTime)))
    || !Number.isInteger(data.expectedBars) || data.expectedBars !== hours * 60
    || !Number.isInteger(data.missingBars) || Number(data.missingBars) < 0
    || data.items.length + Number(data.missingBars) !== data.expectedBars
    || !Array.isArray(data.gaps) || !data.gaps.every(gap => object(gap) && time(gap.start) && time(gap.end)
      && Number.isInteger(gap.minutes) && Number(gap.minutes) > 0)
    || !['UNAVAILABLE', 'GAPPED', 'COMPLETE'].includes(String(data.quality))
    || typeof data.stale !== 'boolean' || !collector(data.collector) || typeof data.requestId !== 'string') {
    throw new Error('K 线响应格式不正确')
  }
  return data as unknown as KlinePage
}

export async function collectKlines(symbol: MarketSymbol, signal: AbortSignal): Promise<CollectorStatus> {
  const data = await read(await fetch(`/api/v1/instruments/${symbol}/klines/sync`, {
    method: 'POST', signal, headers: { Accept: 'application/json', 'X-AI2B-Action': 'collect-klines' },
  }))
  if (!object(data) || !collector(data.collector)) throw new Error('采集任务响应格式不正确')
  return data.collector
}

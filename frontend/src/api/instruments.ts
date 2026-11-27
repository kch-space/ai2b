export interface Instrument {
  id: number
  exchange: string
  marketType: string
  symbol: string
  baseAsset: string
  quoteAsset: string
  marginAsset: string
  contractType: string
  status: string
  priceTickSize: string
  quantityStepSize: string
  updatedAt: string
}

export interface SyncStatus {
  outcome: 'IDLE' | 'RUNNING' | 'SUCCESS' | 'ERROR'
  running: boolean
  lastSuccessAt: string | null
  lastAttemptAt: string | null
  nextAllowedAt: string
  error: string | null
}

export interface InstrumentPage {
  items: Instrument[]
  total: number
  page: number
  size: number
  sync: SyncStatus
  requestId: string
}

async function readJson(response: Response): Promise<unknown> {
  const data: unknown = await response.json()
  if (!response.ok) {
    const message = isObject(data) && typeof data.message === 'string' ? data.message : `HTTP ${response.status}`
    throw new Error(message)
  }
  return data
}

function isObject(value: unknown): value is Record<string, unknown> {
  return value !== null && typeof value === 'object'
}

function isTime(value: unknown): value is string {
  return typeof value === 'string' && Number.isFinite(Date.parse(value))
}

function isSync(value: unknown): value is SyncStatus {
  return isObject(value) && ['IDLE', 'RUNNING', 'SUCCESS', 'ERROR'].includes(String(value.outcome))
    && typeof value.running === 'boolean'
    && (value.lastSuccessAt === null || isTime(value.lastSuccessAt))
    && (value.lastAttemptAt === null || isTime(value.lastAttemptAt))
    && isTime(value.nextAllowedAt) && (value.error === null || typeof value.error === 'string')
}

function isInstrument(value: unknown): value is Instrument {
  return isObject(value) && Number.isSafeInteger(value.id) && Number(value.id) > 0
    && ['exchange', 'marketType', 'symbol', 'baseAsset', 'quoteAsset', 'marginAsset', 'contractType', 'status']
      .every(key => typeof value[key] === 'string' && value[key].length > 0)
    && ['priceTickSize', 'quantityStepSize'].every(key =>
      typeof value[key] === 'string' && /^\d+(\.\d+)?$/.test(value[key]) && /[1-9]/.test(value[key]))
    && isTime(value.updatedAt)
}

export async function getInstruments(keyword: string, status: string, page: number,
  signal: AbortSignal): Promise<InstrumentPage> {
  const query = new URLSearchParams({ keyword, status, page: String(page), size: '25' })
  const data = await readJson(await fetch(`/api/v1/instruments?${query}`, {
    headers: { Accept: 'application/json' }, cache: 'no-store', signal,
  }))
  if (!isObject(data) || !Array.isArray(data.items) || !data.items.every(isInstrument)
    || !Number.isSafeInteger(data.total) || Number(data.total) < 0
    || !Number.isSafeInteger(data.page) || Number(data.page) < 1
    || !Number.isSafeInteger(data.size) || Number(data.size) < 1 || Number(data.size) > 200
    || !isSync(data.sync) || typeof data.requestId !== 'string') {
    throw new Error('合约列表响应格式不正确')
  }
  return data as unknown as InstrumentPage
}

export async function syncInstruments(signal: AbortSignal): Promise<SyncStatus> {
  const data = await readJson(await fetch('/api/v1/instruments/sync', {
    method: 'POST', headers: { Accept: 'application/json', 'X-AI2B-Action': 'sync-instruments' }, signal,
  }))
  if (!isObject(data) || !isSync(data.sync)) throw new Error('合约同步响应格式不正确')
  return data.sync
}

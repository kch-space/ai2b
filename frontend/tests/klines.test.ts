import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import KlineDetail from '../src/components/KlineDetail.vue'
import KlineChart from '../src/components/KlineChart.vue'
import { getKlines } from '../src/api/klines'
import type { KlinePage } from '../src/api/klines'
vi.mock('../src/components/MarketMetrics.vue', () => ({ default: { template: '<div />' } }))

// Test-only candles. Production has no fixture fallback.
const end = Date.parse('2026-11-27T11:59:00Z')
function fixture(hours = 6, empty = false): KlinePage {
  const start = end - (hours * 60 - 1) * 60000
  const candle = (open: number) => ({ openTime: new Date(open).toISOString(), closeTime: new Date(open + 59999).toISOString(),
    open: '100.000000000000000001', high: '110', low: '90', close: '101', volume: '10', quoteVolume: '1000',
    trades: 5, takerBuyVolume: '4', takerBuyQuoteVolume: '400', receivedAt: '2026-11-27T12:00:05Z' })
  return {
    instrument: { id: 1, symbol: 'BTCUSDT', exchange: 'BINANCE', marketType: 'USD_M_FUTURES', baseAsset: 'BTC',
      quoteAsset: 'USDT', marginAsset: 'USDT', contractType: 'PERPETUAL', status: 'TRADING', priceTickSize: '0.1',
      quantityStepSize: '0.001', updatedAt: '2026-11-27T00:00:00Z' },
    interval: '1m', start: new Date(start).toISOString(), end: new Date(end).toISOString(),
    items: empty ? [] : [candle(start), candle(end)], expectedBars: hours * 60, missingBars: hours * 60 - (empty ? 0 : 2),
    gaps: [{ start: new Date(empty ? start : start + 60000).toISOString(), end: new Date(empty ? end : end - 60000).toISOString(),
      minutes: hours * 60 - (empty ? 0 : 2) }], stale: empty, quality: empty ? 'UNAVAILABLE' : 'GAPPED',
    collector: { outcome: 'SUCCESS', running: false, lastAttemptAt: '2026-11-27T12:00:05Z', nextAllowedAt: '2026-11-27T12:00:15Z',
      error: null, lastWriteAt: '2026-11-27T12:00:05Z', coverageStart: new Date(start).toISOString(),
      lastContiguousOpenTime: new Date(start).toISOString() },
    serverTime: '2026-11-27T12:00:05Z', requestId: 'test-fixture',
  }
}
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
let wrapper: VueWrapper | undefined

afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.useRealTimers() })

describe('market detail', () => {
  it('renders exact values and leaves missing minutes blank', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture())))
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    await flushPromises()
    expect(wrapper.text()).toContain('358')
    expect(wrapper.text()).toContain('存在缺口')
    expect(wrapper.text()).toContain('100.000000000000000001')
    expect(wrapper.findAll('g.candle-up, g.candle-down')).toHaveLength(2)
  })
  it('changes range and requests the selected hours', async () => {
    const fetchMock = vi.fn().mockResolvedValueOnce(response(fixture())).mockResolvedValueOnce(response(fixture(1)))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    await flushPromises()
    await wrapper.get('select').setValue('1')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('hours=1')
    expect(wrapper.text()).toContain('2 / 60')
  })
  it('shows unavailable source and never invents candles', async () => {
    const page = fixture(6, true)
    page.collector.error = '交易所返回 HTTP 451'; page.collector.outcome = 'ERROR'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('451')
    expect(wrapper.text()).toContain('暂无闭合 K 线')
    expect(wrapper.find('svg').exists()).toBe(false)
  })
  it('submits asynchronous collection and polls until complete', async () => {
    vi.useFakeTimers()
    const page = fixture()
    const running = { ...page, collector: { ...page.collector, outcome: 'RUNNING', running: true } }
    const fetchMock = vi.fn().mockResolvedValueOnce(response(page))
      .mockResolvedValueOnce(response({ collector: { ...page.collector, outcome: 'QUEUED', running: true } }, 202))
      .mockResolvedValueOnce(response(running)).mockResolvedValueOnce(response(page))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    await flushPromises()
    await wrapper.get('[data-testid="collect-klines"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: expect.objectContaining({ 'X-AI2B-Action': 'collect-klines' }) })
    expect(wrapper.get('[data-testid="collect-klines"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(3000); await flushPromises()
    expect(wrapper.text()).toContain('本次采集完成')
    expect(fetchMock).toHaveBeenCalledTimes(4)
  })
  it('retains the chart when a refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(fixture())).mockRejectedValueOnce(new Error('网络不可用')))
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    await flushPromises()
    await wrapper.get('[data-testid="refresh-klines"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('保留上次读取结果')
    expect(wrapper.find('svg').exists()).toBe(true)
  })
  it('aborts requests and stops polling on unmount', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    const fetchMock = vi.fn((_url, options: RequestInit) => { signal = options.signal as AbortSignal; return new Promise(() => {}) })
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(KlineDetail, { props: { symbol: 'BTCUSDT' } })
    wrapper.unmount(); wrapper = undefined
    expect(signal?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(20000)
    expect(fetchMock).toHaveBeenCalledTimes(1)
  })
})

describe('candle chart', () => {
  it('supports keyboard inspection of a missing minute', async () => {
    const page = fixture(1)
    wrapper = mount(KlineChart, { props: { items: page.items, start: page.start, end: page.end } })
    await wrapper.get('svg').trigger('keydown', { key: 'ArrowLeft' })
    expect(wrapper.text()).toContain('该分钟数据缺失')
    expect(wrapper.findAll('g.candle-up')).toHaveLength(2)
  })
})

describe('kline API', () => {
  it.each(['number-price', 'count-mismatch', 'unordered', 'unclosed', 'bad-status'])(
    'rejects invalid payload: %s', async kind => {
      const page = fixture()
      if (kind === 'number-price') (page.items[0] as unknown as Record<string, unknown>).open = 100
      if (kind === 'count-mismatch') page.missingBars = 0
      if (kind === 'unordered') page.items.reverse()
      if (kind === 'unclosed') page.items[1]!.receivedAt = page.items[1]!.openTime
      if (kind === 'bad-status') (page.collector as unknown as Record<string, unknown>).outcome = 'OK'
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
      await expect(getKlines('BTCUSDT', 6, new AbortController().signal)).rejects.toThrow('格式不正确')
    })
  it('shows a readable error for a non-JSON HTTP failure', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('proxy failed', { status: 502 })))
    await expect(getKlines('BTCUSDT', 6, new AbortController().signal)).rejects.toThrow('HTTP 502')
  })
})

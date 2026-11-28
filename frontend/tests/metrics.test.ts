import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import MarketMetrics from '../src/components/MarketMetrics.vue'
import MetricChart from '../src/components/MetricChart.vue'
import { getMetrics, metricTypes, percent } from '../src/api/metrics'
import type { MetricPage, MetricPoint, MetricStream } from '../src/api/metrics'

const end = Date.parse('2026-11-27T12:00:00Z'), start = end - 287 * 300000
function fixture(): MetricPage {
  return { symbol: 'BTCUSDT', baseAsset: 'BTC', quoteAsset: 'USDT', running: false, sourceGate: { until: '1970-01-01T00:00:00Z', error: null },
    serverTime: '2026-11-27T12:00:05Z', requestId: 'test-only', streams: metricTypes.map(type => {
      const five = !['FUNDING_SNAPSHOT', 'FUNDING_HISTORY'].includes(type)
      const values: Record<string, string> = type === 'OPEN_INTEREST' ? { quantity: '100.000000000000000001', notional: '6000000' }
        : type === 'FUNDING_SNAPSHOT' ? { rate: '-0.00001234', markPrice: '60000', indexPrice: '60001', nextFundingTime: '2026-11-27T16:00:00Z' }
        : type === 'FUNDING_HISTORY' ? { rate: '0.0001', markPrice: '59999' } : { longShare: '0.6', shortShare: '0.4', ratio: '1.5' }
      const point = (time: number): MetricPoint => ({ eventTime: new Date(time).toISOString(), receivedAt: '2026-11-27T12:00:05Z', values })
      const items = five ? [point(start), point(end)] : [point(end)]
      return { type, authentication: type.startsWith('TOP_') ? 'API_KEY' : 'PUBLIC', period: five ? '5m' : 'OBSERVATION',
        start: new Date(start).toISOString(), end: new Date(end).toISOString(), items, latest: items.at(-1)!,
        expectedPoints: five ? 288 : null, missingPoints: five ? 286 : null, stale: false, quality: five ? 'GAPPED' : 'READY',
        sync: { lastAttemptAt: '2026-11-27T12:00:05Z', lastSuccessAt: '2026-11-27T12:00:05Z', nextAllowedAt: '2026-11-27T12:05:05Z', failures: 0, errorCode: null, error: null },
      } satisfies MetricStream
    }) }
}
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
let wrapper: VueWrapper | undefined
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.useRealTimers() })

describe('market metrics', () => {
  it('shows precise quantity and separate funding/account/position semantics', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture())))
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); await flushPromises()
    expect(wrapper.get('[data-testid="open-interest"]').text()).toContain('100.000000000000000001 BTC')
    expect(wrapper.get('[data-testid="funding"]').text()).toContain('-0.001234%')
    expect(wrapper.get('[data-testid="GLOBAL_ACCOUNT_RATIO"]').text()).toContain('每个账户')
    expect(wrapper.get('[data-testid="TOP_POSITION_RATIO"]').text()).toContain('口径不同于账户数')
  })
  it('isolates an unavailable authenticated stream from public data', async () => {
    const page = fixture(), stream = page.streams.find(s => s.type === 'TOP_ACCOUNT_RATIO')!
    stream.items = []; stream.latest = null; stream.quality = 'UNAVAILABLE'; stream.missingPoints = 288; stream.stale = true
    stream.sync.errorCode = 'API_KEY_REQUIRED'; stream.sync.error = '该行情接口需要配置币安 API Key'
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); await flushPromises()
    expect(wrapper.get('[data-testid="TOP_ACCOUNT_RATIO"]').text()).toContain('暂无数据')
    expect(wrapper.get('[role="alert"]').text()).toContain('需要配置')
    expect(wrapper.get('[data-testid="open-interest"]').text()).toContain('6000000 USDT')
  })
  it('retains observations when refresh fails', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(fixture())).mockRejectedValueOnce(new Error('网络错误')))
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); await flushPromises()
    await wrapper.get('[data-testid="refresh-metrics"]').trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('保留上次读取')
    expect(wrapper.text()).toContain('100.000000000000000001')
  })
  it('submits collection with required header and polls running jobs', async () => {
    vi.useFakeTimers()
    const running = { ...fixture(), running: true }
    const fetch = vi.fn().mockResolvedValueOnce(response(fixture())).mockResolvedValueOnce(response({ accepted: true, requestId: 'test' },202))
      .mockResolvedValueOnce(response(running)).mockResolvedValueOnce(response(fixture()))
    vi.stubGlobal('fetch', fetch)
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); await flushPromises()
    await wrapper.get('[data-testid="collect-metrics"]').trigger('click'); await flushPromises()
    expect(fetch.mock.calls[1]?.[1]).toMatchObject({ method: 'POST', headers: expect.objectContaining({ 'X-AI2B-Action': 'collect-metrics' }) })
    expect(wrapper.text()).toContain('指标采集任务已提交')
    expect(wrapper.get('[data-testid="collect-metrics"]').attributes('disabled')).toBeDefined()
    await vi.advanceTimersByTimeAsync(3000); await flushPromises()
    expect(wrapper.get('[data-testid="collect-metrics"]').attributes('disabled')).toBeUndefined()
  })
  it('aborts pending requests and stops timers on unmount', async () => {
    vi.useFakeTimers()
    let signal: AbortSignal | undefined
    const fetch = vi.fn((_url, options: RequestInit) => { signal = options.signal as AbortSignal; return new Promise(() => {}) })
    vi.stubGlobal('fetch', fetch)
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); wrapper.unmount(); wrapper = undefined
    expect(signal?.aborted).toBe(true)
    await vi.advanceTimersByTimeAsync(60000); expect(fetch).toHaveBeenCalledTimes(1)
  })
  it('shows cooldown and stale labels instead of implying fresh data', async () => {
    const page = fixture(); page.streams[0]!.stale = true; page.streams[0]!.quality = 'STALE'
    page.sourceGate = { until: '2026-11-27T12:30:00Z', error: '交易所限流' }
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
    wrapper = mount(MarketMetrics, { props: { symbol: 'BTCUSDT' } }); await flushPromises()
    expect(wrapper.text()).toContain('数据滞后'); expect(wrapper.get('[role="alert"]').text()).toContain('下次允许采集')
  })
})
describe('metric chart and decimals', () => {
  it('does not draw a line across missing five-minute observations', () => {
    const stream = fixture().streams[0]!
    wrapper = mount(MetricChart, { props: { items: stream.items, field: 'quantity', label: 'OI', start: stream.start, end: stream.end } })
    expect(wrapper.findAll('polyline')).toHaveLength(2)
    expect(wrapper.findAll('circle')).toHaveLength(2)
    expect(wrapper.findAll('title')[0]!.text()).toContain('100.000000000000000001')
  })
  it.each([['0.0001','0.01%'],['-0.00001234','-0.001234%'],['0.6000','60%'],['1.000000000000000001','100.0000000000000001%']])('converts %s exactly', (value,result) => expect(percent(value)).toBe(result))
})
describe('metric API validation', () => {
  it.each(['duplicate-type','numeric-value','bad-count','unordered','missing-type'])( 'rejects %s', async kind => {
    const page = fixture()
    if (kind === 'duplicate-type') page.streams[1] = page.streams[0]!
    if (kind === 'numeric-value') (page.streams[0]!.items[0]!.values as Record<string,unknown>).quantity = 100
    if (kind === 'bad-count') page.streams[0]!.missingPoints = 0
    if (kind === 'unordered') page.streams[0]!.items.reverse()
    if (kind === 'missing-type') page.streams.pop()
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(page)))
    await expect(getMetrics('BTCUSDT',new AbortController().signal)).rejects.toThrow('格式不正确')
  })
})

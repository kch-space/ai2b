import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import Dashboard from '../src/components/Dashboard.vue'
import { getKlines, type KlinePage, type MarketSymbol } from '../src/api/klines'
import { getEvents } from '../src/api/alerts'
import { getInstruments } from '../src/api/instruments'
import { eventFixture, pageFixture } from './alert-fixtures'
import { defaults, savePreferences } from '../src/settings/preferences'
vi.mock('../src/api/klines', () => ({ getKlines: vi.fn() }))
vi.mock('../src/api/alerts', () => ({ getEvents: vi.fn() }))
vi.mock('../src/api/instruments', () => ({ getInstruments: vi.fn() }))
let wrapper: VueWrapper | undefined
function market(symbol: MarketSymbol): KlinePage {
  const end = Date.parse('2026-11-28T01:00:00Z'), start = end - 59 * 60000
  const items = Array.from({ length: 60 }, (_, index) => {
    const open = start + index * 60000
    return { openTime: new Date(open).toISOString(), closeTime: new Date(open + 59999).toISOString(),
      open: '100', high: '102', low: '99', close: String(100 + index / 100), volume: '10', quoteVolume: '1000',
      trades: 1, takerBuyVolume: '1', takerBuyQuoteVolume: '100', receivedAt: new Date(open + 62000).toISOString() }
  })
  return { instrument: { id: 1, symbol, baseAsset: 'BTC', quoteAsset: 'USDT', marginAsset: 'USDT', exchange: 'BINANCE', marketType: 'USD_M_FUTURES',
      contractType: 'PERPETUAL', status: 'TRADING', priceTickSize: '0.1', quantityStepSize: '0.01', updatedAt: new Date(end).toISOString() },
    interval: '1m', start: new Date(start).toISOString(), end: new Date(end).toISOString(), items, expectedBars: 60, missingBars: 0,
    gaps: [], stale: false, quality: 'COMPLETE', collector: { outcome: 'SUCCESS', running: false, lastAttemptAt: null, nextAllowedAt: new Date(end).toISOString(),
      error: null, lastWriteAt: null, coverageStart: null, lastContiguousOpenTime: null }, serverTime: new Date(end + 65000).toISOString(), requestId: 'test' }
}
beforeEach(() => {
  vi.mocked(getKlines).mockImplementation(async symbol => market(symbol))
  vi.mocked(getEvents).mockResolvedValue(pageFixture as Awaited<ReturnType<typeof getEvents>>)
  vi.mocked(getInstruments).mockResolvedValue({ items: [], total: 659, page: 1, size: 1, requestId: 'test',
    sync: { outcome: 'SUCCESS', running: false, lastSuccessAt: null, lastAttemptAt: null, nextAllowedAt: '2026-11-28T01:00:00Z', error: null } })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; localStorage.clear(); vi.clearAllMocks(); vi.useRealTimers() })
describe('compact dashboard', () => {
  it('shows only two focus cards and requests just one metadata row', async () => {
    wrapper = mount(Dashboard); await flushPromises()
    expect(wrapper.findAll('.focus-card')).toHaveLength(2)
    expect(wrapper.find('table').exists()).toBe(false)
    expect(getInstruments).toHaveBeenCalledWith('', '', 1, expect.any(AbortSignal), 1)
    expect(wrapper.text()).toContain('659')
    expect(wrapper.findAll('.sparkline')).toHaveLength(2)
    expect(wrapper.text()).toContain('+0.59%')
  })
  it('caps recent events at four without hiding the total', async () => {
    vi.mocked(getEvents).mockResolvedValue({ ...pageFixture, total: 30,
      items: Array.from({ length: 20 }, (_, i) => ({ ...eventFixture, id: String(i) })) } as Awaited<ReturnType<typeof getEvents>>)
    wrapper = mount(Dashboard); await flushPromises()
    expect(wrapper.findAll('.recent-event')).toHaveLength(4)
    expect(wrapper.text()).toContain('30')
  })
  it('does not draw fabricated trends or calculate changes across gaps', async () => {
    vi.mocked(getKlines).mockResolvedValue({ ...market('BTCUSDT'), quality: 'GAPPED', missingBars: 1 })
    wrapper = mount(Dashboard); await flushPromises()
    expect(wrapper.find('.sparkline').exists()).toBe(false)
    expect(wrapper.text()).toContain('存在缺口')
    expect(wrapper.text()).toContain('1h 涨跌暂不可用')
  })
  it('isolates failed streams and preserves last successful values on refresh', async () => {
    wrapper = mount(Dashboard); await flushPromises()
    vi.mocked(getKlines).mockImplementation(async symbol => { if (symbol === 'BTCUSDT') throw new Error('HTTP 451'); return market(symbol) })
    vi.mocked(getEvents).mockRejectedValue(new Error('预警读取失败'))
    await wrapper.get('[data-testid="refresh-dashboard"]').trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('HTTP 451（保留上次数据）')
    expect(wrapper.text()).toContain('预警读取失败（保留上次数据）')
    expect(wrapper.text()).toContain('100.59')
    expect(wrapper.findAll('.focus-card')[1]!.text()).toContain('窗口完整')
  })
  it('honors saved polling preferences and stops on unmount', async () => {
    vi.useFakeTimers(); savePreferences({ ...defaults(), refreshSeconds: 15 })
    wrapper = mount(Dashboard); await flushPromises()
    await vi.advanceTimersByTimeAsync(15000); await flushPromises()
    expect(getKlines).toHaveBeenCalledTimes(4)
    wrapper.unmount(); wrapper = undefined
    await vi.advanceTimersByTimeAsync(30000)
    expect(getKlines).toHaveBeenCalledTimes(4)
  })
  it('aborts all pending requests on unmount', () => {
    let signal: AbortSignal | undefined
    vi.mocked(getKlines).mockImplementation((_symbol, _hours, current) => { signal = current; return new Promise(() => {}) })
    wrapper = mount(Dashboard); wrapper.unmount(); wrapper = undefined
    expect(signal?.aborted).toBe(true)
  })
})

import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import InstrumentList from '../src/components/InstrumentList.vue'
import { getInstruments, syncInstruments } from '../src/api/instruments'

// Test-only metadata; never used as a live-data fallback.
const sync = { outcome: 'SUCCESS', running: false, lastSuccessAt: '2026-11-27T12:00:00Z',
  lastAttemptAt: '2026-11-27T12:00:00Z', nextAllowedAt: '2026-11-27T12:01:00Z', error: null }
const item = { id: 1, exchange: 'BINANCE', marketType: 'USD_M_FUTURES', symbol: 'BTCUSDT', baseAsset: 'BTC',
  quoteAsset: 'USDT', marginAsset: 'USDT', contractType: 'PERPETUAL', status: 'TRADING',
  priceTickSize: '0.000000000000000001', quantityStepSize: '0.001', updatedAt: '2026-11-27T12:00:00Z' }
const page = { items: [item], total: 26, page: 1, size: 25, sync, requestId: 'fixture-request' }
const response = (data: unknown, status = 200) => new Response(JSON.stringify(data), { status })
let wrapper: VueWrapper | undefined

afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.useRealTimers() })

describe('instrument list', () => {
  it('renders exact decimal strings, filters and pagination', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response(page)))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(InstrumentList)
    await flushPromises()
    expect(wrapper.text()).toContain('0.000000000000000001')
    expect(wrapper.text()).toContain('交易中')
    await wrapper.get('input').setValue('eth')
    await wrapper.get('select').setValue('TRADING')
    await wrapper.get('form').trigger('submit')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('keyword=eth&status=TRADING&page=1')
    await wrapper.get('[aria-label="下一页"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('page=2')
  })

  it('shows unsynced empty state without invented data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...page, items: [], total: 0,
      sync: { ...sync, outcome: 'IDLE', lastSuccessAt: null, lastAttemptAt: null } })))
    wrapper = mount(InstrumentList)
    await flushPromises()
    expect(wrapper.text()).toContain('暂无合约数据')
    expect(wrapper.find('table').exists()).toBe(false)
  })

  it('returns to page one when a filter changes before pagination', async () => {
    const fetchMock = vi.fn().mockImplementation(() => Promise.resolve(response(page)))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(InstrumentList)
    await flushPromises()
    await wrapper.get('input').setValue('BTC')
    await wrapper.get('[aria-label="下一页"]').trigger('click')
    await flushPromises()
    expect(fetchMock.mock.calls.at(-1)?.[0]).toContain('keyword=BTC&status=&page=1')
  })

  it('refreshes after sync and shows upstream error while retaining stored rows', async () => {
    const fetchMock = vi.fn()
      .mockResolvedValueOnce(response(page))
      .mockResolvedValueOnce(response({ message: '交易所返回 HTTP 451' }, 503))
      .mockResolvedValueOnce(response({ ...page, sync: { ...sync, outcome: 'ERROR', error: '交易所返回 HTTP 451' } }))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(InstrumentList)
    await flushPromises()
    await wrapper.get('[data-testid="sync"]').trigger('click')
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('451')
    expect(wrapper.text()).toContain('BTCUSDT')
    expect(fetchMock.mock.calls[1]?.[1]).toMatchObject({ method: 'POST',
      headers: expect.objectContaining({ 'X-AI2B-Action': 'sync-instruments' }) })
  })

  it('shows successful sync and loads updated rows', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValueOnce(response(page))
      .mockResolvedValueOnce(response({ sync, requestId: 'sync-fixture' }))
      .mockResolvedValueOnce(response({ ...page, items: [{ ...item, symbol: 'ETHUSDT' }] })))
    wrapper = mount(InstrumentList)
    await flushPromises()
    await wrapper.get('[data-testid="sync"]').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('同步成功')
    expect(wrapper.text()).toContain('ETHUSDT')
  })

  it('aborts pending requests on unmount', () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => {
      signal = options.signal as AbortSignal
      return new Promise(() => {})
    }))
    wrapper = mount(InstrumentList)
    wrapper.unmount()
    wrapper = undefined
    expect(signal?.aborted).toBe(true)
  })

  it('turns a slow list into a retryable timeout', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    wrapper = mount(InstrumentList)
    await vi.advanceTimersByTimeAsync(10000)
    expect(wrapper.get('[role="alert"]').text()).toContain('请求超时')
    expect(wrapper.get('button[type="submit"]').attributes('disabled')).toBeUndefined()
  })
})

describe('instrument API contract', () => {
  it.each([{ ...page, items: [{ ...item, priceTickSize: 0.1 }] }, { ...page, sync: {} },
    { ...page, total: -1 }, { ...page, items: [{ ...item, updatedAt: 'bad-date' }] }])(
    'rejects invalid list responses', async data => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(data)))
      await expect(getInstruments('', '', 1, new AbortController().signal)).rejects.toThrow('格式不正确')
    })
  it('rejects malformed sync responses', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ sync: {} })))
    await expect(syncInstruments(new AbortController().signal)).rejects.toThrow('格式不正确')
  })
})

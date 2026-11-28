import { flushPromises, mount } from '@vue/test-utils'
import type { VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import App from '../src/App.vue'

// Simulated application metadata only; never a production market-data source.
const fixture = {
  status: 'UP', service: 'test-backend',
  timestamp: '2026-11-25T00:00:00Z', requestId: 'test-request',
}
let wrapper: VueWrapper | undefined

afterEach(() => {
  wrapper?.unmount()
  wrapper = undefined
  window.history.replaceState(null, '', '/')
  vi.unstubAllGlobals()
  vi.useRealTimers()
})

describe('application home', () => {
  it('shows loading without inventing a connected state', () => {
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true } } })
    expect(wrapper.text()).toContain('正在检查后端服务')
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).not.toContain('后端服务已连接')
  })

  it('shows successful metadata and implemented market and mock-alert capabilities', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture))))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true } } })
    await flushPromises()
    expect(wrapper.text()).toContain('后端服务已连接')
    expect(wrapper.text()).toContain('test-backend')
    expect(wrapper.text()).toContain('test-request')
    expect(wrapper.findComponent({ name: 'Dashboard' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'InstrumentList' }).exists()).toBe(false)
    expect(wrapper.find('nav a[href="#/alerts"]').exists()).toBe(true)
    expect(wrapper.text()).toContain('不代表行情采集、数据库或通知渠道已连接')
  })

  it('shows failure and can retry successfully', async () => {
    const fetchMock = vi.fn()
      .mockRejectedValueOnce(new Error('网络不可用'))
      .mockResolvedValueOnce(new Response(JSON.stringify(fixture)))
    vi.stubGlobal('fetch', fetchMock)
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true } } })
    await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('网络不可用')
    await wrapper.get('button').trigger('click')
    await flushPromises()
    expect(wrapper.text()).toContain('后端服务已连接')
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
    expect(fetchMock).toHaveBeenCalledTimes(2)
  })

  it('turns a timed-out request into a retryable error', async () => {
    vi.useFakeTimers()
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => new Promise((_resolve, reject) => {
      options.signal?.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true } } })
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('[role="alert"]').text()).toContain('请求超时')
    expect(wrapper.get('button').attributes('disabled')).toBeUndefined()
  })

  it('cancels the pending request on unmount', () => {
    let signal: AbortSignal | undefined
    vi.stubGlobal('fetch', vi.fn((_url, options: RequestInit) => {
      signal = options.signal as AbortSignal
      return new Promise(() => {})
    }))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true } } })
    wrapper.unmount()
    wrapper = undefined
    expect(signal?.aborted).toBe(true)
  })
  it('opens supported detail routes and returns to the overview', async () => {
    window.history.replaceState(null, '', '/#/market/BTCUSDT')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture))))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true, KlineDetail: true } } })
    expect(wrapper.findComponent({ name: 'KlineDetail' }).props('symbol')).toBe('BTCUSDT')
    window.history.replaceState(null, '', '/#/')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'Dashboard' }).exists()).toBe(true)
  })

  it('opens the alert center route', async () => {
    window.history.replaceState(null, '', '/#/alerts')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture))))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, SettingsPage: true, InstrumentList: true, KlineDetail: true, AlertCenter: true } } })
    expect(wrapper.findComponent({ name: 'AlertCenter' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'InstrumentList' }).exists()).toBe(false)
    window.history.replaceState(null, '', '/#/')
    window.dispatchEvent(new HashChangeEvent('hashchange'))
    await flushPromises()
    expect(wrapper.findComponent({ name: 'Dashboard' }).exists()).toBe(true)
  })

  it('keeps the full market on a separate route and highlights navigation', async () => {
    window.history.replaceState(null, '', '/#/markets')
    vi.stubGlobal('fetch', vi.fn().mockImplementation(() => Promise.resolve(new Response(JSON.stringify(fixture)))))
    wrapper = mount(App, { global: { stubs: { Dashboard: true, InstrumentList: true } } })
    expect(wrapper.findComponent({ name: 'InstrumentList' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'Dashboard' }).exists()).toBe(false)
    expect(wrapper.get('nav a[href="#/markets"]').attributes('aria-current')).toBe('page')
  })
  it('opens settings subroutes without loading a full market list', () => {
    window.history.replaceState(null, '', '/#/settings/ai')
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    wrapper = mount(App, { global: { stubs: { SettingsPage: true, InstrumentList: true, Dashboard: true } } })
    expect(wrapper.findComponent({ name: 'SettingsPage' }).exists()).toBe(true)
    expect(wrapper.findComponent({ name: 'InstrumentList' }).exists()).toBe(false)
    expect(wrapper.get('nav a[href="#/settings"]').attributes('aria-current')).toBe('page')
  })
  it('shows an honest not-found page for unsupported routes', () => {
    window.history.replaceState(null, '', '/#/unknown')
    vi.stubGlobal('fetch', vi.fn(() => new Promise(() => {})))
    wrapper = mount(App)
    expect(wrapper.text()).toContain('页面不存在')
    expect(wrapper.findComponent({ name: 'Dashboard' }).exists()).toBe(false)
  })

})

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import AlertCenter from '../src/components/AlertCenter.vue'
vi.mock('../src/components/EventAI.vue', () => ({ default: { template: '<div />' } }))
import { detailFixture, pageFixture, response, rulesFixture } from './alert-fixtures'
let wrapper: VueWrapper | undefined
function normalFetch() {
  return vi.fn((url: string) => Promise.resolve(response(url.endsWith('/rules') ? rulesFixture
    : url.includes('/events/') ? detailFixture : pageFixture)))
}
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.unstubAllGlobals(); vi.useRealTimers() })
describe('alert center', () => {
  it('renders rule status, precise display and explicitly simulated notifications', async () => {
    vi.stubGlobal('fetch', normalFetch()); wrapper = mount(AlertCenter); await flushPromises()
    expect(wrapper.text()).toContain('MOCK 模拟通知')
    expect(wrapper.text()).toContain('历史补录 / 实时预热中')
    expect(wrapper.text()).toContain('5.00%')
    expect(wrapper.text()).toContain('-11.00%')
    await wrapper.findAll('button').find(b => b.text() === '查看详情')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('首次触发')
    expect(wrapper.text()).toContain('已模拟（未真实发送）')
    expect(wrapper.text()).toContain('mock:n1')
    await wrapper.findAll('button').find(b => b.text() === '关闭详情')!.trigger('click')
    expect(wrapper.find('[aria-label="事件详情"]').exists()).toBe(false)
  })
  it('filters and paginates, resetting page on a new filter', async () => {
    const fetch = vi.fn((url: string) => Promise.resolve(response(url.endsWith('/rules') ? rulesFixture : { ...pageFixture, total: 25 })))
    vi.stubGlobal('fetch', fetch); wrapper = mount(AlertCenter); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '下一页')!.trigger('click'); await flushPromises()
    expect(fetch).toHaveBeenCalledWith(expect.stringContaining('page=2'), expect.anything())
    await wrapper.get('select').setValue('RESOLVED'); await flushPromises()
    expect(fetch).toHaveBeenCalledWith('/api/v1/alerts/events?page=1&pageSize=20&status=RESOLVED', expect.anything())
  })
  it('preserves last successful list on refresh failure and permits retry', async () => {
    const fetch = normalFetch(); vi.stubGlobal('fetch', fetch); wrapper = mount(AlertCenter); await flushPromises()
    fetch.mockImplementation(() => Promise.reject(new Error('网络不可用')))
    await wrapper.findAll('button').find(b => b.text() === '刷新')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('保留上次成功数据')
    expect(wrapper.text()).toContain('BTCUSDT')
    fetch.mockImplementation((url: string) => Promise.resolve(response(url.endsWith('/rules') ? rulesFixture : pageFixture)))
    await wrapper.findAll('button').find(b => b.text() === '刷新')!.trigger('click'); await flushPromises()
    expect(wrapper.find('[role="alert"]').exists()).toBe(false)
  })
  it('renders empty and disabled states without creating synthetic events', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(response(url.endsWith('/rules')
      ? { ...rulesFixture, enabled: false } : { ...pageFixture, items: [], total: 0 }))))
    wrapper = mount(AlertCenter); await flushPromises()
    expect(wrapper.text()).toContain('暂无事件')
    expect(wrapper.text()).toContain('预警检测已停用')
    expect(wrapper.find('tbody').exists()).toBe(false)
  })
  it('cancels timed-out and unmounted requests', async () => {
    vi.useFakeTimers()
    const signals: AbortSignal[] = []
    vi.stubGlobal('fetch', vi.fn((_url: string, options: RequestInit) => new Promise((_resolve, reject) => {
      const signal = options.signal as AbortSignal; signals.push(signal)
      signal.addEventListener('abort', () => reject(new DOMException('Aborted', 'AbortError')))
    })))
    wrapper = mount(AlertCenter)
    await vi.advanceTimersByTimeAsync(5000)
    expect(wrapper.get('[role="alert"]').text()).toContain('请求超时')
    await wrapper.get('button').trigger('click')
    wrapper.unmount(); wrapper = undefined
    expect(signals.every(s => s.aborted)).toBe(true)
  })
  it('shows a detail error without discarding list', async () => {
    vi.stubGlobal('fetch', vi.fn((url: string) => Promise.resolve(url.includes('/events/')
      ? response({ message: '事件不存在' }, 404) : response(url.endsWith('/rules') ? rulesFixture : pageFixture))))
    wrapper = mount(AlertCenter); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '查看详情')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('事件不存在')
    expect(wrapper.find('tbody').text()).toContain('BTCUSDT')
  })
})

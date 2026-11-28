import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, describe, expect, it, vi } from 'vitest'
import SettingsPage from '../src/components/SettingsPage.vue'
import { STORAGE_KEY } from '../src/settings/preferences'
let wrapper: VueWrapper | undefined
function route(key: string) {
  window.history.replaceState(null, '', `/#/settings/${key}`)
  window.dispatchEvent(new HashChangeEvent('hashchange'))
}
afterEach(() => { wrapper?.unmount(); wrapper = undefined; window.history.replaceState(null, '', '/'); localStorage.clear(); vi.unstubAllGlobals(); vi.restoreAllMocks() })
describe('settings page', () => {
  it('saves display preferences locally without making requests', async () => {
    const fetch = vi.fn(); vi.stubGlobal('fetch', fetch)
    route('display'); wrapper = mount(SettingsPage)
    await wrapper.get('#refresh-seconds').setValue('15')
    await wrapper.get('#market-size').setValue('25')
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(JSON.parse(localStorage.getItem(STORAGE_KEY)!)).toMatchObject({ refreshSeconds: 15, marketPageSize: 25 })
    expect(wrapper.text()).toContain('返回概览或重新进入合约市场后生效')
    expect(fetch).not.toHaveBeenCalled()
  })
  it('routes integration settings to server-backed panels', async () => {
    route('telegram'); wrapper = mount(SettingsPage, { global: { stubs: { IntegrationSettings: true } } })
    expect(wrapper.findComponent({ name: 'IntegrationSettings' }).props('kind')).toBe('telegram')
    route('ai'); await flushPromises()
    expect(wrapper.findComponent({ name: 'IntegrationSettings' }).props('kind')).toBe('ai')
    expect(wrapper.find('form').exists()).toBe(false)
  })
  it('reports storage failures rather than pretending saved', async () => {
    route('display'); wrapper = mount(SettingsPage)
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('存储被禁用') })
    await wrapper.get('form').trigger('submit'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('存储被禁用')
    expect(wrapper.text()).not.toContain('显示偏好已保存')
  })
  it('shows read-only defaults, not fake backend configuration controls', () => {
    route('system'); wrapper = mount(SettingsPage)
    expect(wrapper.text()).toContain('不是实时后端配置查询')
    expect(wrapper.text()).toContain('认证尚未实现')
    expect(wrapper.find('form').exists()).toBe(false)
  })

  it('lets the user clear local drafts without changing server configuration', async () => {
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, refreshSeconds: 15, marketPageSize: 25 }))
    route('display'); wrapper = mount(SettingsPage)
    await wrapper.get('.clear-drafts').trigger('click'); await flushPromises()
    expect(localStorage.getItem(STORAGE_KEY)).toBeNull()
    expect((wrapper.get('#refresh-seconds').element as HTMLSelectElement).value).toBe('30')
    expect(wrapper.text()).toContain('未修改后端')
  })

})

import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import IntegrationSettings from '../src/components/IntegrationSettings.vue'
import { getIntegrations, saveIntegration, testIntegration } from '../src/api/integrations'
import { overviewFixture } from './integration-fixtures'
vi.mock('../src/api/integrations', () => ({ getIntegrations: vi.fn(), saveIntegration: vi.fn(), testIntegration: vi.fn() }))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.mocked(getIntegrations).mockResolvedValue(overviewFixture as Awaited<ReturnType<typeof getIntegrations>>)
  vi.mocked(saveIntegration).mockResolvedValue({ ...overviewFixture.telegram, version: 2 } as Awaited<ReturnType<typeof saveIntegration>>)
  vi.mocked(testIntegration).mockResolvedValue({ success: true, message: 'provider accepted', messageId: 'telegram:42' })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; localStorage.clear(); vi.clearAllMocks(); vi.useRealTimers() })
describe('server-backed integration settings', () => {
  it('loads masked configuration without filling or persisting secrets', async () => {
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    expect(wrapper.text()).toContain('服务端已加密保存')
    expect((wrapper.get('#telegram-token').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').find(b => b.text() === '发送测试消息')!.attributes('disabled')).toBeUndefined()
    expect(localStorage.length).toBe(0)
  })
  it('requires confirmation before submitting a new encrypted server configuration', async () => {
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.get('#telegram-token').setValue('123456789:abcdefghijklmnopqrstuvw')
    await wrapper.get('[aria-label="启用真实 Telegram 预警"]').setValue(true)
    await wrapper.get('form').trigger('submit')
    expect(saveIntegration).not.toHaveBeenCalled()
    expect(wrapper.get('[role="dialog"]').text()).toContain('后续新事件会发往 Chat ID -1001')
    await wrapper.findAll('button').find(b => b.text() === '确认保存')!.trigger('click'); await flushPromises()
    expect(saveIntegration).toHaveBeenCalledWith('telegram', expect.objectContaining({ version: 1, enabled: true, secret: '123456789:abcdefghijklmnopqrstuvw' }), expect.any(AbortSignal))
    expect((wrapper.get('#telegram-token').element as HTMLInputElement).value).toBe('')
    expect(localStorage.length).toBe(0)
    expect(wrapper.text()).toContain('服务端配置已保存')
  })
  it('cancels real send confirmation without making any request', async () => {
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '发送测试消息')!.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('真实发送一条测试消息')
    await wrapper.findAll('button').find(b => b.text() === '取消')!.trigger('click')
    expect(testIntegration).not.toHaveBeenCalled()
    expect(wrapper.find('[role="dialog"]').exists()).toBe(false)
  })
  it('uses the saved version for real tests and disables tests after edits', async () => {
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '连接机器人')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(testIntegration).toHaveBeenCalledWith('telegram', 'connect', 1, expect.any(AbortSignal))
    expect(wrapper.text()).toContain('provider accepted')
    await wrapper.get('#chat-id').setValue('-1002')
    expect(wrapper.findAll('button').find(b => b.text() === '发送测试消息')!.attributes('disabled')).toBeDefined()
  })
  it('shows AI target and billing confirmation then displays plain text replies', async () => {
    vi.mocked(testIntegration).mockResolvedValue({ success: true, message: '模型返回', reply: '<script>not executable</script>' })
    wrapper = mount(IntegrationSettings, { props: { kind: 'ai' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '测试模型回复')!.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('可能产生服务商费用')
    expect(testIntegration).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.get('pre').text()).toBe('<script>not executable</script>')
    expect(wrapper.find('script').exists()).toBe(false)
  })
  it('retains failure messages and never claims save success', async () => {
    vi.mocked(saveIntegration).mockRejectedValue(new Error('配置已变更'))
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.get('form').trigger('submit')
    await wrapper.findAll('button').find(b => b.text() === '确认保存')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('配置已变更')
    expect(wrapper.text()).not.toContain('服务端配置已保存')
  })
  it('saves a Token before a Chat ID without enabling or contacting Telegram', async () => {
    const initial = { ...overviewFixture, telegram: { ...overviewFixture.telegram, secretConfigured: false,
      settings: { ...overviewFixture.telegram.settings, chatId: '' } } }
    vi.mocked(getIntegrations).mockResolvedValue(initial as Awaited<ReturnType<typeof getIntegrations>>)
    vi.mocked(saveIntegration).mockResolvedValue({ ...initial.telegram, kind: 'TELEGRAM', secretConfigured: true, version: 2 })
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    expect(wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.attributes('disabled')).toBeDefined()
    await wrapper.get('#telegram-token').setValue('123456789:abcdefghijklmnopqrstuvw')
    await wrapper.get('[data-testid="check-telegram"]').trigger('click')
    expect(wrapper.text()).toContain('本地格式校验通过')
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="dialog"]').text()).toContain('尚未绑定')
    await wrapper.findAll('button').find(b => b.text() === '确认保存')!.trigger('click'); await flushPromises()
    expect(saveIntegration).toHaveBeenCalledWith('telegram', expect.objectContaining({ enabled: false,
      settings: expect.objectContaining({ chatId: '' }), secret: '123456789:abcdefghijklmnopqrstuvw' }), expect.any(AbortSignal))
    expect(testIntegration).not.toHaveBeenCalled()
    expect((wrapper.get('#telegram-token').element as HTMLInputElement).value).toBe('')
    expect(wrapper.findAll('button').find(b => b.text() === '发送测试消息')!.attributes('disabled')).toBeDefined()
  })
  it('requires confirmation to discover and an explicit candidate choice then save', async () => {
    vi.mocked(testIntegration).mockResolvedValue({ success: true, message: '请选择接收人', botUsername: 'test_bot', chats: [
      { chatId: '42', type: 'private', title: '<script>Alice</script>', username: 'alice_user', lastMessageAt: '2026-11-28T01:00:00Z' },
      { chatId: '-2002', type: 'supergroup', title: 'Research group', username: '', lastMessageAt: '2026-11-28T00:00:00Z' },
    ] })
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('getUpdates')
    expect(wrapper.get('[role="dialog"]').text()).toContain('不会确认消费更新')
    expect(testIntegration).not.toHaveBeenCalled()
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(testIntegration).toHaveBeenCalledWith('telegram', 'chats', 1, expect.any(AbortSignal))
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(2)
    expect(wrapper.find('script').exists()).toBe(false)
    expect(wrapper.get('a[href="https://t.me/test_bot"]').attributes('rel')).toContain('noopener')
    expect((wrapper.get('#chat-id').element as HTMLInputElement).value).toBe('-1001')
    expect(saveIntegration).not.toHaveBeenCalled()
    await wrapper.findAll('button').filter(b => b.text() === '使用此 Chat ID')[1]!.trigger('click'); await flushPromises()
    expect((wrapper.get('#chat-id').element as HTMLInputElement).value).toBe('-2002')
    expect((wrapper.get('[aria-label="启用真实 Telegram 预警"]').element as HTMLInputElement).checked).toBe(false)
    expect(wrapper.text()).toContain('请核对后保存')
    expect(saveIntegration).not.toHaveBeenCalled()
    expect(wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.attributes('disabled')).toBeDefined()
    await wrapper.get('form').trigger('submit')
    expect(wrapper.get('[role="dialog"]').text()).toContain('-2002')
    await wrapper.findAll('button').find(b => b.text() === '确认保存')!.trigger('click'); await flushPromises()
    expect(saveIntegration).toHaveBeenCalledWith('telegram', expect.objectContaining({ enabled: false,
      settings: expect.objectContaining({ chatId: '-2002' }) }), expect.any(AbortSignal))
    expect(localStorage.length).toBe(0)
  })
  it('keeps Chat ID unchanged when no /start was found', async () => {
    vi.mocked(testIntegration).mockResolvedValue({ success: true, message: '未找到待处理的 /start，请重新发送或手动填写', botUsername: 'test_bot', chats: [] })
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.text()).toContain('未找到待处理的 /start')
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(0)
    expect((wrapper.get('#chat-id').element as HTMLInputElement).value).toBe('-1001')
    expect(saveIntegration).not.toHaveBeenCalled()
  })
  it('shows polling/webhook errors without saving or sending', async () => {
    vi.mocked(testIntegration).mockRejectedValue(new Error('Telegram 更新读取冲突：请检查已有 webhook'))
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('webhook')
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(0)
    expect(saveIntegration).not.toHaveBeenCalled()
    expect(testIntegration).toHaveBeenCalledTimes(1)
  })
  it('discards discovery results when the config changed or the form is edited', async () => {
    const discovery = { success: true, message: '请选择', botUsername: 'test_bot', chats: [
      { chatId: '42', type: 'private' as const, title: 'Alice', username: '', lastMessageAt: '2026-11-28T01:00:00Z' },
    ] }
    vi.mocked(testIntegration).mockResolvedValue(discovery)
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    vi.mocked(getIntegrations).mockResolvedValue({ ...overviewFixture, telegram: { ...overviewFixture.telegram, version: 2 } } as Awaited<ReturnType<typeof getIntegrations>>)
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('配置已变更')
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(0)
    await wrapper.findAll('button').find(b => b.text() === '刷新配置')!.trigger('click'); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(1)
    await wrapper.get('#telegram-token').setValue('replacement-token')
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(0)
    expect(saveIntegration).not.toHaveBeenCalled()
  })
  it('clears candidates if the saved-version refresh fails after discovery', async () => {
    vi.mocked(testIntegration).mockResolvedValue({ success: true, message: '请选择', botUsername: 'test_bot', chats: [
      { chatId: '42', type: 'private', title: 'Alice', username: '', lastMessageAt: '2026-11-28T01:00:00Z' },
    ] })
    wrapper = mount(IntegrationSettings, { props: { kind: 'telegram' } }); await flushPromises()
    vi.mocked(getIntegrations).mockRejectedValue(new Error('无法核对保存版本'))
    await wrapper.findAll('button').find(b => b.text() === '从 /start 获取 Chat ID')!.trigger('click')
    await wrapper.findAll('button').find(b => b.text() === '确认执行')!.trigger('click'); await flushPromises()
    expect(wrapper.get('[role="alert"]').text()).toContain('无法核对保存版本')
    expect(wrapper.findAll('.telegram-chat-candidate')).toHaveLength(0)
    expect(saveIntegration).not.toHaveBeenCalled()
  })
  it('aborts pending server reads on unmount', () => {
    let signal: AbortSignal | undefined
    vi.mocked(getIntegrations).mockImplementation(current => { signal = current; return new Promise(() => {}) })
    wrapper = mount(IntegrationSettings, { props: { kind: 'ai' } }); wrapper.unmount(); wrapper = undefined
    expect(signal?.aborted).toBe(true)
  })
})

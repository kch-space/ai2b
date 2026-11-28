import { afterEach, describe, expect, it, vi } from 'vitest'
import { getIntegrations, saveIntegration, testIntegration, getReports } from '../src/api/integrations'
import { overviewFixture } from './integration-fixtures'
const response = (v: unknown, status = 200) => new Response(JSON.stringify(v), { status })
afterEach(() => vi.unstubAllGlobals())
describe('integration API', () => {
  it('reads masked configuration and enforces response shape', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(overviewFixture)))
    expect((await getIntegrations(new AbortController().signal)).telegram.secretConfigured).toBe(true)
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...overviewFixture, ai: {} })))
    await expect(getIntegrations(new AbortController().signal)).rejects.toThrow('格式不正确')
  })
  it('sends explicit admin headers and never persists the secret', async () => {
    const fetch = vi.fn().mockResolvedValue(response(overviewFixture.ai)); vi.stubGlobal('fetch', fetch)
    await saveIntegration('ai', { version: 1, enabled: false, settings: overviewFixture.ai.settings, secret: 'test-key', clearSecret: false }, new AbortController().signal)
    expect(fetch).toHaveBeenCalledWith('/api/v1/integrations/ai', expect.objectContaining({ method: 'PUT', headers: expect.objectContaining({ 'X-AI2B-Action': 'integration-admin', 'Content-Type': 'application/json' }), body: expect.stringContaining('test-key') }))
  })
  it('confirms provider tests and event analysis explicitly', async () => {
    const fetch = vi.fn().mockResolvedValue(response({ success: true, message: '生成成功', reply: 'test report' })); vi.stubGlobal('fetch', fetch)
    await testIntegration('ai', 'analyze', 1, new AbortController().signal, 'event-id')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ confirmed: true, version: 1, eventId: 'event-id' })
  })
  it('validates discovery candidates and confirms the version without including the Token', async () => {
    const chat = { chatId: '-1001234', type: 'supergroup', title: 'Research', username: '', lastMessageAt: '2026-11-28T01:00:00Z' }
    const fetch = vi.fn().mockResolvedValue(response({ success: true, message: '请选择', botUsername: 'test_bot', chats: [chat] }))
    vi.stubGlobal('fetch', fetch)
    expect((await testIntegration('telegram', 'chats', 7, new AbortController().signal)).chats).toEqual([chat])
    expect(fetch.mock.calls[0]![0]).toBe('/api/v1/integrations/telegram/test/chats')
    expect(JSON.parse(fetch.mock.calls[0]![1].body)).toEqual({ confirmed: true, version: 7 })
    for (const malformed of [undefined, {}, [{ ...chat, chatId: 123 }], [{ ...chat, chatId: '0' }], [{ ...chat, type: 'channel' }], [{ ...chat, lastMessageAt: 'invalid' }], [{ ...chat, username: '<script>' }]]) {
      fetch.mockResolvedValue(response({ success: true, message: '请选择', botUsername: 'test_bot', chats: malformed }))
      await expect(testIntegration('telegram', 'chats', 7, new AbortController().signal)).rejects.toThrow('会话响应格式不正确')
    }
    fetch.mockResolvedValue(response({ success: true, message: '请选择', botUsername: '../unsafe', chats: [chat] }))
    await expect(testIntegration('telegram', 'chats', 7, new AbortController().signal)).rejects.toThrow('身份响应格式不正确')
  })
  it('propagates server errors and validates reports', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: '只允许本机' }, 403)))
    await expect(getIntegrations(new AbortController().signal)).rejects.toThrow('只允许本机')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ items: [{ id: 'r', model: 'm', text: 'report', createdAt: '2026-11-28T01:00:00Z' }] })))
    expect((await getReports('event', new AbortController().signal))[0]?.text).toBe('report')
  })
})

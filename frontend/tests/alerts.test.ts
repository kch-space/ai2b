import { afterEach, describe, expect, it, vi } from 'vitest'
import { getEvent, getEvents, getRules } from '../src/api/alerts'
import { eventFixture, pageFixture, rulesFixture, detailFixture, response } from './alert-fixtures'
afterEach(() => vi.unstubAllGlobals())
describe('alert API validation', () => {
  it('keeps precise strings and sends bounded page filters', async () => {
    const fetch = vi.fn().mockResolvedValue(response(pageFixture)); vi.stubGlobal('fetch', fetch)
    const signal = new AbortController().signal
    expect((await getEvents(2, 'ACTIVE', signal)).items[0]?.evidence.changeRatio).toBe('-0.11')
    expect(fetch).toHaveBeenCalledWith('/api/v1/alerts/events?page=2&pageSize=20&status=ACTIVE', expect.objectContaining({ signal, cache: 'no-store' }))
  })
  it('rejects fake real-delivery lists, malformed evidence and unknown statuses', async () => {
    for (const fixture of [
      { ...pageFixture, simulation: 'fake' }, { ...pageFixture, channel: 'TELEGRAM' },
      { ...pageFixture, items: [{ ...eventFixture, evidence: { changeRatio: 0.11 } }] },
      { ...pageFixture, items: [{ ...eventFixture, status: 'DELETED' }] }, { ...pageFixture, serverTime: 'invalid' },
    ]) {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(fixture)))
      await expect(getEvents(1, 'ALL', new AbortController().signal)).rejects.toThrow('响应格式不正确')
    }
  })
  it('validates rules and rejects invalid data', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(rulesFixture)))
    expect((await getRules(new AbortController().signal)).items[0]?.trigger_ratio).toBe('0.05')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...rulesFixture, states: [{ outcome: 'MATCH' }] })))
    await expect(getRules(new AbortController().signal)).rejects.toThrow('规则响应格式不正确')
  })
  it('validates mock-only detail and reports HTTP errors', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response(detailFixture)))
    expect((await getEvent(eventFixture.id, new AbortController().signal)).notifications[0]?.status).toBe('SIMULATED')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ ...detailFixture, notifications: [{ channel: 'REAL' }] })))
    await expect(getEvent(eventFixture.id, new AbortController().signal)).rejects.toThrow('详情响应格式不正确')
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(response({ message: '事件不存在' }, 404)))
    await expect(getEvent(eventFixture.id, new AbortController().signal)).rejects.toThrow('事件不存在')
  })
})

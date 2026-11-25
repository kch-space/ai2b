import { afterEach, describe, expect, it, vi } from 'vitest'
import { getHealth } from '../src/api/health'

const fixture = {
  status: 'UP', service: 'test-backend',
  timestamp: '2026-11-25T00:00:00Z', requestId: 'test-request',
}

afterEach(() => vi.unstubAllGlobals())

describe('health API', () => {
  it('uses the relative API URL and caller cancellation signal', async () => {
    const fetchMock = vi.fn().mockResolvedValue(new Response(JSON.stringify(fixture)))
    vi.stubGlobal('fetch', fetchMock)
    const signal = new AbortController().signal
    await expect(getHealth(signal)).resolves.toEqual(fixture)
    expect(fetchMock).toHaveBeenCalledWith('/api/v1/health', expect.objectContaining({ signal }))
  })

  it('rejects HTTP failures', async () => {
    vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response('', { status: 503 })))
    await expect(getHealth(new AbortController().signal)).rejects.toThrow('HTTP 503')
  })

  it.each([{}, { ...fixture, status: 'DOWN' }, { ...fixture, timestamp: 'invalid' }, null])(
    'rejects an invalid response: %j', async (data) => {
      vi.stubGlobal('fetch', vi.fn().mockResolvedValue(new Response(JSON.stringify(data))))
      await expect(getHealth(new AbortController().signal)).rejects.toThrow('响应格式不正确')
    },
  )
})

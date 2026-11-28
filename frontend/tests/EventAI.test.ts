import { flushPromises, mount, type VueWrapper } from '@vue/test-utils'
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest'
import EventAI from '../src/components/EventAI.vue'
import { getIntegrations, getReports, testIntegration } from '../src/api/integrations'
import { overviewFixture } from './integration-fixtures'
vi.mock('../src/api/integrations', () => ({ getIntegrations: vi.fn(), getReports: vi.fn(), testIntegration: vi.fn() }))
let wrapper: VueWrapper | undefined
beforeEach(() => {
  vi.mocked(getIntegrations).mockResolvedValue({ ...overviewFixture, ai: { ...overviewFixture.ai, enabled: true } } as Awaited<ReturnType<typeof getIntegrations>>)
  vi.mocked(getReports).mockResolvedValue([])
  vi.mocked(testIntegration).mockResolvedValue({ success: true, message: '已生成', reply: 'report' })
})
afterEach(() => { wrapper?.unmount(); wrapper = undefined; vi.clearAllMocks() })
describe('AI event research', () => {
  it('shows the target and requires explicit confirmation before sending evidence', async () => {
    wrapper = mount(EventAI, { props: { eventId: 'event-1' } }); await flushPromises()
    await wrapper.findAll('button').find(b => b.text() === '分析本事件')!.trigger('click')
    expect(wrapper.get('[role="dialog"]').text()).toContain('test-model')
    expect(wrapper.get('[role="dialog"]').text()).toContain('event-1')
    expect(testIntegration).not.toHaveBeenCalled()
    vi.mocked(getReports).mockResolvedValue([{ id: 'r1', model: 'test-model', text: '研究报告', createdAt: '2026-11-28T01:00:00Z' }])
    await wrapper.findAll('button').find(b => b.text() === '确认分析')!.trigger('click'); await flushPromises()
    expect(testIntegration).toHaveBeenCalledWith('ai', 'analyze', 1, expect.any(AbortSignal), 'event-1')
    expect(wrapper.text()).toContain('研究报告')
  })
  it('disables analysis when AI is not enabled while preserving historical reports', async () => {
    vi.mocked(getIntegrations).mockResolvedValue(overviewFixture as Awaited<ReturnType<typeof getIntegrations>>)
    vi.mocked(getReports).mockResolvedValue([{ id: 'r1', model: 'old-model', text: '旧报告', createdAt: '2026-11-28T01:00:00Z' }])
    wrapper = mount(EventAI, { props: { eventId: 'event-1' } }); await flushPromises()
    expect(wrapper.get('button').attributes('disabled')).toBeDefined()
    expect(wrapper.text()).toContain('旧报告')
    expect(testIntegration).not.toHaveBeenCalled()
  })
})

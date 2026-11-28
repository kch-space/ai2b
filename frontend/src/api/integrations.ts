export type IntegrationKind = 'telegram' | 'ai'
export interface IntegrationConfig {
  kind: 'TELEGRAM' | 'AI'; version: number; enabled: boolean; secretConfigured: boolean; settings: Record<string, string | boolean>
}
export interface IntegrationAudit { id: string; kind: string; action: string; outcome: string; detail: string; createdAt: string }
export interface IntegrationOverview { audit?: IntegrationAudit[]; telegram: IntegrationConfig; ai: IntegrationConfig; aiAllowedHosts: string[]; allowLocalAI: boolean }
export interface TelegramChat { chatId: string; type: 'private' | 'group' | 'supergroup'; title: string; username: string; lastMessageAt: string }
export interface IntegrationResult { success: boolean; message: string; reply?: string; messageId?: string; botUsername?: string; modelAvailable?: boolean; reportId?: string; chats?: TelegramChat[] }
export interface AIReport { id: string; model: string; text: string; createdAt: string }
async function request(path: string, signal: AbortSignal, method = 'GET', body?: unknown): Promise<unknown> {
  const response = await fetch(`/api/v1/integrations${path}`, { method, signal, cache: 'no-store',
    headers: { Accept: 'application/json', ...(method !== 'GET' ? { 'Content-Type': 'application/json', 'X-AI2B-Action': 'integration-admin' } : {}) },
    ...(body === undefined ? {} : { body: JSON.stringify(body) }) })
  const data: unknown = await response.json().catch(() => null)
  if (!response.ok) throw new Error(object(data) && typeof data.message === 'string' ? data.message : `集成接口返回 HTTP ${response.status}`)
  return data
}
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object'
function config(v: unknown): v is IntegrationConfig {
  return object(v) && ['TELEGRAM', 'AI'].includes(String(v.kind)) && Number.isSafeInteger(v.version)
    && typeof v.enabled === 'boolean' && typeof v.secretConfigured === 'boolean' && object(v.settings)
    && Object.values(v.settings).every(x => typeof x === 'string' || typeof x === 'boolean')
}
export async function getIntegrations(signal: AbortSignal): Promise<IntegrationOverview> {
  const data = await request('', signal)
  if (!object(data) || !config(data.telegram) || !config(data.ai) || !Array.isArray(data.aiAllowedHosts)
    || !data.aiAllowedHosts.every(x => typeof x === 'string') || typeof data.allowLocalAI !== 'boolean') throw new Error('集成配置响应格式不正确')
  return data as unknown as IntegrationOverview
}
export async function saveIntegration(kind: IntegrationKind, update: { version: number; enabled: boolean; settings: Record<string, string | boolean>; secret?: string; clearSecret: boolean }, signal: AbortSignal): Promise<IntegrationConfig> {
  const data = await request(`/${kind}`, signal, 'PUT', update)
  if (!config(data)) throw new Error('配置保存响应格式不正确')
  return data
}
export async function testIntegration(kind: IntegrationKind, action: string, version: number, signal: AbortSignal, eventId?: string): Promise<IntegrationResult> {
  const data = await request(`/${kind}/test/${action}`, signal, 'POST', { confirmed: true, version, eventId })
  if (!object(data) || data.success !== true || typeof data.message !== 'string' || (data.reply !== undefined && typeof data.reply !== 'string')) throw new Error('集成测试响应格式不正确')
  if (data.botUsername !== undefined && (typeof data.botUsername !== 'string' || !/^[A-Za-z0-9_]{5,64}$/.test(data.botUsername))) throw new Error('机器人身份响应格式不正确')
  if (kind === 'telegram' && action === 'chats' && (!Array.isArray(data.chats) || data.chats.length > 100 || typeof data.botUsername !== 'string'
    || !data.chats.every(c => object(c) && typeof c.chatId === 'string' && /^-?[1-9]\d{0,19}$/.test(c.chatId)
      && ['private', 'group', 'supergroup'].includes(String(c.type)) && typeof c.title === 'string' && c.title.length <= 160
      && typeof c.username === 'string' && (c.username === '' || /^[A-Za-z0-9_]{5,64}$/.test(c.username))
      && typeof c.lastMessageAt === 'string' && Number.isFinite(Date.parse(c.lastMessageAt))))) throw new Error('Telegram 会话响应格式不正确')
  return data as unknown as IntegrationResult
}
export async function getReports(eventId: string, signal: AbortSignal): Promise<AIReport[]> {
  const data = await request(`/ai/reports/${encodeURIComponent(eventId)}`, signal)
  if (!object(data) || !Array.isArray(data.items) || !data.items.every(r => object(r) && typeof r.id === 'string'
    && typeof r.model === 'string' && typeof r.text === 'string' && typeof r.createdAt === 'string' && Number.isFinite(Date.parse(r.createdAt)))) throw new Error('AI 报告响应格式不正确')
  return data.items as AIReport[]
}

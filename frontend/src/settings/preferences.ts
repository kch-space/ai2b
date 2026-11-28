/** Browser-only display preferences. Legacy integration drafts are ignored and dropped on save. */
export interface Preferences {
  version: 1
  refreshSeconds: 15 | 30 | 60
  marketPageSize: 10 | 25 | 50
  telegram: { chatId: string; minimumSeverity: 'P0' | 'P1'; includeEvidence: boolean }
  ai: { baseUrl: string; model: string; temperature: string; maxTokens: string }
}
export const STORAGE_KEY = 'ai2b.ui-preferences.v1'
export function defaults(): Preferences {
  return { version: 1, refreshSeconds: 30, marketPageSize: 10,
    telegram: { chatId: '', minimumSeverity: 'P1', includeEvidence: true },
    ai: { baseUrl: 'https://api.openai.com/v1', model: '', temperature: '0.2', maxTokens: '1024' } }
}
const object = (v: unknown): v is Record<string, unknown> => v !== null && typeof v === 'object'
export function readPreferences(): Preferences {
  const result = defaults()
  try {
    const data: unknown = JSON.parse(localStorage.getItem(STORAGE_KEY) ?? 'null')
    if (!object(data) || data.version !== 1) return result
    if (typeof data.refreshSeconds === 'number' && [15, 30, 60].includes(data.refreshSeconds)) result.refreshSeconds = data.refreshSeconds as Preferences['refreshSeconds']
    if (typeof data.marketPageSize === 'number' && [10, 25, 50].includes(data.marketPageSize)) result.marketPageSize = data.marketPageSize as Preferences['marketPageSize']

  } catch { /* Storage unavailable or corrupt: safe defaults, no automatic overwrites. */ }
  return result
}
export function savePreferences(value: Preferences): void {
  // Whitelist every field, even if a caller supplied extra secret fields.
  const clean = { version: 1, refreshSeconds: value.refreshSeconds, marketPageSize: value.marketPageSize }
  if (![15, 30, 60].includes(value.refreshSeconds) || ![10, 25, 50].includes(value.marketPageSize)) throw new Error('显示偏好参数无效')
  localStorage.setItem(STORAGE_KEY, JSON.stringify(clean))
}
export function validateBaseUrl(value: string): string | null {
  try {
    const url = new URL(value)
    if (url.username || url.password || url.search || url.hash) return 'Base URL 不允许包含用户名、密码、查询参数或片段，请勿把密钥写入地址。'
    const local = ['localhost', '127.0.0.1', '[::1]'].includes(url.hostname)
    if (url.protocol !== 'https:' && !(url.protocol === 'http:' && local)) return '远程模型地址必须使用 HTTPS；本机 localhost 可用 HTTP。'
    return null
  } catch { return '请输入完整有效的 Base URL（例如 https://api.openai.com/v1）。' }
}
export function validateTelegram(token: string, chatId: string, requireChatId = true): string[] {
  const errors: string[] = []
  if (!/^\d{5,20}:[A-Za-z0-9_-]{20,100}$/.test(token.trim())) errors.push('Bot Token 格式应为机器人数字 ID:密钥，请从 BotFather 获取。')
  if ((requireChatId || chatId.trim()) && !/^-?\d{1,20}$/.test(chatId.trim())) errors.push('Chat ID 需为数字，群组 ID 通常是负数；不要填写手机号。')
  return errors
}
export function validateAI(config: Preferences['ai'], key: string): string[] {
  const errors: string[] = []
  const urlError = validateBaseUrl(config.baseUrl.trim())
  if (urlError) errors.push(urlError)
  if (!config.model.trim() || config.model.length > 128 || /[\r\n]/.test(config.model)) errors.push('请填写有效的模型 ID。')
  if (!key.trim() || /\s/.test(key.trim())) errors.push('请填写无空白字符的 API Key（仅保留在当前页面内存中）。')
  if (!config.temperature.trim() || !Number.isFinite(Number(config.temperature)) || Number(config.temperature) < 0 || Number(config.temperature) > 2) errors.push('Temperature 需为 0–2。')
  if (!/^\d+$/.test(config.maxTokens) || Number(config.maxTokens) < 1 || Number(config.maxTokens) > 32768) errors.push('Max tokens 需为 1–32768 的整数。')
  return errors
}

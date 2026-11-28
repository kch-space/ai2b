import { afterEach, describe, expect, it, vi } from 'vitest'
import { defaults, readPreferences, savePreferences, STORAGE_KEY, validateAI, validateBaseUrl, validateTelegram } from '../src/settings/preferences'
afterEach(() => { localStorage.clear(); vi.restoreAllMocks() })
describe('local UI preferences and non-secret drafts', () => {
  it('defaults to a compact market and slower dashboard refresh', () => {
    expect(readPreferences().marketPageSize).toBe(10)
    expect(readPreferences().refreshSeconds).toBe(30)
  })
  it('whitelists non-secret fields even when supplied secret properties', () => {
    const value = { ...defaults(), token: 'top-secret-token', apiKey: 'top-secret-key',
      telegram: { ...defaults().telegram, botToken: 'secret-bot' }, ai: { ...defaults().ai, apiKey: 'secret-ai' } }
    savePreferences(value)
    const stored = localStorage.getItem(STORAGE_KEY)!
    expect(stored).not.toContain('secret')
    expect(JSON.parse(stored)).toEqual({ version: 1, refreshSeconds: 30, marketPageSize: 10 })
  })
  it('recovers from malformed data and ignores unknown values', () => {
    localStorage.setItem(STORAGE_KEY, '{broken')
    expect(readPreferences()).toEqual(defaults())
    localStorage.setItem(STORAGE_KEY, JSON.stringify({ version: 1, refreshSeconds: '15', marketPageSize: 999, telegram: { minimumSeverity: 'P9' } }))
    expect(readPreferences()).toEqual(defaults())
  })
  it('returns defaults with unavailable storage and exposes failed saves', () => {
    vi.spyOn(Storage.prototype, 'getItem').mockImplementation(() => { throw new Error('blocked') })
    expect(readPreferences()).toEqual(defaults())
    vi.spyOn(Storage.prototype, 'setItem').mockImplementation(() => { throw new Error('blocked') })
    expect(() => savePreferences(defaults())).toThrow('blocked')
  })
  it('blocks URL credentials/query strings and unsafe remote HTTP', () => {
    for (const url of ['https://user:secret@example.com/v1', 'https://example.com/v1?key=secret', 'https://example.com/v1#secret', 'http://example.com/v1', 'file:///tmp/model', 'invalid']) {
      expect(validateBaseUrl(url)).not.toBeNull()

    }
    expect(validateBaseUrl('http://localhost:11434/v1')).toBeNull()
    expect(validateBaseUrl('https://example.com/v1')).toBeNull()
  })
  it('allows Token-only setup but requires Chat ID before enabling', () => {
    const token = '123456789:abcdefghijklmnopqrstuvw'
    expect(validateTelegram(token, '', false)).toEqual([])
    expect(validateTelegram(token, '', true)).toHaveLength(1)
    expect(validateTelegram(token, 'phone', false)).toHaveLength(1)
    expect(validateTelegram('', '', false)).toHaveLength(1)
  })
  it('checks syntax without claiming credentials are valid', () => {
    expect(validateTelegram('123456789:abcdefghijklmnopqrstuvw', '-1001234567890')).toEqual([])
    expect(validateTelegram('', 'phone')).toHaveLength(2)
    expect(validateAI({ ...defaults().ai, model: 'research-model' }, 'test-only-key')).toEqual([])
    expect(validateAI({ ...defaults().ai, temperature: 'NaN', maxTokens: '-1' }, '')).toHaveLength(4)
  })
})

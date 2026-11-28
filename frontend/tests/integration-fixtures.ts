export const overviewFixture = {
  telegram: { kind: 'TELEGRAM', version: 1, enabled: false, secretConfigured: true,
    settings: { chatId: '-1001', minimumSeverity: 'P1', includeEvidence: true } },
  ai: { kind: 'AI', version: 1, enabled: false, secretConfigured: true,
    settings: { baseUrl: 'https://api.openai.com/v1', model: 'test-model', temperature: '0.2', maxTokens: '1024' } },
  aiAllowedHosts: ['api.openai.com'], allowLocalAI: false,
}

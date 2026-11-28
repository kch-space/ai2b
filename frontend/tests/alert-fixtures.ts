const now = '2026-11-28T02:00:00Z'
export const eventFixture = {
  id: 'ab000000-0000-4000-8000-000000000001', symbol: 'BTCUSDT', rule_code: 'PRICE_DROP', version_number: 1,
  status: 'ACTIVE', severity: 'P0', opened_at: now, last_seen_at: now, resolved_at: null, hit_count: 1,
  evidence: { changeRatio: '-0.11', baselinePrice: '100', latestPrice: '89', dataTime: now, source: 'BINANCE_USD_M_CLOSED_1M', quality: 'COMPLETE_LIVE' },
}
export const pageFixture = { items: [eventFixture], total: 1, page: 1, pageSize: 20, channel: 'MOCK', simulation: true, serverTime: now }
export const rulesFixture = {
  items: [{ id: 2, rule_code: 'PRICE_DROP', version_number: 1, direction: 'DOWN', window_minutes: 5,
    trigger_ratio: '0.05', escalation_ratio: '0.10', recovery_ratio: '0.02', recovery_windows: 2, cooldown_seconds: 900, reminder_delta: '0.01' }],
  states: [{ symbol: 'BTCUSDT', rule_code: 'PRICE_DROP', outcome: 'UNKNOWN', reason: 'BACKFILL_OR_WARMUP', evaluated_at: now,
    last_window: null, recovery_count: 0, change_ratio: null }], enabled: true, simulation: true,
}
export const detailFixture = {
  event: eventFixture,
  history: [{ id: 'h1', action: 'OPEN', severity: 'P0', data_time: now, created_at: now, evidence: eventFixture.evidence }],
  notifications: [{ id: 'n1', history_id: 'h1', channel: 'MOCK', status: 'SIMULATED', attempts: 1,
    last_error: null, provider_message_id: 'mock:n1', sent_at: now }],
  attempts: [{ outbox_id: 'n1', attempt_number: 1, outcome: 'SIMULATED', error_message: null, created_at: now }], simulation: true,
}
export const response = (body: unknown, status = 200) => new Response(JSON.stringify(body), { status })

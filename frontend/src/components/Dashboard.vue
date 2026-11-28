<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getKlines, type KlinePage, type MarketSymbol } from '../api/klines'
import { getEvents, type EventPage } from '../api/alerts'
import { getInstruments, type InstrumentPage } from '../api/instruments'
import { readPreferences } from '../settings/preferences'
const symbols: MarketSymbol[] = ['BTCUSDT', 'ETHUSDT']
const markets = ref<Partial<Record<MarketSymbol, KlinePage>>>({})
const marketErrors = ref<Partial<Record<MarketSymbol, string>>>({})
const events = ref<EventPage | null>(null)
const instruments = ref<InstrumentPage | null>(null)
const eventError = ref('')
const instrumentError = ref('')
const loading = ref(false)
const lastRefresh = ref<string | null>(null)
const refreshSeconds = readPreferences().refreshSeconds
let controller: AbortController | null = null
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
const time = (v: string | null | undefined) => v ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', second: '2-digit', month: '2-digit', day: '2-digit' }).format(new Date(v)) : '—'
const price = (p?: KlinePage) => p?.items.length ? Number(p.items.at(-1)!.close).toLocaleString('en-US', { maximumFractionDigits: 2 }) : '—'
const change = (p?: KlinePage) => p && p.missingBars === 0 && !p.stale && p.items.length > 1
  ? (Number(p.items.at(-1)!.close) / Number(p.items[0]!.open) - 1) * 100 : null
const ready = computed(() => symbols.filter(s => markets.value[s]?.quality === 'COMPLETE' && !markets.value[s]?.stale && !marketErrors.value[s]).length)
function line(p?: KlinePage) {
  if (!p || p.items.length < 2 || p.missingBars || p.stale) return ''
  const values = p.items.map(c => Number(c.close)), min = Math.min(...values), span = Math.max(...values) - min || 1
  return values.map((v, i) => `${i / (values.length - 1) * 300},${64 - (v - min) / span * 50}`).join(' ')
}
async function refresh() {
  controller?.abort(); const current = new AbortController(); controller = current; loading.value = true
  const timeout = setTimeout(() => current.abort(), 8000)
  const message = (e: unknown) => current.signal.aborted ? '请求超时' : e instanceof Error ? e.message : '加载失败'
  const active = () => !disposed && controller === current
  const jobs = symbols.map(async symbol => {
    try { const result = await getKlines(symbol, 1, current.signal); if (active()) { markets.value[symbol] = result; delete marketErrors.value[symbol] } }
    catch (e) { if (active()) marketErrors.value[symbol] = message(e) }
  })
  jobs.push((async () => {
    try { const result = await getEvents(1, 'ACTIVE', current.signal); if (active()) { events.value = result; eventError.value = '' } }
    catch (e) { if (active()) eventError.value = message(e) }
  })())
  jobs.push((async () => {
    try { const result = await getInstruments('', '', 1, current.signal, 1); if (active()) { instruments.value = result; instrumentError.value = '' } }
    catch (e) { if (active()) instrumentError.value = message(e) }
  })())
  await Promise.all(jobs)
  clearTimeout(timeout)
  if (active()) { loading.value = false; controller = null; lastRefresh.value = new Date().toISOString() }
}
onMounted(() => { void refresh(); timer = setInterval(() => { if (!loading.value) void refresh() }, refreshSeconds * 1000) })
onUnmounted(() => { disposed = true; controller?.abort(); clearInterval(timer) })
</script>
<template>
  <div class="dashboard">
    <div class="page-heading"><div><p class="eyebrow">MARKET OVERVIEW</p><h1>市场概览<span class="heading-dot">.</span></h1><p class="page-description">先看重点，再深入研究。异常信号与行情状态集中在这里。</p></div><button :disabled="loading" data-testid="refresh-dashboard" @click="refresh">{{ loading ? '刷新中…' : '↻ 刷新概览' }}</button></div>
    <div class="overview-stats">
      <article><span class="stat-label">重点观察</span><strong>02 <small>个标的</small></strong><span class="stat-foot">BTC / ETH · 闭合 1m</span></article>
      <article><span class="stat-label">活动预警 <span v-if="eventError && events" class="muted">· 上次数据</span></span><strong>{{ events?.total ?? '—' }} <small>条事件</small></strong><a href="#/alerts" class="stat-foot text-link">查看事件中心 →</a></article>
      <article><span class="stat-label">完整行情窗口</span><strong>{{ loading && !lastRefresh ? '—' : ready }} <small>/ 2</small></strong><span class="stat-foot">最近 1h · 无缺口且未陈旧</span></article>
      <article><span class="stat-label">全市场合约 <span v-if="instrumentError && instruments" class="muted">· 上次数据</span></span><strong>{{ instruments?.total ?? '—' }} <small>个标的</small></strong><a href="#/markets" class="stat-foot text-link">按需搜索，不占首页 →</a></article>
    </div>
    <section aria-labelledby="focus-title">
      <div class="section-heading"><h2 id="focus-title">重点观察</h2><a href="#/markets" class="text-link">浏览合约市场 ↗</a></div>
      <div class="focus-grid"><article v-for="symbol in symbols" :key="symbol" class="focus-card">
        <div class="focus-heading"><span class="coin-icon" :class="{ eth: symbol === 'ETHUSDT' }">{{ symbol === 'BTCUSDT' ? '₿' : 'Ξ' }}</span><div><h3>{{ symbol }}</h3><span class="muted">{{ symbol === 'BTCUSDT' ? 'Bitcoin' : 'Ethereum' }} · USDT 永续</span></div><span class="quality-pill" :class="{ good: markets[symbol] && !marketErrors[symbol] && !markets[symbol]!.stale && !markets[symbol]!.missingBars }">{{ marketErrors[symbol] ? '读取失败' : !markets[symbol] ? '等待数据' : markets[symbol]!.stale ? '数据陈旧' : markets[symbol]!.missingBars ? '存在缺口' : '窗口完整' }}</span></div>
        <div class="focus-price">{{ price(markets[symbol]) }} <small>USDT</small></div>
        <div class="focus-change" :class="{ negative: (change(markets[symbol]) ?? 0) < 0 }">{{ change(markets[symbol]) == null ? '1h 涨跌暂不可用' : `${change(markets[symbol])! >= 0 ? '+' : ''}${change(markets[symbol])!.toFixed(2)}%` }}<span v-if="change(markets[symbol]) != null">最近 1h 完整窗口</span></div>
        <svg v-if="line(markets[symbol])" class="sparkline" viewBox="0 0 300 76" role="img" :aria-label="`${symbol} 最近一小时闭合收盘价趋势`"><polyline :points="line(markets[symbol])" fill="none" stroke="currentColor" stroke-width="2" /></svg>
        <div v-else class="sparkline-empty">{{ loading && !markets[symbol] ? '正在读取闭合行情…' : '完整窗口就绪后显示趋势，不补造缺失数据' }}</div>
        <p v-if="marketErrors[symbol]" class="error" role="alert">{{ marketErrors[symbol] }}{{ markets[symbol] ? '（保留上次数据）' : '' }}</p>
        <p v-else-if="markets[symbol]?.collector.error" class="warning">采集状态：{{ markets[symbol]!.collector.error }}</p>
        <div class="focus-footer"><span>闭合价 · {{ time(markets[symbol]?.items.at(-1)?.closeTime) }}</span><a :href="`#/market/${symbol}`" class="text-link">查看详情 →</a></div>
      </article></div>
    </section>
    <div class="dashboard-bottom">
      <section class="panel recent-alerts" aria-labelledby="recent-title"><div class="section-heading"><h2 id="recent-title">近期活动预警</h2><a href="#/alerts" class="text-link">全部事件 ↗</a></div>
        <p v-if="eventError" class="error" role="alert">{{ eventError }}{{ events ? '（保留上次数据）' : '' }}</p>
        <div v-if="!events?.items.length" class="calm-empty"><span aria-hidden="true">◎</span><h3>{{ !events ? '等待预警数据' : '暂无活动预警' }}</h3><p>只在完整实时窗口命中时触发，不将补录或缺失数据当成信号。</p></div>
        <a v-for="event in events?.items.slice(0, 4)" :key="event.id" class="recent-event" href="#/alerts"><span class="event-level" :class="{ urgent: event.severity === 'P0' }">{{ event.severity }}</span><div><strong>{{ event.symbol }} · {{ event.rule_code === 'PRICE_DROP' ? '价格急跌' : '价格急涨' }}</strong><small>5m {{ (Number(event.evidence.changeRatio) * 100).toFixed(2) }}% · {{ time(event.last_seen_at) }} · 命中 {{ event.hit_count }} 次</small></div><span aria-hidden="true">↗</span></a>
      </section>
      <section class="panel integration-summary"><p class="eyebrow">WORKSPACE STATUS</p><h2>数据与通知</h2><div class="status-row"><span>数据源</span><strong>Binance USDⓈ-M</strong></div><div class="status-row"><span>规则</span><strong>5m 价格异动 · v1</strong></div><div class="status-row"><span>通知</span><strong class="amber">{{ events?.channel === 'MOCK+TELEGRAM' ? 'Telegram + MOCK' : 'MOCK · 未启用 Telegram' }}</strong></div><div class="status-row"><span>AI 分析</span><a href="#/settings/ai" class="text-link">按需分析 · 查看配置</a></div><p v-if="instrumentError || instruments?.sync.error" class="warning">合约同步：{{ instrumentError || instruments?.sync.error }}</p><a class="settings-shortcut" href="#/settings">配置 Telegram 与 AI 模型 <span>→</span></a></section>
    </div>
    <p class="dashboard-refresh">每 {{ refreshSeconds }} 秒刷新 · 最近尝试 {{ time(lastRefresh) }}（UTC+8） · <a href="#/settings/display" class="text-link">调整显示偏好</a></p>
  </div>
</template>

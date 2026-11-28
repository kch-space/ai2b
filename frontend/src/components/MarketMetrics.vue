<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { collectMetrics, getMetrics, percent } from '../api/metrics'
import type { MetricPage, MetricStream, MetricType } from '../api/metrics'
import type { MarketSymbol } from '../api/klines'
import MetricChart from './MetricChart.vue'

const props = defineProps<{ symbol: MarketSymbol }>()
const data = ref<MetricPage | null>(null), error = ref(''), actionError = ref(''), notice = ref('')
const loading = ref(false), starting = ref(false)
let query: AbortController | null = null, action: AbortController | null = null
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false, lastRead = 0
const streams = computed(() => new Map(data.value?.streams.map(s => [s.type, s]) ?? []))
const oi = computed(() => streams.value.get('OPEN_INTEREST'))
const funding = computed(() => streams.value.get('FUNDING_SNAPSHOT'))
const settled = computed(() => streams.value.get('FUNDING_HISTORY'))
const ratios: { type: MetricType; label: string; description: string }[] = [
  { type: 'GLOBAL_ACCOUNT_RATIO', label: '全市场账户多空比', description: '统计持有净多仓与净空仓的账户占比，每个账户计一次。' },
  { type: 'TOP_ACCOUNT_RATIO', label: '大户账户多空比', description: '统计保证金余额排名前 20% 用户的账户方向占比。' },
  { type: 'TOP_POSITION_RATIO', label: '大户持仓多空比', description: '统计保证金余额排名前 20% 用户的多空持仓比例，口径不同于账户数。' },
]
const formatTime = (v: string | null | undefined) => v ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium' }).format(new Date(v)) : '暂无'
const quality = (s: MetricStream | undefined) => !s ? '暂无数据' : ({ UNAVAILABLE: '暂无数据', STALE: '数据滞后', GAPPED: '窗口有缺口', READY: s.period === '5m' ? '窗口完整' : '已更新' })[s.quality]
const status = (s: MetricStream | undefined) => s?.sync.error ?? ''
const value = (s: MetricStream | undefined, field: string) => s?.latest?.values[field] ?? '—'

async function load() {
  query?.abort()
  const current = new AbortController(); query = current; loading.value = true
  const timeout = setTimeout(() => current.abort(), 10000)
  try {
    const result = await getMetrics(props.symbol, current.signal)
    if (!disposed && query === current) { data.value = result; error.value = ''; lastRead = Date.now() }
  } catch (cause) {
    if (!disposed && query === current) { error.value = current.signal.aborted ? '指标查询超时，请重试。' : cause instanceof Error ? cause.message : '指标读取失败'; lastRead = Date.now() }
  } finally {
    clearTimeout(timeout)
    if (query === current) { query = null; loading.value = false }
  }
}
async function collect() {
  if (starting.value) return
  const current = new AbortController(); action = current; starting.value = true; actionError.value = ''; notice.value = ''
  const timeout = setTimeout(() => current.abort(), 10000)
  try {
    await collectMetrics(props.symbol, current.signal)
    if (!disposed) { notice.value = '指标采集任务已提交，页面会自动刷新。'; await load() }
  } catch (cause) {
    if (!disposed) actionError.value = current.signal.aborted ? '提交超时，请刷新查看指标采集状态。' : cause instanceof Error ? cause.message : '指标采集提交失败'
  } finally {
    clearTimeout(timeout); action = null
    if (!disposed) starting.value = false
  }
}
onMounted(() => {
  load()
  timer = setInterval(() => { if (!loading.value && (data.value?.running || Date.now() - lastRead >= 30000)) load() }, 3000)
})
onUnmounted(() => { disposed = true; clearInterval(timer); query?.abort(); action?.abort() })
</script>
<template>
  <section class="panel metric-panel" aria-labelledby="metrics-title">
    <div class="panel-heading"><div><p class="eyebrow">MARKET CONTEXT</p><h2 id="metrics-title">持仓、费率与多空分布</h2></div><div class="detail-actions"><button data-testid="refresh-metrics" :disabled="loading" @click="load">刷新指标</button><button data-testid="collect-metrics" :disabled="starting || data?.running" @click="collect">{{ starting || data?.running ? '指标采集中…' : '采集指标' }}</button></div></div>
    <p v-if="error" role="alert" class="error">{{ error }}{{ data ? '；保留上次读取的指标。' : '' }}</p>
    <p v-if="actionError" role="alert" class="error">{{ actionError }}</p><p v-if="notice" role="status" class="success">{{ notice }}</p>
    <p v-if="loading && !data" role="status" class="empty">正在读取市场指标…</p>
    <template v-if="data">
      <p v-if="Date.parse(data.sourceGate.until) > Date.parse(data.serverTime)" role="alert" class="error">{{ data.sourceGate.error }}；下次允许采集：{{ formatTime(data.sourceGate.until) }}。已有数据保留。</p>
      <div class="metric-overview">
        <article class="metric-card" data-testid="open-interest">
          <div class="metric-card-heading"><h3>持仓量 OI</h3><span :class="oi?.quality === 'READY' ? 'metric-quality' : 'warning'">{{ quality(oi) }}</span></div>
          <p class="metric-main">{{ value(oi, 'quantity') }} <small>{{ data.baseAsset }}</small></p><p class="metric-sub">名义价值 {{ value(oi, 'notional') }} {{ data.quoteAsset }}</p>
          <p v-if="status(oi)" role="alert" class="error">{{ status(oi) }}</p>
          <MetricChart v-if="oi?.items.length" :items="oi.items" field="quantity" label="持仓量 OI" :start="oi.start" :end="oi.end" />
          <p v-else class="empty">尚无真实持仓量数据。</p>
          <p class="metric-caption">5 分钟采样 · {{ oi?.items.length ?? 0 }} / 288 点 · 缺失 {{ oi?.missingPoints ?? 288 }} 点<br />数据时间：{{ formatTime(oi?.latest?.eventTime) }}</p>
          <p class="note">数量与 USDT 名义价值分开展示；价格变化也会影响名义价值，OI 增加不能据此判断开多或开空。</p>
        </article>
        <article class="metric-card" data-testid="funding">
          <div class="metric-card-heading"><h3>资金费率快照</h3><span :class="funding?.quality === 'READY' ? 'metric-quality' : 'warning'">{{ quality(funding) }}</span></div>
          <p class="metric-main">{{ percent(funding?.latest?.values.rate) }}</p><p class="metric-sub">下次结算：{{ formatTime(funding?.latest?.values.nextFundingTime) }}</p>
          <p v-if="status(funding)" role="alert" class="error">{{ status(funding) }}</p>
          <dl class="metric-prices"><div><dt>标记价格（USDT）</dt><dd>{{ value(funding, 'markPrice') }}</dd></div><div><dt>指数价格（USDT）</dt><dd>{{ value(funding, 'indexPrice') }}</dd></div></dl>
          <p class="metric-caption">数据时间：{{ formatTime(funding?.latest?.eventTime) }}</p>
          <p class="note">费率快照可能变化；实际结算以历史记录为准。展示交易所下次结算时间，不假定固定结算周期。</p>
        </article>
      </div>
      <div class="metric-ratios"><article v-for="ratio in ratios" :key="ratio.type" class="metric-card" :data-testid="ratio.type">
        <div class="metric-card-heading"><h3>{{ ratio.label }}</h3><span :class="streams.get(ratio.type)?.quality === 'READY' ? 'metric-quality' : 'warning'">{{ quality(streams.get(ratio.type)) }}</span></div>
        <p class="metric-main">{{ value(streams.get(ratio.type), 'ratio') }}<small> 多 / 空</small></p>
        <p class="metric-sub">多 {{ percent(streams.get(ratio.type)?.latest?.values.longShare) }} · 空 {{ percent(streams.get(ratio.type)?.latest?.values.shortShare) }}</p>
        <p v-if="status(streams.get(ratio.type))" role="alert" class="error">{{ status(streams.get(ratio.type)) }}</p>
        <MetricChart v-if="streams.get(ratio.type)?.items.length" :items="streams.get(ratio.type)!.items" field="ratio" :label="ratio.label" :start="streams.get(ratio.type)!.start" :end="streams.get(ratio.type)!.end" />
        <p v-else class="empty">尚无该口径的真实数据。</p>
        <p class="metric-caption">5 分钟采样 · {{ streams.get(ratio.type)?.items.length ?? 0 }} / 288 点<br />数据时间：{{ formatTime(streams.get(ratio.type)?.latest?.eventTime) }}</p><p class="note">{{ ratio.description }}</p>
      </article></div>
      <details class="gap-details metric-funding-history"><summary>最近 24 小时已结算资金费率 · {{ settled?.items.length ?? 0 }} 条 · {{ quality(settled) }}</summary>
        <p v-if="status(settled)" role="alert" class="error">{{ status(settled) }}</p>
        <div v-if="settled?.items.length" class="table-scroll"><table><thead><tr><th>结算时间（UTC+8）</th><th>资金费率</th><th>结算标记价（USDT）</th></tr></thead><tbody><tr v-for="point in [...settled.items].reverse()" :key="point.eventTime"><td>{{ formatTime(point.eventTime) }}</td><td>{{ percent(point.values.rate) }}</td><td>{{ point.values.markPrice }}</td></tr></tbody></table></div>
        <p v-else class="empty">该区间未保存结算记录，不用零费率代替缺失数据。</p>
      </details>
      <p class="note">来源：Binance USDⓈ-M · 最近 24 小时 · 指标约每 5 分钟采集，非逐笔实时数据；缺口不连线，超过 15 分钟未更新会标记滞后。窄屏图表可横向滑动。各指标独立保存与重试。</p>
    </template>
  </section>
</template>

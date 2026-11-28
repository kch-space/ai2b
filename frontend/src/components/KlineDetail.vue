<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { collectKlines, getKlines } from '../api/klines'
import type { KlinePage, MarketSymbol } from '../api/klines'
import KlineChart from './KlineChart.vue'
import MarketMetrics from './MarketMetrics.vue'

const props = defineProps<{ symbol: MarketSymbol }>()
const hours = ref(6)
const data = ref<KlinePage | null>(null)
const loading = ref(false)
const starting = ref(false)
const error = ref('')
const actionError = ref('')
const notice = ref('')
let controller: AbortController | null = null
let actionController: AbortController | null = null
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
let refreshedAt = 0
const latest = computed(() => data.value?.items.at(-1))
const recent = computed(() => data.value?.items.slice(-8).reverse() ?? [])
const outcome = computed(() => ({ IDLE: '等待采集', QUEUED: '等待执行', RUNNING: '采集中', SUCCESS: '本次采集完成',
  CATCHING_UP: '补录中，下轮继续', ERROR: '采集异常' })[data.value?.collector.outcome ?? 'IDLE'])
const formatTime = (value: string | null | undefined) => value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium',
}).format(new Date(value)) : '暂无'

async function load(clear = false) {
  controller?.abort()
  const current = new AbortController()
  controller = current
  loading.value = true
  if (clear) data.value = null
  const timeout = setTimeout(() => current.abort(), 10000)
  try {
    const result = await getKlines(props.symbol, hours.value, current.signal)
    if (disposed || controller !== current) return
    data.value = result
    error.value = ''
    refreshedAt = Date.now()
  } catch (cause) {
    if (!disposed && controller === current) {
      error.value = current.signal.aborted ? '查询超时，请重试。' : cause instanceof Error ? cause.message : 'K 线读取失败'
      refreshedAt = Date.now()
    }
  } finally {
    clearTimeout(timeout)
    if (controller === current) { controller = null; loading.value = false }
  }
}

async function collect() {
  if (starting.value) return
  starting.value = true
  actionError.value = ''
  notice.value = ''
  const current = new AbortController()
  actionController = current
  const timeout = setTimeout(() => current.abort(), 10000)
  try {
    await collectKlines(props.symbol, current.signal)
    if (!disposed) { notice.value = '采集任务已提交，页面会自动刷新进度。'; await load() }
  } catch (cause) {
    if (!disposed) actionError.value = current.signal.aborted ? '提交超时，请刷新查看任务状态。'
      : cause instanceof Error ? cause.message : '提交失败'
  } finally {
    clearTimeout(timeout)
    if (!disposed) starting.value = false
    actionController = null
  }
}

watch(hours, () => load(true))
onMounted(() => {
  load()
  timer = setInterval(() => {
    if (!loading.value && (data.value?.collector.running || Date.now() - refreshedAt >= 15000)) load()
  }, 3000)
})
onUnmounted(() => {
  disposed = true
  clearInterval(timer)
  controller?.abort()
  actionController?.abort()
})
</script>

<template>
  <section class="market-detail" aria-labelledby="detail-title">
    <a class="back-link" href="#/">← 返回合约列表</a>
    <div class="detail-heading"><div><p class="eyebrow">MARKET OBSERVATION</p><h1 id="detail-title">{{ symbol }} <span>永续合约</span></h1><p class="note">Binance USDⓈ-M · 1 分钟闭合 K 线 · Asia/Shanghai</p></div><nav aria-label="切换合约"><a :class="{ active: symbol === 'BTCUSDT' }" href="#/market/BTCUSDT">BTC</a><a :class="{ active: symbol === 'ETHUSDT' }" href="#/market/ETHUSDT">ETH</a></nav></div>
    <div class="panel market-panel">
      <div class="panel-heading"><div><p class="eyebrow">CLOSED CANDLES</p><h2>价格与成交量</h2></div><div class="detail-actions"><button data-testid="refresh-klines" :disabled="loading" @click="load()">{{ loading ? '读取中…' : '刷新' }}</button><button data-testid="collect-klines" :disabled="starting || data?.collector.running" @click="collect">{{ starting || data?.collector.running ? '采集中…' : '补录 / 继续采集' }}</button></div></div>
      <p v-if="error" class="error" role="alert">{{ error }}{{ data ? '；当前保留上次读取结果。' : '' }}</p>
      <p v-if="actionError" class="error" role="alert">{{ actionError }}</p>
      <p v-if="notice" class="success" role="status">{{ notice }}</p>
      <p v-if="!data && loading" class="empty" role="status">正在读取闭合 K 线…</p>
      <template v-if="data">
        <div class="market-stats"><div><span>最新闭合收盘（USDT）</span><strong>{{ latest?.close ?? '—' }}</strong></div><div><span>窗口数据</span><strong>{{ data.items.length }} / {{ data.expectedBars }}<small> 分钟</small></strong></div><div><span>缺失分钟</span><strong :class="{ warning: data.missingBars > 0 }">{{ data.missingBars }}</strong></div><div><span>数据状态</span><strong class="quality">{{ data.quality === 'UNAVAILABLE' ? '暂无数据' : data.stale ? '数据滞后' : data.missingBars > 0 ? '存在缺口' : '窗口完整' }}</strong></div></div>
        <div class="chart-toolbar"><label>查看区间 <select v-model.number="hours" aria-label="查看区间"><option :value="1">最近 1 小时</option><option :value="6">最近 6 小时</option><option :value="24">最近 24 小时</option></select></label><span>最新闭合时间：{{ formatTime(latest?.closeTime) }}</span></div>
        <p v-if="data.collector.error" class="error" role="alert">{{ data.collector.error }}；已有数据保留。</p>
        <KlineChart v-if="data.items.length" :items="data.items" :start="data.start" :end="data.end" />
        <p v-else class="empty">该区间暂无闭合 K 线。点击“补录 / 继续采集”获取真实数据；采集失败时会显示具体原因。</p>
        <div class="collection-status" role="status"><span>采集：{{ outcome }}</span><span>最近写入：{{ formatTime(data.collector.lastWriteAt) }}</span><span>连续检查点：{{ formatTime(data.collector.lastContiguousOpenTime) }}</span></div>
        <details v-if="data.gaps.length" class="gap-details"><summary>查看 {{ data.gaps.length }} 段数据缺口（UTC+8）</summary><ul><li v-for="gap in data.gaps.slice(0, 20)" :key="gap.start">{{ formatTime(gap.start) }} 至 {{ formatTime(gap.end) }} · {{ gap.minutes }} 分钟</li></ul><p v-if="data.gaps.length > 20">其余缺口请缩小查看区间。</p></details>
        <details v-if="recent.length" class="gap-details"><summary>最近 {{ recent.length }} 根 K 线原始值</summary><div class="table-scroll"><table><thead><tr><th>开盘时间（UTC+8）</th><th>开</th><th>高</th><th>低</th><th>收</th><th>报价成交额（USDT）</th></tr></thead><tbody><tr v-for="item in recent" :key="item.openTime"><td>{{ formatTime(item.openTime) }}</td><td>{{ item.open }}</td><td>{{ item.high }}</td><td>{{ item.low }}</td><td>{{ item.close }}</td><td>{{ item.quoteVolume }}</td></tr></tbody></table></div></details>
      </template>
    </div>
    <p class="note">首次补录最近 24 小时；之后从连续检查点续采。缺口与未闭合数据不会用于生成完整窗口。本阶段尚未接入预警规则。</p>
    <MarketMetrics :key="symbol" :symbol="symbol" />
  </section>
</template>

<script setup lang="ts">
import EventAI from './EventAI.vue'
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getEvent, getEvents, getRules, type EventDetail, type EventPage, type RuleResponse } from '../api/alerts'
const page = ref(1)
const status = ref('ALL')
const events = ref<EventPage | null>(null)
const rules = ref<RuleResponse | null>(null)
const detail = ref<EventDetail | null>(null)
const selected = ref<string | null>(null)
const error = ref('')
const detailError = ref('')
const loading = ref(false)
const detailLoading = ref(false)
const pages = computed(() => Math.max(1, Math.ceil((events.value?.total ?? 0) / 20)))
let controller: AbortController | null = null
let detailController: AbortController | null = null
let timer: ReturnType<typeof setInterval> | undefined
let disposed = false
const time = (v: string | null | undefined) => v ? new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium' }).format(new Date(v)) : '—'
const percent = (v: string | null | undefined) => v != null ? `${(Number(v) * 100).toFixed(2)}%` : '—'
const actions: Record<string, string> = { OPEN: '首次触发', UPDATE: '持续命中', ESCALATE: '升级', REMIND: '冷却后提醒', RESOLVE: '恢复' }
const reasons: Record<string, string> = { COMPLETE_LIVE: '完整实时窗口', BACKFILL_OR_WARMUP: '历史补录 / 实时预热中', MISSING_BARS: '样本不足', GAP: '数据缺口', STALE: '数据陈旧', NOT_TRADING: '合约非交易状态', INVALID_BAR: '无效样本', UNCLOSED: '尚未闭合', WARMUP: '预热中' }
const notifications: Record<string, string> = { PENDING: '等待发送', PROCESSING: '处理中', SIMULATED: '已模拟（未真实发送）', SENT: 'Telegram 已确认发送', CANCELLED: '已取消', DEAD: '失败 / 需人工核对' }
async function refresh() {
  controller?.abort()
  const current = new AbortController(); controller = current; loading.value = true
  const timeout = setTimeout(() => current.abort(), 5000)
  try {
    const [list, config] = await Promise.all([getEvents(page.value, status.value, current.signal), getRules(current.signal)])
    if (!disposed && controller === current) { events.value = list; rules.value = config; error.value = '' }
  } catch (cause) {
    if (!disposed && controller === current) error.value = current.signal.aborted ? '请求超时，请稍后重试。' : cause instanceof Error ? cause.message : '预警数据加载失败'
  } finally { clearTimeout(timeout); if (controller === current) { controller = null; loading.value = false } }
}
async function open(id: string) {
  detailController?.abort()
  const current = new AbortController(); detailController = current
  selected.value = id; detail.value = null; detailError.value = ''; detailLoading.value = true
  const timeout = setTimeout(() => current.abort(), 5000)
  try {
    const result = await getEvent(id, current.signal)
    if (!disposed && detailController === current) detail.value = result
  } catch (cause) {
    if (!disposed && detailController === current) detailError.value = current.signal.aborted ? '详情请求超时，请重试。' : cause instanceof Error ? cause.message : '详情加载失败'
  } finally { clearTimeout(timeout); if (detailController === current) { detailController = null; detailLoading.value = false } }
}
function close() { detailController?.abort(); detailController = null; selected.value = null; detail.value = null; detailError.value = ''; detailLoading.value = false }
function filter() { page.value = 1; close(); events.value = null; void refresh() }
function move(delta: number) { page.value += delta; close(); events.value = null; void refresh() }
onMounted(() => { void refresh(); timer = setInterval(() => { if (!loading.value) void refresh(); if (selected.value && !detailLoading.value) void open(selected.value) }, 10000) })
onUnmounted(() => { disposed = true; controller?.abort(); detailController?.abort(); clearInterval(timer) })
</script>
<template>
  <section class="panel alert-center" aria-labelledby="alerts-title">
    <a href="#/">← 返回市场首页</a>
    <div class="panel-heading"><div><p class="eyebrow">PRICE ALERTS · M1</p><h1 id="alerts-title">事件中心</h1></div><button :disabled="loading" @click="refresh">{{ loading ? '刷新中…' : '刷新' }}</button></div>
    <p class="mock-banner">{{ events?.channel === 'MOCK+TELEGRAM' ? 'Telegram 已启用 · 新事件按配置发送，MOCK 记录仍为模拟。' : 'MOCK 模拟通知 · Telegram 未启用，历史通知以每条渠道状态为准。' }}</p>
    <p class="note">BTC / ETH · 闭合 5 分钟价格窗口（6 根连续 1m K 线）。阈值为工程示例，未回测，不代表交易建议。</p>
    <p v-if="rules && !rules.enabled" class="warning">预警检测已停用，历史记录仍保留。</p>
    <div v-if="rules" class="alert-rule-grid">
      <article v-for="rule in rules.items" :key="rule.id" class="metric-card">
        <h3>{{ rule.rule_code === 'PRICE_DROP' ? '价格急跌' : '价格急涨' }} · v{{ rule.version_number }}</h3>
        <p class="note">{{ rule.window_minutes }}m ≥ {{ percent(rule.trigger_ratio) }} → P1；≥ {{ percent(rule.escalation_ratio) }} → P0</p>
        <p class="note">方向涨跌幅 ≤ {{ percent(rule.recovery_ratio) }}，连续 {{ rule.recovery_windows }} 个有效窗口恢复；提醒冷却 {{ rule.cooldown_seconds / 60 }} 分钟。</p>
        <p v-for="s in rules.states.filter(s => s.rule_code === rule.rule_code)" :key="s.symbol" class="note">{{ s.symbol }} · {{ s.outcome }} · {{ reasons[s.reason] ?? s.reason }} · {{ percent(s.change_ratio) }}<br>{{ time(s.evaluated_at) }}（UTC+8）</p>
        <p v-if="!rules.states.some(s => s.rule_code === rule.rule_code)" class="note">等待采集与检测；首次启动/重启后至少 6 根新实时 K 线预热。</p>
      </article>
    </div>
    <label class="alert-filter">事件状态 <select v-model="status" @change="filter"><option value="ALL">全部</option><option value="ACTIVE">活动中</option><option value="RESOLVED">已恢复</option></select></label>
    <p v-if="error" class="error" role="alert">{{ error }} <span v-if="events">（保留上次成功数据）</span></p>
    <p v-if="loading && !events" role="status">正在加载预警事件…</p>
    <p v-if="events?.items.length === 0" class="note">暂无事件。缺口、陈旧数据、补录和预热不会触发预警。</p>
    <div v-if="events && events.items.length" class="alert-table-scroll">
      <table class="alert-table"><thead><tr><th>标的 / 规则</th><th>级别 / 状态</th><th>5m 涨跌</th><th>命中次数</th><th>最近更新（UTC+8）</th><th>证据</th></tr></thead>
        <tbody><tr v-for="event in events.items" :key="event.id"><td><a :href="`#/market/${event.symbol}`">{{ event.symbol }}</a><br>{{ event.rule_code }} · v{{ event.version_number }}</td><td>{{ event.severity }} / {{ event.status === 'ACTIVE' ? '活动中' : '已恢复' }}</td><td>{{ percent(event.evidence.changeRatio) }}</td><td>{{ event.hit_count }}</td><td>{{ time(event.last_seen_at) }}</td><td><button @click="open(event.id)">查看详情</button></td></tr></tbody>
      </table>
    </div>
    <div v-if="events" class="alert-pagination"><button :disabled="page <= 1 || loading" @click="move(-1)">上一页</button><span>第 {{ page }} / {{ pages }} 页 · {{ events.total }} 条</span><button :disabled="page >= pages || loading" @click="move(1)">下一页</button></div>
    <section v-if="selected" class="metric-card alert-detail" aria-label="事件详情">
      <div class="panel-heading"><h2>事件详情</h2><button @click="close">关闭详情</button></div>
      <p v-if="detailLoading" role="status">正在加载详情…</p>
      <p v-if="detailError" class="error" role="alert">{{ detailError }} <button @click="open(selected)">重试详情</button></p>
      <template v-if="detail">
        <p>{{ detail.event.symbol }} · {{ detail.event.rule_code }} · v{{ detail.event.version_number }} · {{ detail.event.severity }}</p>
        <p class="note">基准收盘价 {{ detail.event.evidence.baselinePrice }} → 最新收盘价 {{ detail.event.evidence.latestPrice }}<br>数据时间 {{ time(detail.event.evidence.dataTime) }} · {{ detail.event.evidence.source }} · {{ detail.event.evidence.quality }}</p>
        <h3>事件历史</h3><ol class="alert-history"><li v-for="h in detail.history" :key="h.id">{{ time(h.data_time) }} · {{ actions[h.action] ?? h.action }} · {{ h.severity }} · {{ percent(h.evidence.changeRatio) }}</li></ol>
        <h3>通知记录</h3><ul class="alert-history"><li v-for="n in detail.notifications" :key="n.id">{{ n.channel }} · {{ notifications[n.status] }} · 尝试 {{ n.attempts }} 次 · {{ n.provider_message_id ?? '—' }}<span v-if="n.last_error"> · 历史错误：{{ n.last_error }}</span></li></ul>
        <EventAI :event-id="detail.event.id" />
        <details v-if="detail.attempts.length"><summary>通知尝试审计</summary><ul class="alert-history"><li v-for="(a, index) in detail.attempts" :key="index">{{ time(a.created_at) }} · 第 {{ a.attempt_number }} 次 · {{ a.outcome }} {{ a.error_message }}</li></ul></details>
      </template>
    </section>
  </section>
</template>

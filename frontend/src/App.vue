<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import InstrumentList from './components/InstrumentList.vue'
import Dashboard from './components/Dashboard.vue'
import SettingsPage from './components/SettingsPage.vue'
import AlertCenter from './components/AlertCenter.vue'
import KlineDetail from './components/KlineDetail.vue'
import type { MarketSymbol } from './api/klines'
import { getHealth, type HealthResponse } from './api/health'

const route = ref(window.location.hash)
const selectedSymbol = computed<MarketSymbol | null>(() => {
  const match = /^#\/market\/(BTCUSDT|ETHUSDT)$/.exec(route.value)
  return match ? match[1] as MarketSymbol : null
})
const section = computed(() => selectedSymbol.value || route.value === '#/markets' ? 'markets'
  : route.value === '#/alerts' ? 'alerts' : /^#\/settings(?:\/|$)/.test(route.value) ? 'settings'
    : ['', '#', '#/'].includes(route.value) ? 'home' : 'missing')
const nav = [{ key: 'home', href: '#/', label: '概览', icon: '◫' }, { key: 'markets', href: '#/markets', label: '合约市场', icon: '≋' },
  { key: 'alerts', href: '#/alerts', label: '事件中心', icon: '◎' }, { key: 'settings', href: '#/settings', label: '设置', icon: '⚙' }]
function updateRoute() { route.value = window.location.hash }
const state = ref<'loading' | 'ready' | 'error'>('loading')
const health = ref<HealthResponse | null>(null)
const error = ref('')
let controller: AbortController | null = null
let disposed = false
const checkedAt = computed(() => health.value ? new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium',
}).format(new Date(health.value.timestamp)) : '—')
async function checkBackend() {
  controller?.abort()
  const current = new AbortController(); controller = current
  state.value = 'loading'; health.value = null; error.value = ''
  const timeout = setTimeout(() => current.abort(), 5000)
  try {
    const result = await getHealth(current.signal)
    if (!disposed && controller === current) { health.value = result; state.value = 'ready' }
  } catch (cause) {
    if (!disposed && controller === current) {
      error.value = current.signal.aborted ? '请求超时，请确认后端已启动后重试。' : cause instanceof Error ? cause.message : '无法连接后端，请稍后重试。'
      state.value = 'error'
    }
  } finally { clearTimeout(timeout); if (controller === current) controller = null }
}
onMounted(() => { void checkBackend(); window.addEventListener('hashchange', updateRoute) })
onUnmounted(() => { disposed = true; controller?.abort(); window.removeEventListener('hashchange', updateRoute) })
</script>

<template>
  <div class="workspace">
    <aside class="sidebar">
      <a class="brand" href="#/" aria-label="ai2b 首页"><span class="brand-mark">a2</span><span>ai2b<small>CRYPTO INTELLIGENCE</small></span></a>
      <p class="sidebar-label">工作空间</p>
      <nav class="primary-nav" aria-label="主导航">
        <a v-for="item in nav" :key="item.key" :href="item.href" :class="{ active: section === item.key }" :aria-current="section === item.key ? 'page' : undefined"><span aria-hidden="true">{{ item.icon }}</span>{{ item.label }}</a>
      </nav>
      <div class="sidebar-bottom"><span class="tag">本地研究环境</span><p>Binance USDT 永续<br>仅监控 · 不自动交易</p><span class="mock-label">通知 · 按服务端配置</span></div>
    </aside>
    <div class="workspace-body">
      <header class="workspace-header">
        <span class="breadcrumb">工作空间 <span>/</span> {{ nav.find(n => n.key === section)?.label ?? '页面' }}</span>
        <details class="connection-popover">
          <summary><span class="status-dot" :class="state" aria-hidden="true"></span>{{ state === 'ready' ? '后端服务已连接' : state === 'loading' ? '正在检查后端服务…' : '后端暂不可用' }}</summary>
          <div class="connection-content">
            <p v-if="error" class="error" role="alert">{{ error }}</p>
            <dl v-if="health" class="metadata"><div><dt>服务</dt><dd>{{ health.service }}</dd></div><div><dt>检查时间（UTC+8）</dt><dd>{{ checkedAt }}</dd></div><div class="wide"><dt>请求 ID</dt><dd class="mono">{{ health.requestId }}</dd></div></dl>
            <p class="note">仅检测应用可用性，不代表行情采集、数据库或通知渠道已连接。</p>
            <button data-testid="check-backend" :disabled="state === 'loading'" @click="checkBackend">重新检测</button>
          </div>
        </details>
      </header>
      <main class="page-content">
        <Dashboard v-if="section === 'home'" />
        <template v-else-if="route === '#/markets'"><div class="page-heading"><div><p class="eyebrow">CONTRACT UNIVERSE</p><h1>合约市场</h1><p class="page-description">按需搜索全市场标的，首页只保留重点观察。</p></div><span class="tag">行情采集仅 BTC / ETH</span></div><InstrumentList /></template>
        <KlineDetail v-else-if="selectedSymbol" :key="selectedSymbol" :symbol="selectedSymbol" />
        <AlertCenter v-else-if="section === 'alerts'" />
        <SettingsPage v-else-if="section === 'settings'" />
        <section v-else class="panel"><h1>页面不存在</h1><p class="note">该地址尚未提供功能。</p><a class="text-link" href="#/">返回概览 →</a></section>
        <footer>仅供市场观察与研究 · 不包含自动下单或真实资金管理</footer>
      </main>
    </div>
  </div>
</template>

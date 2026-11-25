<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getHealth } from './api/health'
import type { HealthResponse } from './api/health'

const state = ref<'loading' | 'ready' | 'error'>('loading')
const health = ref<HealthResponse | null>(null)
const error = ref('')
let controller: AbortController | null = null
let disposed = false

const checkedAt = computed(() => health.value
  ? new Intl.DateTimeFormat('zh-CN', {
      timeZone: 'Asia/Shanghai', dateStyle: 'medium', timeStyle: 'medium',
    }).format(new Date(health.value.timestamp))
  : '—')

async function checkBackend() {
  controller?.abort()
  const current = new AbortController()
  controller = current
  state.value = 'loading'
  health.value = null
  error.value = ''
  const timeout = setTimeout(() => current.abort(), 5000)
  try {
    const result = await getHealth(current.signal)
    if (!disposed && controller === current) {
      health.value = result
      state.value = 'ready'
    }
  } catch (cause) {
    if (!disposed && controller === current) {
      error.value = current.signal.aborted
        ? '请求超时，请确认后端已启动后重试。'
        : cause instanceof Error ? cause.message : '无法连接后端，请稍后重试。'
      state.value = 'error'
    }
  } finally {
    clearTimeout(timeout)
    if (controller === current) controller = null
  }
}

onMounted(checkBackend)
onUnmounted(() => {
  disposed = true
  controller?.abort()
})
</script>

<template>
  <main class="shell">
    <header class="topbar">
      <a class="brand" href="/" aria-label="Crypto Intelligence 首页">
        <span class="brand-mark" aria-hidden="true">CI</span>
        <span>Crypto Intelligence<small>ai2b · 市场情报工作台</small></span>
      </a>
      <span class="tag">工程初始化</span>
    </header>

    <section class="intro">
      <p class="eyebrow">MARKET INTELLIGENCE</p>
      <h1>重要信息，主动找到你。</h1>
      <p class="intro-copy">加密货币市场监控、预警与分析平台。前后端基础工程已就绪，行情与预警能力将在后续阶段接入。</p>
    </section>

    <section class="panel" aria-labelledby="connection-title">
      <div class="panel-heading">
        <div><p class="eyebrow">SYSTEM CONNECTION</p><h2 id="connection-title">后端连接</h2></div>
        <button type="button" :disabled="state === 'loading'" @click="checkBackend">
          {{ state === 'loading' ? '检测中…' : '重新检测' }}
        </button>
      </div>
      <div class="connection" role="status" aria-live="polite" :aria-busy="state === 'loading'">
        <span class="status-dot" :class="state" aria-hidden="true"></span>
        <strong v-if="state === 'loading'">正在检查后端服务…</strong>
        <strong v-else-if="state === 'ready'">后端服务已连接</strong>
        <strong v-else>后端服务暂不可用</strong>
      </div>
      <p v-if="state === 'error'" class="error" role="alert">{{ error }}</p>
      <dl v-if="health" class="metadata">
        <div><dt>服务</dt><dd>{{ health.service }}</dd></div>
        <div><dt>检查时间（UTC+8）</dt><dd>{{ checkedAt }}</dd></div>
        <div class="wide"><dt>请求 ID</dt><dd class="mono">{{ health.requestId }}</dd></div>
      </dl>
      <p class="note">仅检测应用可用性，不代表行情采集、数据库或通知渠道已连接。</p>
    </section>

    <section class="features" aria-label="规划能力">
      <article class="feature"><span class="feature-number">01</span><h2>市场监控</h2><p>价格、成交量、OI 与资金费率</p><span class="planned">待实现</span></article>
      <article class="feature"><span class="feature-number">02</span><h2>规则预警</h2><p>可解释信号、事件与证据追踪</p><span class="planned">待实现</span></article>
      <article class="feature"><span class="feature-number">03</span><h2>研究分析</h2><p>历史信号、现货与链上数据</p><span class="planned">待实现</span></article>
    </section>
    <footer>仅供市场观察与研究 · 不包含自动下单或真实资金管理</footer>
  </main>
</template>

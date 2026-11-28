<script setup lang="ts">
import { onMounted, onUnmounted, ref } from 'vue'
import { getIntegrations, getReports, testIntegration, type AIReport, type IntegrationConfig } from '../api/integrations'
const props = defineProps<{ eventId: string }>()
const ai = ref<IntegrationConfig | null>(null)
const reports = ref<AIReport[]>([])
const busy = ref(false)
const error = ref('')
const confirming = ref(false)
let controller: AbortController | null = null
let disposed = false
async function load() {
  const current = new AbortController(); controller = current
  const timeout = setTimeout(() => current.abort(), 8000)
  try { const [config, items] = await Promise.all([getIntegrations(current.signal), getReports(props.eventId, current.signal)]); if (!disposed) { ai.value = config.ai; reports.value = items } }
  catch (e) { if (!disposed) error.value = e instanceof Error ? e.message : 'AI 配置读取失败' }
  finally { clearTimeout(timeout); if (controller === current) controller = null }
}
async function analyze() {
  if (!ai.value) return
  confirming.value = false; busy.value = true; error.value = ''
  const current = new AbortController(); controller = current; const timeout = setTimeout(() => current.abort(), 25000)
  try {
    await testIntegration('ai', 'analyze', ai.value.version, current.signal, props.eventId)
    const items = await getReports(props.eventId, current.signal)
    if (!disposed) reports.value = items
  } catch (e) { if (!disposed) error.value = current.signal.aborted ? '请求超时，模型调用可能已完成，请刷新报告核对后再试。' : e instanceof Error ? e.message : '分析失败' }
  finally { clearTimeout(timeout); if (controller === current) { controller = null; busy.value = false } }
}
onMounted(() => { void load() })
onUnmounted(() => { disposed = true; controller?.abort() })
</script>
<template>
  <section class="test-box" aria-label="AI 事件研究">
    <div class="section-heading"><h3>AI 事件研究</h3><button :disabled="busy || !ai?.enabled || !ai.secretConfigured" @click="confirming = true">{{ busy ? '生成中…' : '分析本事件' }}</button></div>
    <p class="note">手动分析将把本事件的闭合行情证据发给已配置模型，可能产生费用；不发送密钥以外的用户配置，不阻塞预警。模型输出需人工核实。</p>
    <p v-if="!ai?.enabled" class="note">请先在 <a href="#/settings/ai" class="text-link">AI 设置</a> 保存密钥并允许手动事件分析。</p>
    <p v-if="error" role="alert" class="error">{{ error }}</p>
    <details v-for="r in reports" :key="r.id"><summary>{{ r.model }} · {{ new Date(r.createdAt).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai'}) }}（UTC+8）</summary><pre class="ai-reply">{{ r.text }}</pre></details>
    <div v-if="confirming" class="confirm-overlay" role="dialog" aria-modal="true" aria-label="确认 AI 分析" @keydown.esc="confirming = false"><div class="confirm-dialog"><h3>确认发送事件证据</h3><p>将事件 {{ eventId }} 的价格、变化率和时间证据发送给 {{ ai?.settings.model }}（{{ ai?.settings.baseUrl }}）。可能产生费用，模型输出不是交易建议。确认？</p><div class="test-actions"><button autofocus @click="confirming = false">取消</button><button @click="analyze">确认分析</button></div></div></div>
  </section>
</template>

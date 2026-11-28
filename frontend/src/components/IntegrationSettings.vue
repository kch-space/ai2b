<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref, watch } from 'vue'
import { getIntegrations, saveIntegration, testIntegration, type IntegrationConfig, type IntegrationKind, type IntegrationOverview, type IntegrationResult } from '../api/integrations'
import { validateBaseUrl, validateTelegram, validateAI } from '../settings/preferences'
const props = defineProps<{ kind: IntegrationKind }>()
const overview = ref<IntegrationOverview | null>(null)
const config = ref<IntegrationConfig | null>(null)
const settings = ref<Record<string, string | boolean>>({})
const secret = ref('')
const enabled = ref(false)
const clearSecret = ref(false)
const loading = ref(false)
const busy = ref(false)
const dirty = ref(false)
const error = ref('')
const notice = ref('')
const result = ref<IntegrationResult | null>(null)
const pending = ref<string | null>(null)
const localErrors = ref<string[]>([])
const telegram = computed(() => props.kind === 'telegram')
const title = computed(() => telegram.value ? 'Telegram 机器人' : 'AI 模型')
let controller: AbortController | null = null
let disposed = false
let hydrating = false
function apply(value: IntegrationConfig) {
  hydrating = true; config.value = value; settings.value = { ...value.settings }; enabled.value = value.enabled
  secret.value = ''; clearSecret.value = false; dirty.value = false
  queueMicrotask(() => { hydrating = false })
}
watch([settings, enabled, secret, clearSecret], () => { if (!hydrating) { dirty.value = true; result.value = null; notice.value = ''; localErrors.value = [] } }, { deep: true })
async function load() {
  controller?.abort(); const current = new AbortController(); controller = current; loading.value = true; error.value = ''
  const timeout = setTimeout(() => current.abort(), 8000)
  try { const value = await getIntegrations(current.signal); if (!disposed && controller === current) { overview.value = value; apply(value[props.kind]) } }
  catch (e) { if (!disposed && controller === current) error.value = current.signal.aborted ? '读取超时，请确认后端已启动。' : e instanceof Error ? e.message : '配置读取失败' }
  finally { clearTimeout(timeout); if (controller === current) { controller = null; loading.value = false } }
}
function validate() {
  if (telegram.value) {
    localErrors.value = validateTelegram(secret.value || (config.value?.secretConfigured && !clearSecret.value ? '123456789:placeholderstoredsecret' : ''), String(settings.value.chatId ?? ''))
  } else {
    localErrors.value = validateAI({ baseUrl: String(settings.value.baseUrl ?? ''), model: String(settings.value.model ?? ''),
      temperature: String(settings.value.temperature ?? ''), maxTokens: String(settings.value.maxTokens ?? '') },
      secret.value || (config.value?.secretConfigured && !clearSecret.value ? 'stored-secret-placeholder' : ''))
  }
  if (!localErrors.value.length) notice.value = '本地格式校验通过；密钥有效性需实际连接测试。'
}
function askSave() {
  error.value = ''; notice.value = ''; result.value = null
  if (!config.value) return
  if (!telegram.value) { const problem = validateBaseUrl(String(settings.value.baseUrl)); if (problem) { error.value = problem; return } }
  pending.value = 'save'
}
function askTest(action: string) { if (config.value && !dirty.value) { pending.value = action; error.value = ''; result.value = null } }
const confirmation = computed(() => {
  if (pending.value === 'save') return enabled.value
    ? telegram.value ? `保存后启用真实 Telegram 预警，后续新事件会发往 Chat ID ${settings.value.chatId}。旧版本待发送任务会取消，已发起网络请求不能保证撤回。确认？`
      : '保存后允许手动调用 AI 分析。不会自动发送行情；每次分析仍需确认。密钥将加密保存到后端，确认？'
    : '将配置及输入的新密钥提交本机后端，密钥加密保存、不回传。此操作不调用外部服务；Telegram 旧版本待发送任务会取消。确认？'
  if (pending.value === 'send') return `将向 Chat ID ${config.value?.settings.chatId} 真实发送一条测试消息（即使自动通知未启用）。确认接收人并发送？`
  if (pending.value === 'reply') return '将发送固定测试提示词给已保存的模型，可能产生服务商费用；不包含行情数据。确认调用？'
  return telegram.value ? '将使用已保存 Token 请求 Telegram getMe 验证机器人身份，不发送消息。确认联网？' : '将请求已配置服务商的模型列表，发送 API Key 鉴权。确认联网？'
})
async function execute() {
  const action = pending.value; if (!action || !config.value) return
  pending.value = null; busy.value = true; error.value = ''; notice.value = ''
  const current = new AbortController(); controller = current
  const timeout = setTimeout(() => current.abort(), 25000)
  try {
    if (action === 'save') {
      const saved = await saveIntegration(props.kind, { version: config.value.version, enabled: enabled.value, settings: Object.fromEntries(Object.entries(settings.value).map(([k, v]) => [k, typeof v === 'boolean' ? v : String(v)])),
        ...(secret.value.trim() ? { secret: secret.value.trim() } : {}), clearSecret: clearSecret.value }, current.signal)
      if (!disposed && controller === current) { apply(saved); notice.value = '服务端配置已保存，密钥已加密且不回传；未进行外部测试。' }
    } else {
      const response = await testIntegration(props.kind, action, config.value.version, current.signal)
      if (!disposed && controller === current) result.value = response
    }
    const latest = await getIntegrations(current.signal)
    if (!disposed && controller === current) overview.value = latest
  } catch (e) { if (!disposed && controller === current) error.value = current.signal.aborted ? '请求超时，外部操作结果可能未知。请先核对审计/目标消息，不要盲目重发。' : e instanceof Error ? e.message : '操作失败' }
  finally { clearTimeout(timeout); if (controller === current) { controller = null; busy.value = false }; secret.value = '' }
}
onMounted(() => { void load() })
onUnmounted(() => { disposed = true; controller?.abort(); secret.value = '' })
</script>
<template>
  <section class="panel settings-card">
    <div class="section-heading"><div><h2>{{ title }}</h2><p class="note">服务端配置 · 默认关闭 · 真实测试需明确确认</p></div><div class="test-actions"><button :disabled="busy || loading || dirty" @click="load">刷新配置</button><span class="quality-pill" :class="{ good: config?.enabled }">{{ config ? config.enabled ? '已启用' : '未启用' : '读取中' }}</span></div></div>
    <p v-if="loading" role="status">正在读取服务端配置…</p>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <button v-if="!config && !loading" @click="load">重试读取</button>
    <template v-if="config">
      <div class="integration-notice"><strong>{{ telegram ? '真实预警发送需启用开关' : 'AI 仅按需调用，不阻塞实时预警' }}</strong><p>{{ telegram ? '启用后仅发送新事件，保留 mock 记录。Telegram 不支持幂等键，网络超时等结果未知时进入人工核对，不盲目重试。' : '连接和回复测试使用已保存配置；事件分析会先确认，再将该事件证据发送给模型。模型输出不是交易建议。' }}</p></div>
      <form @submit.prevent="askSave">
        <fieldset :disabled="busy || loading" class="integration-fields">
          <label class="settings-check"><input v-model="enabled" type="checkbox" :aria-label="telegram ? '启用真实 Telegram 预警' : '允许手动 AI 事件分析'" />{{ telegram ? '启用真实 Telegram 预警' : '允许手动 AI 事件分析' }}</label>
          <div class="settings-field"><label :for="telegram ? 'telegram-token' : 'ai-key'">{{ telegram ? 'Bot Token' : 'API Key' }} <span class="field-badge">{{ config.secretConfigured ? '服务端已加密保存' : '尚未保存' }}</span></label><input :id="telegram ? 'telegram-token' : 'ai-key'" v-model="secret" type="password" autocomplete="new-password" spellcheck="false" maxlength="500" :placeholder="config.secretConfigured ? '留空保留现有密钥，不回显明文' : '输入后加密保存在服务端'" /><small>密钥不存浏览器。输入新值替换；提交后或离开页面清空输入框。</small></div>
          <label class="settings-check"><input v-model="clearSecret" type="checkbox" />删除已保存密钥（需同时停用；输入新密钥则替换）</label>
          <template v-if="telegram">
            <div class="settings-field"><label for="chat-id">接收 Chat ID</label><input id="chat-id" v-model="settings.chatId" maxlength="64" autocomplete="off" placeholder="例如 -1001234567890" /><small>当前配置的接收人即自动通知白名单；修改接收人取消旧版本队列。</small></div>
            <div class="settings-field"><label for="minimum-severity">最低通知级别</label><select id="minimum-severity" v-model="settings.minimumSeverity"><option value="P0">仅 P0 · 紧急</option><option value="P1">P1 及以上</option></select></div>
            <label class="settings-check"><input v-model="settings.includeEvidence" type="checkbox" />消息包含价格窗口与触发证据</label>
          </template>
          <template v-else>
            <div class="settings-field"><label for="ai-url">Base URL</label><input id="ai-url" v-model="settings.baseUrl" type="url" maxlength="500" autocomplete="off" /><small>服务端允许主机：{{ overview?.aiAllowedHosts.join(', ') || '无' }}；本地模型 {{ overview?.allowLocalAI ? '允许' : '禁止' }}。其他主机需在后端环境白名单配置。</small></div>
            <div class="settings-field"><label for="ai-model">模型 ID</label><input id="ai-model" v-model="settings.model" maxlength="128" autocomplete="off" placeholder="填写实际 model ID" /></div>
            <div class="settings-field-grid"><div class="settings-field"><label for="ai-temperature">Temperature</label><input id="ai-temperature" v-model="settings.temperature" type="number" min="0" max="2" step="0.1" /></div><div class="settings-field"><label for="ai-tokens">Max tokens</label><input id="ai-tokens" v-model="settings.maxTokens" type="number" min="1" max="32768" /></div></div>
          </template>
          <div class="test-box"><h3>配置检查与真实测试</h3><p class="note">测试使用服务端已保存版本。修改后请先保存，再测试；一次请求后至少间隔 10 秒。</p><div class="test-actions"><button type="button" :data-testid="telegram ? 'check-telegram' : 'check-ai'" @click="validate">{{ telegram ? '校验配置格式' : '校验模型参数' }}</button><button type="button" :disabled="dirty || !config.secretConfigured" @click="askTest('connect')">{{ telegram ? '连接机器人' : '连接测试' }}</button><button type="button" :disabled="dirty || !config.secretConfigured" @click="askTest(telegram ? 'send' : 'reply')">{{ telegram ? '发送测试消息' : '测试模型回复' }}</button></div><ul v-if="localErrors.length" role="alert" class="validation-errors"><li v-for="e in localErrors" :key="e">{{ e }}</li></ul></div>
          <div class="settings-actions"><span class="muted">{{ dirty ? '有未保存修改' : `服务端版本 v${config.version}` }}</span><button type="submit" class="primary-button">{{ busy ? '处理中…' : '保存服务端配置' }}</button></div>
        </fieldset>
      </form>
      <p v-if="notice" role="status" class="success settings-feedback">{{ notice }}</p>
      <div v-if="result" role="status" class="settings-feedback"><p>{{ result.message }}</p><p v-if="result.botUsername">机器人：@{{ result.botUsername }}</p><p v-if="result.messageId">消息编号：{{ result.messageId }}</p><pre v-if="result.reply" class="ai-reply">{{ result.reply }}</pre></div>
      <details v-if="overview?.audit?.some(a => a.kind === config?.kind)" class="integration-audit"><summary>近期操作审计</summary><ul class="alert-history"><li v-for="a in overview.audit.filter(a => a.kind === config?.kind)" :key="a.id">{{ new Date(a.createdAt).toLocaleString('zh-CN', {timeZone:'Asia/Shanghai'}) }}（UTC+8） · {{ a.action }} · {{ a.outcome }} · {{ a.detail }}</li></ul></details>
      <p class="settings-privacy">仅限本机管理。配置中的密钥由后端 AES-GCM 加密，主密钥必须单独备份；真实外部调用会向你选择的服务商发送鉴权信息。</p>
    </template>
    <div v-if="pending" role="dialog" aria-modal="true" aria-labelledby="integration-confirm-title" class="confirm-overlay" @keydown.esc="pending = null"><div class="confirm-dialog"><h3 id="integration-confirm-title">确认{{ pending === 'save' ? '保存配置' : '外部操作' }}</h3><p>{{ confirmation }}</p><div class="test-actions"><button autofocus @click="pending = null">取消</button><button class="primary-button" @click="execute">确认{{ pending === 'save' ? '保存' : '执行' }}</button></div></div></div>
  </section>
</template>

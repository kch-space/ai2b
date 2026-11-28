<script setup lang="ts">
import { computed, nextTick, onMounted, onUnmounted, ref, watch } from 'vue'
import IntegrationSettings from './IntegrationSettings.vue'
import { defaults, readPreferences, savePreferences, STORAGE_KEY } from '../settings/preferences'
const draft = ref(readPreferences())
const route = ref(window.location.hash)
const tabs = [{ key: 'display', title: '显示偏好', description: '概览刷新与列表密度', icon: '◫' },
  { key: 'telegram', title: 'Telegram 机器人', description: '真实通知配置与测试', icon: '↗' },
  { key: 'ai', title: 'AI 模型', description: '模型连接与调用测试', icon: '✧' },
  { key: 'system', title: '系统与规则', description: '运行边界与当前参数', icon: '⚙' }]
const active = computed(() => { const key = route.value.split('/')[2]; return tabs.some(t => t.key === key) ? key : 'display' })
const notice = ref('')
const saveError = ref('')
const dirty = ref(false)
function updateRoute() { route.value = window.location.hash }
watch(draft, () => { dirty.value = true; notice.value = ''; saveError.value = '' }, { deep: true })
watch(active, () => { notice.value = ''; saveError.value = '' })
function save() {
  saveError.value = ''; notice.value = ''
  try { savePreferences(draft.value); dirty.value = false; notice.value = '显示偏好已保存；返回概览或重新进入合约市场后生效。' }
  catch (cause) { saveError.value = cause instanceof Error ? cause.message : '浏览器存储不可用，配置未保存。' }
}
async function clearSavedDrafts() {
  try { localStorage.removeItem(STORAGE_KEY); draft.value = defaults(); await nextTick(); dirty.value = false; notice.value = '本地显示偏好与集成草稿已清除；未修改后端。' }
  catch { saveError.value = '浏览器存储不可用，未能清除草稿。' }
}
onMounted(() => window.addEventListener('hashchange', updateRoute))
onUnmounted(() => { window.removeEventListener('hashchange', updateRoute) })
</script>
<template>
  <section class="settings-page">
    <div class="page-heading"><div><p class="eyebrow">WORKSPACE SETTINGS</p><h1>设置</h1><p class="page-description">显示偏好现在生效，Telegram 与 AI 由服务端安全配置，真实调用需确认。</p></div><span class="tag">单用户 · 本地环境</span></div>
    <div class="settings-layout">
      <nav class="settings-nav" aria-label="设置分类"><a v-for="tab in tabs" :key="tab.key" :href="`#/settings/${tab.key}`" :class="{ active: active === tab.key }" :aria-current="active === tab.key ? 'page' : undefined"><span aria-hidden="true">{{ tab.icon }}</span><div><strong>{{ tab.title }}</strong><small>{{ tab.description }}</small></div></a></nav>
      <div class="settings-main">
        <form v-if="active === 'display'" class="panel settings-card" @submit.prevent="save">
          <div class="section-heading"><div><h2>显示偏好</h2><p class="note">仅保存在当前浏览器，不修改后端采集或预警规则。</p></div><span class="quality-pill good">可使用</span></div>
          <div class="settings-field"><label for="refresh-seconds">概览刷新间隔</label><select id="refresh-seconds" v-model.number="draft.refreshSeconds"><option :value="15">15 秒</option><option :value="30">30 秒（推荐）</option><option :value="60">60 秒</option></select><small>控制首页行情和事件摘要的读取频率，不影响后台持续采集。</small></div>
          <div class="settings-field"><label for="market-size">合约市场每页数量</label><select id="market-size" v-model.number="draft.marketPageSize"><option :value="10">10 条 · 紧凑（推荐）</option><option :value="25">25 条</option><option :value="50">50 条</option></select><small>全量合约已移出首页；在市场页也可以临时调整数量。</small></div>
          <div class="settings-field"><label>展示时区</label><div class="readonly-value">Asia/Shanghai · UTC+8</div><small>行情统一按 UTC 存储，时间展示固定为北京时间。</small></div>
          <div class="settings-actions"><span class="muted">{{ dirty ? '有未保存修改' : '仅此浏览器' }}</span><button class="primary-button" type="submit">保存显示偏好</button></div>
        </form>
        <IntegrationSettings v-else-if="active === 'telegram' || active === 'ai'" :key="active" :kind="active" />
        <section v-else class="panel settings-card"><div class="section-heading"><div><h2>系统与规则</h2><p class="note">当前代码默认值与运行边界，不是实时后端配置查询。</p></div><span class="quality-pill">只读说明</span></div>
          <div class="settings-system-grid"><article><span class="stat-label">市场数据</span><strong>Binance USDT 永续</strong><p>BTCUSDT / ETHUSDT · 闭合 1m<br>K 线调度默认 15 秒，指标默认 30 秒。</p></article><article><span class="stat-label">价格规则 v1</span><strong>5m ≥ 5% → P1</strong><p>≥ 10% → P0；≤ 2% 连续 2 个有效窗口恢复。阈值为未回测工程示例。</p></article><article><span class="stat-label">模拟通知</span><strong>MOCK · 本地 outbox</strong><p>支持去重、升级、租约回收和重试。可配置 Telegram 真实渠道。</p></article><article><span class="stat-label">访问与密钥</span><strong>仅监听 127.0.0.1</strong><p>认证尚未实现，不直接开放公网。集成写接口仅允许本机操作。</p></article></div>
          <div class="integration-notice"><strong>后端配置在哪里？</strong><p>采集与预警开关由后端 application.yml / 运行环境控制。本页暂不支持规则编辑、运行开关；集成密钥可在相应分区安全保存。</p></div><a class="text-link" href="#/alerts">查看实时规则检测状态 →</a>
        </section>
        <p v-if="notice" class="settings-feedback success" role="status">{{ notice }}</p>
        <p v-if="saveError" class="settings-feedback error" role="alert">{{ saveError }}</p>
        <p class="settings-privacy">显示偏好仅存当前浏览器；Telegram/AI 配置改为服务端保存，不读取旧浏览器集成草稿。密钥不回显、不存浏览器。</p>
        <button class="clear-drafts" type="button" @click="clearSavedDrafts">清除本地偏好与旧草稿</button>
      </div>
    </div>
  </section>
</template>

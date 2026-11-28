<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from 'vue'
import { getInstruments, syncInstruments } from '../api/instruments'
import { readPreferences } from '../settings/preferences'
import type { InstrumentPage, SyncStatus } from '../api/instruments'

const result = ref<InstrumentPage | null>(null)
const sync = ref<SyncStatus | null>(null)
const keyword = ref('')
const status = ref('')
const page = ref(1)
const pageSize = ref<number>(readPreferences().marketPageSize)
const loading = ref(false)
const syncing = ref(false)
const error = ref('')
const syncError = ref('')
const syncNotice = ref('')
let disposed = false
let listController: AbortController | null = null
let syncController: AbortController | null = null
let appliedKeyword = ''
let appliedStatus = ''
const pages = computed(() => Math.max(1, Math.ceil((result.value?.total ?? 0) / (result.value?.size ?? 25))))
const stale = computed(() => !!sync.value?.lastSuccessAt
  && Date.now() - Date.parse(sync.value.lastSuccessAt) > 2 * 60 * 60 * 1000)

function formatTime(value: string | null) {
  return value ? new Intl.DateTimeFormat('zh-CN', {
    timeZone: 'Asia/Shanghai', dateStyle: 'short', timeStyle: 'medium',
  }).format(new Date(value)) : '尚未成功同步'
}

function statusText(value: string) {
  return ({ TRADING: '交易中', PENDING_TRADING: '待上线', SETTLING: '结算中',
    CLOSE: '已关闭', NOT_IN_SNAPSHOT: '本次快照未包含' } as Record<string, string>)[value] ?? value
}

async function load(targetPage = 1) {
  listController?.abort()
  const current = new AbortController()
  listController = current
  loading.value = true
  error.value = ''
  // Do not display a prior filter's rows under a new filter or page.
  result.value = null
  const timeout = setTimeout(() => current.abort(), 10000)
  const search = keyword.value.trim()
  const selectedStatus = status.value
  try {
    const data = await getInstruments(search, selectedStatus, targetPage, current.signal, pageSize.value)
    if (disposed || listController !== current) return
    result.value = data
    page.value = data.page
    sync.value = data.sync
    appliedKeyword = search
    appliedStatus = selectedStatus
  } catch (cause) {
    if (!disposed && listController === current) error.value = current.signal.aborted
      ? '列表请求超时，请重试。' : cause instanceof Error ? cause.message : '无法读取合约列表'
  } finally {
    clearTimeout(timeout)
    if (listController === current) {
      loading.value = false
      listController = null
    }
  }
}

function changePage(targetPage: number) {
  return load(keyword.value.trim() === appliedKeyword && status.value === appliedStatus ? targetPage : 1)
}

async function synchronize() {
  if (syncing.value) return
  const current = new AbortController()
  syncController = current
  syncing.value = true
  syncError.value = ''
  syncNotice.value = ''
  const timeout = setTimeout(() => current.abort(), 30000)
  try {
    const data = await syncInstruments(current.signal)
    if (disposed) return
    sync.value = data
    syncNotice.value = '同步成功，合约列表已更新。'
    await load(1)
  } catch (cause) {
    if (!disposed) {
      syncError.value = current.signal.aborted
        ? '同步请求超时，服务端可能仍在处理，请刷新列表查看结果。'
        : cause instanceof Error ? cause.message : '合约同步失败'
      await load(page.value)
    }
  } finally {
    clearTimeout(timeout)
    if (!disposed) syncing.value = false
    syncController = null
  }
}

onMounted(() => load())
onUnmounted(() => {
  disposed = true
  listController?.abort()
  syncController?.abort()
})
</script>

<template>
  <section class="panel instruments" aria-labelledby="instruments-title">
    <div class="panel-heading">
      <div><p class="eyebrow">CONTRACT UNIVERSE</p><h2 id="instruments-title">USDT 永续合约</h2></div>
      <button data-testid="sync" :disabled="syncing || sync?.running" @click="synchronize">
        {{ syncing || sync?.running ? '同步中…' : '同步合约' }}
      </button>
    </div>
    <p class="note">精简分页展示，可选择每页 10 / 25 / 50 条。仅 BTC / ETH 已接入行情，其余为合约基础信息；价格步长与数量步长是下单精度，不是实时行情。</p>
    <div class="sync-meta" role="status" aria-live="polite">
      <span>最近成功同步（UTC+8）：{{ sync ? formatTime(sync.lastSuccessAt) : loading ? '读取中…' : '暂不可用' }}</span>
      <span v-if="sync?.running" class="tag">后台同步中</span>
      <span v-else-if="stale" class="tag">数据可能陈旧</span>
    </div>
    <p v-if="syncError || sync?.error" class="error" role="alert">{{ syncError || sync?.error }}；上次成功的数据仍保留。</p>
    <p v-if="syncNotice" class="success" role="status">{{ syncNotice }}</p>
    <form class="filters" @submit.prevent="load(1)">
      <label>合约搜索<input v-model="keyword" maxlength="64" placeholder="例如 BTCUSDT" autocomplete="off" /></label>
      <label>交易状态<select v-model="status"><option value="">全部状态</option><option value="TRADING">交易中</option><option value="PENDING_TRADING">待上线</option><option value="SETTLING">结算中</option><option value="NOT_IN_SNAPSHOT">本次快照未包含</option></select></label>
      <label>每页数量<select v-model.number="pageSize" @change="load(1)" aria-label="每页数量"><option :value="10">10 条</option><option :value="25">25 条</option><option :value="50">50 条</option></select></label>
      <button type="submit" :disabled="loading">{{ loading ? '查询中…' : '查询 / 刷新' }}</button>
    </form>
    <p v-if="error" class="error" role="alert">{{ error }}</p>
    <p v-else-if="loading" class="empty" role="status">正在读取合约列表…</p>
    <template v-else-if="result">
      <p v-if="result.items.length === 0" class="empty">{{ result.sync.lastSuccessAt ? '没有符合条件的合约，请调整筛选条件。' : '暂无合约数据，点击“同步合约”获取交易所基础信息。' }}</p>
      <div v-else class="table-scroll">
        <table>
          <caption class="sr-only">Binance USDT 永续合约基础信息</caption>
          <thead><tr><th scope="col">合约 / 基础资产</th><th scope="col">状态</th><th scope="col">价格步长（USDT）</th><th scope="col">数量步长（基础资产）</th><th scope="col">更新时间（UTC+8）</th></tr></thead>
          <tbody><tr v-for="item in result.items" :key="item.id"><td><a v-if="['BTCUSDT', 'ETHUSDT'].includes(item.symbol)" class="symbol-link mono" :href="`#/market/${item.symbol}`">{{ item.symbol }} ↗</a><strong v-else class="mono">{{ item.symbol }}</strong><small class="asset">{{ item.baseAsset }} / {{ item.quoteAsset }}</small></td><td><span class="contract-status" :class="{ trading: item.status === 'TRADING' }">{{ statusText(item.status) }}</span></td><td class="mono">{{ item.priceTickSize }}</td><td class="mono">{{ item.quantityStepSize }}</td><td>{{ formatTime(item.updatedAt) }}</td></tr></tbody>
        </table>
      </div>
      <div class="pagination"><span>共 {{ result.total }} 个合约 · 第 {{ page }} / {{ pages }} 页</span><div><button aria-label="上一页" :disabled="loading || page <= 1" @click="changePage(page - 1)">上一页</button><button aria-label="下一页" :disabled="loading || page >= pages" @click="changePage(page + 1)">下一页</button></div></div>
    </template>
  </section>
</template>

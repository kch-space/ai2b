<script setup lang="ts">
import { computed, ref } from 'vue'
import type { Kline } from '../api/klines'

const props = defineProps<{ items: Kline[]; start: string; end: string }>()
const hoveredTime = ref<number | null>(null)
const startMs = computed(() => Date.parse(props.start))
const endMs = computed(() => Date.parse(props.end))
const slots = computed(() => Math.round((endMs.value - startMs.value) / 60000) + 1)
const byTime = computed(() => new Map(props.items.map(item => [Date.parse(item.openTime), item])))
const selected = computed(() => hoveredTime.value === null ? props.items.at(-1) : byTime.value.get(hoveredTime.value))
const bounds = computed(() => {
  const low = Math.min(...props.items.map(item => Number(item.low)))
  const high = Math.max(...props.items.map(item => Number(item.high)))
  const pad = Math.max((high - low) * .06, high * .00001)
  return { low: low - pad, high: high + pad }
})
const maxVolume = computed(() => Math.max(1, ...props.items.map(item => Number(item.volume))))
const x = (time: string | number) => 88 + ((typeof time === 'string' ? Date.parse(time) : time) - startMs.value) / 60000 / slots.value * 890 + 445 / slots.value
const y = (price: string | number) => 224 - (Number(price) - bounds.value.low) / (bounds.value.high - bounds.value.low) * 200
const bodyWidth = computed(() => Math.max(.6, Math.min(10, 890 / slots.value * .65)))
const grid = computed(() => Array.from({ length: 5 }, (_, index) => bounds.value.low + index * (bounds.value.high - bounds.value.low) / 4))
const formatTime = (value: string | number) => new Intl.DateTimeFormat('zh-CN', {
  timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false,
}).format(new Date(value))
const priceLabel = (value: number) => new Intl.NumberFormat('en-US', { maximumFractionDigits: 4 }).format(value)

function point(event: MouseEvent) {
  const rect = event.currentTarget instanceof SVGElement ? event.currentTarget.getBoundingClientRect() : null
  if (!rect) return
  const pixel = (event.clientX - rect.left) / rect.width * 1000
  const index = Math.max(0, Math.min(slots.value - 1, Math.floor((pixel - 88) / 890 * slots.value)))
  hoveredTime.value = startMs.value + index * 60000
}
function key(event: KeyboardEvent) {
  if (!['ArrowLeft', 'ArrowRight'].includes(event.key)) return
  event.preventDefault()
  hoveredTime.value = Math.max(startMs.value, Math.min(endMs.value,
    (hoveredTime.value ?? endMs.value) + (event.key === 'ArrowLeft' ? -60000 : 60000)))
}
</script>

<template>
  <div class="candle-readout" aria-live="polite">
    <template v-if="selected"><span>{{ formatTime(selected.openTime) }}（UTC+8）</span><span>开 <b>{{ selected.open }}</b></span><span>高 <b>{{ selected.high }}</b></span><span>低 <b>{{ selected.low }}</b></span><span>收 <b>{{ selected.close }}</b></span><span>成交量 <b>{{ selected.volume }}</b></span></template>
    <span v-else>该分钟数据缺失，未生成 K 线。</span>
  </div>
  <div class="chart-scroll">
    <svg class="candle-chart" viewBox="0 0 1000 340" role="img" tabindex="0" aria-label="闭合一分钟 K 线与基础资产成交量图，左右方向键查看各分钟；空白位置表示数据缺失" @mousemove="point" @mouseleave="hoveredTime = null" @keydown="key">
      <g v-for="price in grid" :key="price"><line x1="88" x2="978" :y1="y(price)" :y2="y(price)" class="chart-grid" /><text x="76" :y="y(price) + 4" text-anchor="end">{{ priceLabel(price) }}</text></g>
      <g v-for="item in items" :key="item.openTime" :class="Number(item.close) >= Number(item.open) ? 'candle-up' : 'candle-down'">
        <line :x1="x(item.openTime)" :x2="x(item.openTime)" :y1="y(item.high)" :y2="y(item.low)" />
        <rect :x="x(item.openTime) - bodyWidth / 2" :y="Math.min(y(item.open), y(item.close))" :width="bodyWidth" :height="Math.max(1, Math.abs(y(item.open) - y(item.close)))" />
        <rect class="volume-bar" :x="x(item.openTime) - bodyWidth / 2" :y="300 - Number(item.volume) / maxVolume * 44" :width="bodyWidth" :height="Math.max(.3, Number(item.volume) / maxVolume * 44)" />
      </g>
      <text x="88" y="247">成交量（基础资产）</text>
      <line v-if="hoveredTime !== null" :x1="x(hoveredTime)" :x2="x(hoveredTime)" y1="20" y2="304" class="chart-crosshair" />
      <text x="88" y="326">{{ formatTime(start) }}</text><text x="533" y="326" text-anchor="middle">{{ formatTime((startMs + endMs) / 2) }}</text><text x="978" y="326" text-anchor="end">{{ formatTime(end) }}（UTC+8）</text>
    </svg>
  </div>
  <p class="note">仅展示已闭合 1m K 线；缺失分钟留空。移动端可横向滑动图表。</p>
</template>

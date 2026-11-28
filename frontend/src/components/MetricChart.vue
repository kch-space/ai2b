<script setup lang="ts">
import { computed } from 'vue'
import type { MetricPoint } from '../api/metrics'
const props = defineProps<{ items: MetricPoint[]; field: string; label: string; start: string; end: string }>()
const bounds = computed(() => {
  const values = props.items.map(p => Number(p.values[props.field]))
  const min = Math.min(...values), max = Math.max(...values)
  const pad = Math.max((max - min) * .08, Math.abs(max) * .0001, .000001)
  return { min: min - pad, max: max + pad }
})
const x = (p: MetricPoint) => 70 + (Date.parse(p.eventTime) - Date.parse(props.start)) / Math.max(1, Date.parse(props.end) - Date.parse(props.start)) * 340
const y = (p: MetricPoint) => 132 - (Number(p.values[props.field]) - bounds.value.min) / (bounds.value.max - bounds.value.min) * 108
const segments = computed(() => {
  const result: MetricPoint[][] = []
  for (const point of props.items) {
    const previous = result.at(-1)?.at(-1)
    if (!previous || Date.parse(point.eventTime) - Date.parse(previous.eventTime) !== 300000) result.push([])
    result.at(-1)!.push(point)
  }
  return result
})
const number = (v: number) => new Intl.NumberFormat('en-US', { notation: Math.abs(v) >= 10000 ? 'compact' : 'standard', maximumFractionDigits: Math.abs(v) >= 10000 ? 2 : 4 }).format(v)
const time = (v: string) => new Intl.DateTimeFormat('zh-CN', { timeZone: 'Asia/Shanghai', hour: '2-digit', minute: '2-digit', hour12: false }).format(new Date(v))
</script>
<template>
  <div class="metric-chart-scroll">
    <svg v-if="items.length" class="metric-chart" viewBox="0 0 420 164" role="img" :aria-label="label + '，5 分钟采样，缺失区间断开'">
      <line x1="70" x2="410" y1="24" y2="24" class="chart-grid" /><line x1="70" x2="410" y1="132" y2="132" class="chart-grid" />
      <text x="62" y="28" text-anchor="end">{{ number(bounds.max) }}</text><text x="62" y="136" text-anchor="end">{{ number(bounds.min) }}</text>
      <polyline v-for="(segment, index) in segments" :key="index" :points="segment.map(p => `${x(p)},${y(p)}`).join(' ')" class="metric-line" />
      <circle v-for="item in items" :key="item.eventTime" :cx="x(item)" :cy="y(item)" r="1.5" class="metric-point"><title>{{ time(item.eventTime) }} · {{ item.values[field] }}</title></circle>
      <text x="70" y="156">{{ time(start) }}</text><text x="410" y="156" text-anchor="end">{{ time(end) }}（UTC+8）</text>
    </svg>
  </div>
</template>

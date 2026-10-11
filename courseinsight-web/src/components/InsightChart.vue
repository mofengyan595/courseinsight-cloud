<script setup lang="ts">
import { onMounted, onScopeDispose, ref, watch } from 'vue'
import * as echarts from 'echarts/core'
import { PieChart, BarChart } from 'echarts/charts'
import {
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AriaComponent,
} from 'echarts/components'
import { CanvasRenderer } from 'echarts/renderers'
echarts.use([
  PieChart,
  BarChart,
  GridComponent,
  TooltipComponent,
  LegendComponent,
  AriaComponent,
  CanvasRenderer,
])
const props = defineProps<{
  title: string
  kind: 'pie' | 'bar'
  values: { name: string; value: number; color: string }[]
}>()
const container = ref<HTMLDivElement>()
let chart: echarts.ECharts | undefined
let observer: ResizeObserver | undefined
function render() {
  chart?.setOption(
    {
      aria: { enabled: true },
      tooltip: { trigger: props.kind === 'pie' ? 'item' : 'axis' },
      color: props.values.map((value) => value.color),
      legend:
        props.kind === 'pie'
          ? { bottom: 4, icon: 'circle', textStyle: { color: '#596076' } }
          : undefined,
      grid: { top: 25, left: 35, right: 20, bottom: 35, containLabel: true },
      xAxis:
        props.kind === 'bar'
          ? {
              type: 'category',
              data: props.values.map((v) => v.name),
              axisTick: { show: false },
              axisLine: { show: false },
            }
          : undefined,
      yAxis:
        props.kind === 'bar'
          ? {
              type: 'value',
              minInterval: 1,
              splitLine: { lineStyle: { color: '#eeedf4' } },
            }
          : undefined,
      series: [
        {
          type: props.kind,
          ...(props.kind === 'pie'
            ? {
                radius: ['48%', '70%'],
                center: ['50%', '44%'],
                label: { show: false },
                itemStyle: {
                  borderColor: '#fff',
                  borderWidth: 4,
                  borderRadius: 6,
                },
              }
            : { barWidth: '36%', itemStyle: { borderRadius: [6, 6, 0, 0] } }),
          data: props.values.map((v) => ({
            name: v.name,
            value: v.value,
            itemStyle: { color: v.color },
          })),
          animationDuration: 200,
        },
      ],
    },
    { notMerge: true },
  )
}
onMounted(() => {
  if (!container.value) return
  chart = echarts.init(container.value)
  render()
  observer = new ResizeObserver(() => chart?.resize())
  observer.observe(container.value)
})
watch(() => props.values, render, { deep: true })
onScopeDispose(() => {
  observer?.disconnect()
  chart?.dispose()
})
</script>
<template>
  <div
    ref="container"
    class="insight-chart"
    role="img"
    :aria-label="`${title}：${values.map((v) => `${v.name} ${v.value}`).join('，')}`"
  ></div>
</template>

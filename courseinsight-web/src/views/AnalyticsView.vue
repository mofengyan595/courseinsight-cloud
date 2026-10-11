<script setup lang="ts">
import { computed, ref, watch } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import CoursePicker from '../components/CoursePicker.vue'
import InsightChart from '../components/InsightChart.vue'
import PageHeading from '../components/PageHeading.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
const route = useRoute()
const router = useRouter()
const initial = Number(route.query.courseId)
const courseId = ref<number | undefined>(
  Number.isSafeInteger(initial) && initial > 0 ? initial : undefined,
)
const { data, loading, error, reload } = useResource(
  (signal) =>
    courseId.value
      ? api.analytics(courseId.value, signal)
      : Promise.resolve(undefined),
  [courseId],
)
watch(courseId, (value) => {
  void router.replace({ query: value ? { courseId: value } : {} })
})
const charts = computed(() =>
  data.value
    ? [
        {
          title: '评价情感分布',
          subtitle: `已分析 ${data.value.sentiments.total} 条`,
          kind: 'pie' as const,
          values: [
            {
              name: '积极',
              value: data.value.sentiments.positive,
              color: '#29a77f',
            },
            {
              name: '中性',
              value: data.value.sentiments.neutral,
              color: '#a59bc9',
            },
            {
              name: '消极',
              value: data.value.sentiments.negative,
              color: '#d95c59',
            },
          ],
        },
        {
          title: '分析任务执行情况',
          subtitle: `共 ${data.value.tasks.total} 个任务`,
          kind: 'bar' as const,
          values: [
            { name: '等待', value: data.value.tasks.waiting, color: '#a59bc9' },
            {
              name: '处理中',
              value: data.value.tasks.processing,
              color: '#6d4aef',
            },
            { name: '成功', value: data.value.tasks.success, color: '#29a77f' },
            { name: '失败', value: data.value.tasks.failed, color: '#d95c59' },
          ],
        },
        {
          title: '风险等级分布',
          subtitle: '基于后端已生成的分析结果',
          kind: 'pie' as const,
          values: [
            { name: '高风险', value: data.value.risks.high, color: '#d95c59' },
            {
              name: '中风险',
              value: data.value.risks.middle,
              color: '#e69a42',
            },
            { name: '低风险', value: data.value.risks.low, color: '#29a77f' },
            {
              name: '未分类',
              value: data.value.risks.unclassified,
              color: '#a59bc9',
            },
          ],
        },
      ]
    : [],
)
</script>
<template>
  <PageHeading
    title="数据看板"
    description="用真实评价数据，了解课程反馈与分析执行情况。"
  >
    <el-button :disabled="!courseId" :loading="loading" @click="reload">
      刷新数据
    </el-button>
  </PageHeading>
  <section class="section-card">
    <h2>选择查看的课程</h2>
    <CoursePicker v-model="courseId" />
  </section>
  <ErrorNotice :message="error" @retry="reload" />
  <el-skeleton v-if="loading && courseId" :rows="6" animated />
  <el-empty
    v-else-if="!courseId"
    description="选择一门课程，查看它的评价与分析概览"
  />
  <template v-else-if="data">
    <div class="metric-grid">
      <div class="metric-card">
        <span>评论总数</span>
        <strong>
          {{ data.totalComments }}
          <small>条</small>
        </strong>
        <p>当前课程的有效评价</p>
      </div>
      <div class="metric-card">
        <span>平均评分</span>
        <strong>
          {{ data.totalComments ? data.averageRating.toFixed(2) : '—' }}
          <small>/ 5</small>
        </strong>
        <p>{{ data.totalComments ? '基于实际提交评分' : '暂无评分数据' }}</p>
      </div>
      <div class="metric-card">
        <span>已分析结果</span>
        <strong>
          {{ data.sentiments.total }}
          <small>条</small>
        </strong>
        <p>已生成结构化分析</p>
      </div>
      <div class="metric-card">
        <span>任务成功比例</span>
        <strong>
          {{ data.tasks.total ? `${data.tasks.completionPercentage}%` : '—' }}
        </strong>
        <p>成功任务 / 全部任务</p>
      </div>
    </div>
    <div class="chart-grid">
      <section v-for="chart in charts" :key="chart.title" class="section-card">
        <h2>{{ chart.title }}</h2>
        <p class="muted">{{ chart.subtitle }}</p>
        <InsightChart
          v-if="chart.values.some((v) => v.value > 0)"
          :title="chart.title"
          :kind="chart.kind"
          :values="chart.values"
        />
        <el-empty
          v-else
          description="暂无统计数据，提交评价或上传 CSV 后再来查看"
          :image-size="100"
        />
        <div class="chart-data">
          <span v-for="value in chart.values" :key="value.name">
            <i :style="{ background: value.color }"></i>
            {{ value.name }}
            <b>{{ value.value }}</b>
          </span>
        </div>
      </section>
    </div>
  </template>
</template>

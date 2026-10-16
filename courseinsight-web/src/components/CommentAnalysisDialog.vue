<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import * as api from '../api'
import { errorMessage } from '../api/http'
import type { CommentAnalysis, JsonValue } from '../types/api'
import { createPoller } from '../composables/poller'
import { dateLabel, riskLabel, sentimentLabel } from '../utils/format'
import ErrorNotice from './ErrorNotice.vue'

const props = defineProps<{ commentId: number }>()
const emit = defineEmits<{ close: [] }>()
const data = ref<CommentAnalysis>()
const error = ref('')
const loading = ref(true)
const paused = ref(false)
const result = computed(() => data.value?.result)
function record(value: JsonValue | undefined): { [key: string]: JsonValue } {
  return value && typeof value === 'object' && !Array.isArray(value)
    ? value
    : {}
}
function text(value: JsonValue | undefined): string {
  return typeof value === 'string' ? value : ''
}
function labels(value: JsonValue | undefined): string[] {
  const values = Array.isArray(value) ? value : [value]
  return values.filter(
    (item): item is string => typeof item === 'string' && !!item.trim(),
  )
}
function entries(value: JsonValue | undefined) {
  return (Array.isArray(value) ? value : []).map((item) => record(item))
}
const advice = computed(() => record(result.value?.advice))
const summary = computed(() => text(advice.value.summary))
const suggestions = computed(() => entries(advice.value.suggestions))
const problems = computed(() => entries(advice.value.problems))
const evidence = computed(() => entries(result.value?.topicEvidence))
const keywords = computed(() => labels(result.value?.keywords))
const topics = computed(() => labels(result.value?.topics))
const adviceText = computed(() => labels(result.value?.advice))
const adviceSource = computed(
  () => result.value?.adviceSource || text(advice.value.source),
)
const confidence = computed(() => {
  const value = result.value?.confidence
  return typeof value === 'number' && Number.isFinite(value)
    ? Math.min(100, Math.max(0, value * 100))
    : null
})
function actionLabel(value: JsonValue | undefined) {
  return (
    { maintain: '保持优势', improve: '建议优化', priority: '优先改进' }[
      text(value)
    ] || '改进建议'
  )
}
const detailsOpen = ref(false)
const pending = computed(
  () =>
    data.value && ['WAITING', 'PROCESSING'].includes(data.value.task.status),
)
const taskLabel = computed(() =>
  data.value
    ? {
        WAITING: '等待分析',
        PROCESSING: '分析中',
        SUCCESS: '分析完成',
        FAILED: '分析失败',
      }[data.value.task.status]
    : '',
)
const poller = createPoller({
  fetch: (signal) => api.commentAnalysis(props.commentId, signal),
  terminal: (value) => ['SUCCESS', 'FAILED'].includes(value.task.status),
  onData: (value) => {
    data.value = value
    loading.value = false
    error.value = ''
    paused.value = false
  },
  onError: (err, stopped) => {
    error.value = errorMessage(err)
    loading.value = false
    paused.value = stopped
  },
})
function refresh() {
  error.value = ''
  loading.value = true
  paused.value = false
  poller.start()
}
watch(
  () => props.commentId,
  () => {
    poller.stop()
    data.value = undefined
    detailsOpen.value = false
    refresh()
  },
  { immediate: true, flush: 'sync' },
)
function close() {
  poller.stop()
  emit('close')
}
onScopeDispose(poller.stop)
</script>
<template>
  <el-dialog
    :model-value="true"
    title="评价分析"
    width="760px"
    align-center
    modal-class="workspace-modal"
    class="analysis-dialog"
    :close-on-click-modal="false"
    @close="close"
  >
    <div class="analysis-report">
      <ErrorNotice :message="error" @retry="refresh" />
      <p v-if="error" class="field-hint">
        {{
          paused
            ? '自动查询已暂停，请检查网络或权限后手动刷新。'
            : '查询失败，稍后自动重试；连续失败 3 次后暂停。'
        }}
      </p>
      <el-skeleton v-if="loading && !data" :rows="6" animated />
      <template v-if="data">
        <section class="review-card" aria-label="原始评价">
          <div class="report-heading">
            <span class="section-label">匿名课程评价</span>
            <el-tag
              :type="
                data.task.status === 'SUCCESS'
                  ? 'success'
                  : data.task.status === 'FAILED'
                    ? 'danger'
                    : 'info'
              "
              size="small"
            >
              {{ taskLabel }}
            </el-tag>
          </div>
          <blockquote>{{ data.comment.content }}</blockquote>
          <div class="review-meta">
            <span class="review-rating">
              <span aria-hidden="true">★</span>
              {{ data.comment.rating }}
              <small>/ 5</small>
            </span>
            <time>{{ dateLabel(data.comment.createdAt) }}</time>
          </div>
        </section>
        <el-alert
          v-if="pending"
          title="分析中"
          description="评价正在等待处理或分析，结果返回后会自动更新。"
          type="info"
          :closable="false"
          show-icon
        />
        <el-alert
          v-else-if="data.task.status === 'FAILED'"
          title="分析失败"
          :description="
            data.task.failureReason ||
            '暂未提供失败原因，请稍后刷新或联系管理员。'
          "
          type="error"
          :closable="false"
          show-icon
        />
        <template v-if="result">
          <section class="overview-card" aria-label="分析概览">
            <div class="report-heading">
              <h3>分析概览</h3>
              <span class="subtle-label">基于这条评价</span>
            </div>
            <p v-if="summary" class="summary-text">{{ summary }}</p>
            <div class="analysis-metrics">
              <div>
                <span>情感倾向</span>
                <strong :class="['sentiment', result.sentiment]">
                  {{ sentimentLabel(result.sentiment) }}
                </strong>
              </div>
              <div>
                <span>置信度</span>
                <strong>
                  {{ confidence === null ? '—' : `${confidence.toFixed(1)}%` }}
                </strong>
                <div
                  v-if="confidence !== null"
                  class="confidence-track"
                  aria-hidden="true"
                >
                  <i :style="{ width: `${confidence}%` }" />
                </div>
              </div>
              <div>
                <span>关注程度</span>
                <strong class="risk" :class="result.riskLevel">
                  {{ riskLabel(result.riskLevel) }}
                </strong>
              </div>
            </div>
            <div v-if="keywords.length" class="keyword-row">
              <span class="subtle-label">关键词</span>
              <span
                v-for="(keyword, index) in keywords"
                :key="index"
                class="keyword-chip"
              >
                {{ keyword }}
              </span>
            </div>
          </section>
          <section class="report-section" aria-labelledby="advice-heading">
            <div class="report-heading">
              <h3 id="advice-heading">教学改进建议</h3>
              <span class="source-badge">
                {{
                  adviceSource === 'llm_api'
                    ? '大模型建议'
                    : adviceSource === 'local_fallback'
                      ? '本地建议'
                      : '分析建议'
                }}
              </span>
            </div>
            <p v-if="adviceSource === 'local_fallback'" class="field-hint">
              本次使用本地建议，未获得大模型生成结果。
            </p>
            <div v-if="suggestions.length" class="suggestion-list">
              <article
                v-for="(item, index) in suggestions"
                :key="index"
                class="suggestion-card"
              >
                <span class="suggestion-number">
                  {{ String(index + 1).padStart(2, '0') }}
                </span>
                <div class="suggestion-content">
                  <div class="suggestion-heading">
                    <h4>{{ text(item.aspect) || '教学改进' }}</h4>
                    <span
                      class="action-badge"
                      :class="text(item.actionType || item.action_type)"
                    >
                      {{ actionLabel(item.actionType || item.action_type) }}
                    </span>
                  </div>
                  <p>{{ text(item.suggestion) }}</p>
                  <blockquote v-if="text(item.evidence)">
                    <span>依据</span>
                    {{ text(item.evidence) }}
                  </blockquote>
                </div>
              </article>
            </div>
            <p v-else-if="!adviceText.length" class="empty-copy">
              {{ summary ? '本次分析未返回具体行动建议。' : '暂无教学建议。' }}
            </p>
            <p
              v-for="(item, index) in adviceText"
              :key="index"
              class="summary-text"
            >
              {{ item }}
            </p>
            <div v-if="problems.length" class="problem-list">
              <h4>值得关注</h4>
              <article v-for="(item, index) in problems" :key="index">
                <strong>{{ text(item.aspect) }}</strong>
                <p>{{ text(item.description) }}</p>
                <blockquote v-if="text(item.evidence)">
                  {{ text(item.evidence) }}
                </blockquote>
              </article>
            </div>
          </section>
          <section class="report-section" aria-labelledby="evidence-heading">
            <div class="report-heading">
              <h3 id="evidence-heading">维度与原文证据</h3>
              <span class="subtle-label">回到学生的原话</span>
            </div>
            <div v-if="topics.length" class="topic-row">
              <span
                v-for="(topic, index) in topics"
                :key="index"
                class="topic-chip"
              >
                {{ topic }}
              </span>
            </div>
            <div v-if="evidence.length" class="evidence-list">
              <article v-for="(item, index) in evidence" :key="index">
                <h4>{{ text(item.aspect) || '评价原文' }}</h4>
                <blockquote>
                  {{ text(item.evidence) || '未提供原文片段' }}
                </blockquote>
              </article>
            </div>
            <p v-else class="empty-copy">暂无维度证据。</p>
          </section>
        </template>
        <details
          :open="detailsOpen"
          class="technical-details"
          @toggle="detailsOpen = ($event.target as HTMLDetailsElement).open"
        >
          <summary>分析与运行信息</summary>
          <dl class="result-details">
            <dt>任务 ID</dt>
            <dd>{{ data.task.id }}</dd>
            <dt>任务状态 / 重试次数</dt>
            <dd>{{ taskLabel }} / {{ data.task.retryCount }} 次</dd>
            <dt>任务开始 / 完成时间</dt>
            <dd>
              {{ dateLabel(data.task.startedAt) }} /
              {{ dateLabel(data.task.completedAt) }}
            </dd>
            <template v-if="data.task.failureReason">
              <dt>最近失败信息</dt>
              <dd>{{ data.task.failureReason }}</dd>
            </template>
            <template v-if="data.task.deadLetteredAt">
              <dt>死信时间</dt>
              <dd>{{ dateLabel(data.task.deadLetteredAt) }}</dd>
            </template>
            <template v-if="result">
              <dt>分析来源</dt>
              <dd>
                语言：{{ result.language || '—' }}
                <br />
                情感来源：{{ result.sentimentSource || '—' }}
                <br />
                运行设备：{{ result.sentimentDevice || '—' }}
                <br />
                建议来源：{{ result.adviceSource || '—' }}
              </dd>
              <dt>长文本处理 / 截断</dt>
              <dd>
                {{
                  result.longTextHandled === null
                    ? '—'
                    : result.longTextHandled
                      ? '是'
                      : '否'
                }}
                /
                {{
                  result.longTextTruncated === null
                    ? '—'
                    : result.longTextTruncated
                      ? '是'
                      : '否'
                }}
              </dd>
              <dt>结果保存时间</dt>
              <dd>{{ dateLabel(result.createdAt) }}</dd>
            </template>
          </dl>
          <details v-if="result" class="raw-details">
            <summary>原始分析数据</summary>
            <pre>{{ JSON.stringify(result, null, 2) }}</pre>
          </details>
        </details>
      </template>
    </div>
    <template #footer>
      <el-button @click="close">关闭</el-button>
      <el-button :loading="loading" @click="refresh">刷新分析</el-button>
    </template>
  </el-dialog>
</template>
<style scoped>
.analysis-report {
  color: #30334a;
  max-height: 68vh;
  overflow-y: auto;
  overscroll-behavior: contain;
  padding-right: 8px;
}
.analysis-report h3 {
  margin: 0;
  font-size: 16px;
  font-weight: 600;
}
.analysis-report h4 {
  margin: 0;
  font-size: 14px;
  line-height: 1.6;
}
.report-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  margin-bottom: 16px;
}
.section-label {
  font-size: 12px;
  font-weight: 600;
  color: #74788e;
}
.subtle-label {
  font-size: 12px;
  color: #86899b;
}
.review-card {
  padding: 20px 22px;
  border: 1px solid #ecebf3;
  border-radius: 14px;
  background: #fafafd;
  margin-bottom: 22px;
}
.review-card blockquote {
  margin: 0 0 18px;
  font-size: 15px;
  line-height: 1.9;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.review-meta {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 12px;
  font-size: 12px;
  color: #858899;
  flex-wrap: wrap;
}
.review-rating {
  font-size: 16px;
  font-weight: 600;
  color: #4c465f;
}
.review-rating > span {
  color: #cf983e;
  margin-right: 5px;
}
.review-rating small {
  color: #93909e;
  font-size: 12px;
  font-weight: 400;
}
.overview-card {
  background: #f5f2fc;
  border: 1px solid #e8e1f7;
  border-radius: 14px;
  padding: 22px;
  margin: 22px 0 28px;
}
.summary-text {
  font-size: 15px;
  line-height: 1.9;
  color: #43365f;
  margin: 0 0 22px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.analysis-metrics {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  gap: 16px;
}
.analysis-metrics > div {
  min-width: 0;
}
.analysis-metrics > div > span {
  display: block;
  font-size: 12px;
  color: #827994;
  margin-bottom: 9px;
}
.analysis-metrics strong {
  font-size: 22px;
  font-weight: 600;
  color: #503d72;
}
.analysis-metrics .positive {
  color: #237b61;
}
.analysis-metrics .negative {
  color: #b34e4c;
}
.analysis-metrics .neutral {
  color: #8b6b2d;
}
.analysis-metrics .risk {
  font-size: 17px;
}
.risk.high {
  color: #b34e4c;
}
.risk.middle {
  color: #946c2b;
}
.risk.low {
  color: #237b61;
}
.confidence-track {
  max-width: 96px;
  height: 4px;
  margin-top: 9px;
  background: #e4dcef;
  border-radius: 4px;
  overflow: hidden;
}
.confidence-track i {
  display: block;
  height: 100%;
  background: #9178d3;
  border-radius: 4px;
}
.keyword-row {
  display: flex;
  align-items: center;
  flex-wrap: wrap;
  gap: 7px;
  border-top: 1px solid #e7e0f2;
  margin-top: 20px;
  padding-top: 16px;
}
.keyword-row > .subtle-label {
  margin-right: 5px;
}
.keyword-chip {
  font-size: 12px;
  color: #75628f;
  background: #fff9;
  padding: 4px 9px;
  border-radius: 6px;
  overflow-wrap: anywhere;
}
.report-section {
  margin-bottom: 28px;
}
.source-badge {
  color: #6e52a5;
  background: #f2eef9;
  font-size: 11px;
  padding: 4px 9px;
  border-radius: 5px;
  flex-shrink: 0;
}
.suggestion-list {
  display: grid;
  gap: 12px;
}
.suggestion-card {
  display: flex;
  align-items: flex-start;
  gap: 14px;
  border: 1px solid #e8e4f0;
  border-radius: 12px;
  padding: 18px;
}
.suggestion-number {
  font-size: 12px;
  font-weight: 600;
  padding: 6px 7px;
  border-radius: 7px;
  color: #7659b0;
  background: #f2eef9;
}
.suggestion-content {
  min-width: 0;
  flex: 1;
}
.suggestion-heading {
  display: flex;
  flex-wrap: wrap;
  align-items: center;
  gap: 8px;
  margin-bottom: 8px;
}
.action-badge {
  font-size: 11px;
  color: #7c6a97;
  background: #f5f2fa;
  border-radius: 4px;
  padding: 2px 6px;
}
.action-badge.maintain {
  color: #317c63;
  background: #edf7f2;
}
.action-badge.priority {
  color: #ac5651;
  background: #fcf0ed;
}
.suggestion-content p {
  margin: 0;
  line-height: 1.85;
  color: #535367;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.suggestion-content blockquote {
  margin: 12px 0 0;
  color: #858394;
  font-size: 12px;
  line-height: 1.8;
  border-left: 2px solid #dcd2ed;
  padding-left: 10px;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.suggestion-content blockquote span {
  color: #a099ac;
  margin-right: 8px;
}
.problem-list {
  border-top: 1px solid #efedf5;
  margin-top: 20px;
  padding-top: 18px;
}
.problem-list > h4 {
  color: #867790;
  margin-bottom: 12px;
}
.problem-list article {
  margin-bottom: 14px;
}
.problem-list strong {
  font-size: 13px;
}
.problem-list p {
  font-size: 13px;
  margin: 4px 0;
  color: #737285;
}
.problem-list blockquote {
  margin: 4px 0;
  font-size: 12px;
  color: #93909f;
  overflow-wrap: anywhere;
}
.topic-row {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  margin-bottom: 16px;
}
.topic-chip {
  font-size: 12px;
  padding: 5px 10px;
  border: 1px solid #e7e1f2;
  color: #7b659f;
  border-radius: 6px;
}
.evidence-list {
  display: grid;
  gap: 12px;
}
.evidence-list article {
  padding: 14px 16px;
  background: #fafafd;
  border-radius: 8px;
}
.evidence-list h4 {
  font-size: 12px;
  color: #827291;
  margin-bottom: 6px;
}
.evidence-list blockquote {
  margin: 0;
  font-size: 13px;
  color: #646276;
  line-height: 1.85;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
}
.empty-copy {
  margin: 12px 0;
  font-size: 13px;
  color: #9993a4;
}
.technical-details {
  border-top: 1px solid #eeebf4;
  padding: 18px 0 4px;
}
.technical-details > summary {
  cursor: pointer;
  font-size: 12px;
  color: #858094;
  padding: 4px 0;
}
.technical-details .result-details {
  margin: 20px 0 0;
}
.technical-details .result-details dt {
  font-size: 11px;
}
.technical-details .result-details dd {
  font-size: 12px;
  margin-bottom: 16px;
}
.raw-details summary {
  cursor: pointer;
  font-size: 12px;
  color: #858094;
}
.raw-details pre {
  font-size: 11px;
  line-height: 1.7;
  white-space: pre-wrap;
  overflow-wrap: anywhere;
  background: #f8f7fb;
  border-radius: 8px;
  padding: 12px;
}
@media (max-width: 600px) {
  .review-card,
  .overview-card {
    padding: 17px;
  }
  .analysis-metrics {
    gap: 9px;
  }
  .analysis-metrics strong {
    font-size: 20px;
  }
  .analysis-metrics .risk {
    font-size: 15px;
  }
  .suggestion-card {
    padding: 14px;
    gap: 10px;
  }
  .report-heading {
    flex-wrap: wrap;
    gap: 8px;
  }
}
</style>

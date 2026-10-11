<script setup lang="ts">
import { computed, onScopeDispose, ref, watch } from 'vue'
import * as api from '../api'
import { errorMessage } from '../api/http'
import type { CommentAnalysis } from '../types/api'
import { createPoller } from '../composables/poller'
import {
  dateLabel,
  jsonLabel,
  riskLabel,
  sentimentLabel,
} from '../utils/format'
import ErrorNotice from './ErrorNotice.vue'

const props = defineProps<{ commentId: number }>()
const emit = defineEmits<{ close: [] }>()
const data = ref<CommentAnalysis>()
const error = ref('')
const loading = ref(true)
const paused = ref(false)
const result = computed(() => data.value?.result)
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
  <el-drawer
    :model-value="true"
    title="评价分析"
    size="min(640px, 100%)"
    @close="close"
  >
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
      <div class="section-toolbar">
        <strong>匿名课程评价</strong>
        <el-tag
          :type="
            data.task.status === 'SUCCESS'
              ? 'success'
              : data.task.status === 'FAILED'
                ? 'danger'
                : 'info'
          "
        >
          {{ taskLabel }}
        </el-tag>
      </div>
      <p class="preserve-text">{{ data.comment.content }}</p>
      <p>
        评分：{{ data.comment.rating }} / 5 ·
        {{ dateLabel(data.comment.createdAt) }}
      </p>
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
      <dl class="result-details">
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
        <template v-if="result">
          <dt>情感倾向 / 置信度</dt>
          <dd>
            {{ sentimentLabel(result.sentiment) }} /
            {{
              result.confidence === null
                ? '—'
                : `${(result.confidence * 100).toFixed(1)}%`
            }}
          </dd>
          <dt>关键词</dt>
          <dd class="preserve-text">{{ jsonLabel(result.keywords) }}</dd>
          <dt>课程维度</dt>
          <dd class="preserve-text">{{ jsonLabel(result.topics) }}</dd>
          <dt>原文证据</dt>
          <dd class="preserve-text">{{ jsonLabel(result.topicEvidence) }}</dd>
          <dt>教学改进建议</dt>
          <dd class="preserve-text">{{ jsonLabel(result.advice) }}</dd>
          <dt>风险等级</dt>
          <dd>{{ riskLabel(result.riskLevel) }}</dd>
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
    </template>
    <template #footer>
      <el-button :loading="loading" @click="refresh">刷新分析</el-button>
    </template>
  </el-drawer>
</template>

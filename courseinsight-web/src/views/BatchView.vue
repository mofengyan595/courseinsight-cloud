<script setup lang="ts">
import { computed, onScopeDispose, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage, ElMessageBox } from 'element-plus'
import { Download, Refresh } from '@element-plus/icons-vue'
import * as api from '../api'
import { errorMessage } from '../api/http'
import type { BatchProgress, BatchResult } from '../types/api'
import { createPoller, isTerminal } from '../composables/poller'
import { useResource } from '../composables/useResource'
import { useAction } from '../composables/useAction'
import {
  dateLabel,
  jsonLabel,
  riskLabel,
  sentimentLabel,
  statusLabel,
} from '../utils/format'
import PageHeading from '../components/PageHeading.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import PaginationBar from '../components/PaginationBar.vue'

const batchId = String(useRoute().params.batchId)
const progress = ref<BatchProgress>()
const progressError = ref('')
const paused = ref(false)
const checking = ref(true)
const page = ref(1)
const resultsVersion = computed(() =>
  progress.value
    ? `${progress.value.successCount}:${progress.value.failedCount}:${progress.value.status}`
    : '',
)
const results = useResource(
  (signal) => api.batchResults(batchId, { page: page.value, size: 10 }, signal),
  [page, resultsVersion],
)
const { busy, error, run } = useAction()
const selected = ref<BatchResult>()
const showDetails = ref(false)
const finished = computed(
  () => progress.value && isTerminal(progress.value.status),
)
const retryAvailable = computed(
  () => finished.value && (progress.value?.failedCount || 0) > 0,
)
const counts = computed(() =>
  progress.value
    ? [
        {
          label: '等待处理',
          value: progress.value.waitingCount,
          tone: 'muted',
        },
        {
          label: '正在处理',
          value: progress.value.processingCount,
          tone: 'primary',
        },
        {
          label: '等待重试',
          value: progress.value.retryingCount,
          tone: 'warning',
        },
        {
          label: '分析成功',
          value: progress.value.successCount,
          tone: 'success',
        },
        {
          label: '分析失败',
          value: progress.value.failedCount,
          tone: 'danger',
        },
      ]
    : [],
)
const poller = createPoller({
  fetch: (signal) => api.batchProgress(batchId, signal),
  terminal: (data) => isTerminal(data.status),
  onData: (data) => {
    progress.value = data
    progressError.value = ''
    checking.value = false
    paused.value = false
  },
  onError: (err, stopped) => {
    progressError.value = errorMessage(err)
    checking.value = false
    paused.value = stopped
  },
})
function refresh() {
  progressError.value = ''
  paused.value = false
  checking.value = true
  poller.start()
  void results.reload()
}
poller.start()
onScopeDispose(poller.stop)
async function retry() {
  try {
    await ElMessageBox.confirm(
      '仅重新入队后端判定可重试的死信任务，已成功结果会保留。确定继续？',
      '重试失败任务',
      {
        confirmButtonText: '重新入队',
        cancelButtonText: '取消',
        type: 'warning',
      },
    )
  } catch {
    return
  }
  await run(async () => {
    const response = await api.retryBatch(batchId)
    ElMessage.success(
      response.requeuedCount
        ? `已将 ${response.requeuedCount} 条任务重新入队，等待分析`
        : '没有可重新入队的死信任务',
    )
    refresh()
  })
}
function details(item: BatchResult) {
  selected.value = item
  showDetails.value = true
}
function download() {
  void run(() => api.exportBatch(batchId))
}
</script>
<template>
  <PageHeading
    title="分析任务详情"
    :description="`批次 #${batchId} · 任务链接可保存，刷新后会自动恢复查询。`"
  >
    <RouterLink to="/analysis"><el-button>新建分析</el-button></RouterLink>
    <el-button :icon="Refresh" :loading="checking" @click="refresh">
      刷新状态
    </el-button>
    <el-button
      type="primary"
      :icon="Download"
      :loading="busy"
      :disabled="!progress"
      @click="download"
    >
      下载 CSV
    </el-button>
  </PageHeading>
  <ErrorNotice :message="progressError" @retry="refresh" />
  <ErrorNotice :message="error" />
  <p v-if="progressError" class="field-hint">
    {{
      paused
        ? '自动查询已暂停。请检查网络或权限后手动恢复。'
        : '查询失败，将延迟后再次查询；连续失败 3 次会暂停。'
    }}
    页面保留的是最近一次成功查询的状态。
  </p>
  <el-skeleton v-if="checking && !progress" :rows="5" animated />
  <template v-if="progress">
    <section class="section-card batch-overview">
      <div class="section-toolbar">
        <div>
          <span class="eyebrow">{{ progress.batchNo }}</span>
          <h2>{{ progress.originalFilename }}</h2>
          <p class="muted">
            课程 #{{ progress.courseId }} · 共 {{ progress.totalCount }} 条评价
            · 创建于 {{ dateLabel(progress.createdAt) }}
          </p>
        </div>
        <el-tag
          :type="
            progress.status === 'COMPLETED'
              ? 'success'
              : progress.status === 'PROCESSING'
                ? 'primary'
                : 'danger'
          "
          size="large"
        >
          {{ statusLabel(progress.status) }}
        </el-tag>
      </div>
      <el-alert
        v-if="progress.status === 'PROCESSING'"
        title="文件已上传，任务已创建；等待队列调度或正在分析。上传成功不代表分析完成。"
        type="info"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="progress.status === 'PARTIAL_FAILED'"
        title="本批次处理已结束，部分评价分析失败。请查看失败原因，可重试符合条件的死信任务。"
        type="warning"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else-if="progress.status === 'FAILED'"
        title="本批次全部分析失败。请查看失败原因，修复服务问题后再尝试重新入队。"
        type="error"
        :closable="false"
        show-icon
      />
      <el-alert
        v-else
        title="本批次全部分析完成，结果已可查看和导出。"
        type="success"
        :closable="false"
        show-icon
      />
      <div class="progress-caption">
        <span>处理进度（成功 + 失败 / 总数）</span>
        <strong>{{ progress.completionPercentage }}%</strong>
      </div>
      <el-progress
        :percentage="progress.completionPercentage"
        :show-text="false"
        :stroke-width="10"
        :status="progress.status === 'COMPLETED' ? 'success' : undefined"
      />
      <div class="batch-counts">
        <div v-for="item in counts" :key="item.label">
          <span>{{ item.label }}</span>
          <strong :class="item.tone">{{ item.value }}</strong>
        </div>
      </div>
      <div class="section-toolbar muted">
        <span>
          {{
            finished
              ? `处理结束 · ${dateLabel(progress.completedAt)} · 自动查询已停止`
              : paused
                ? '查询已暂停'
                : '约每 2 秒更新状态'
          }}
        </span>
        <el-button
          v-if="retryAvailable"
          type="warning"
          plain
          :loading="busy"
          @click="retry"
        >
          重试失败任务
        </el-button>
      </div>
    </section>
    <section class="section-card">
      <div class="section-toolbar">
        <div>
          <h2>逐条分析结果</h2>
          <p class="muted">
            仅显示后端已返回的数据。未完成任务的分析字段暂为空。
          </p>
        </div>
        <el-button :loading="results.loading.value" @click="results.reload">
          刷新结果
        </el-button>
      </div>
      <ErrorNotice :message="results.error.value" @retry="results.reload" />
      <el-table
        v-loading="results.loading.value"
        :data="results.data.value?.items || []"
        stripe
        empty-text="暂无结果，请等待任务处理或刷新结果"
        class="results-table"
      >
        <el-table-column type="expand">
          <template #default="{ row }">
            <div class="expanded-result">
              <p>
                <b>课程维度：</b>
                {{ jsonLabel(row.topics) }}
              </p>
              <p>
                <b>关键词：</b>
                {{ jsonLabel(row.keywords) }}
              </p>
              <p class="preserve-text">
                <b>教学建议：</b>
                {{ jsonLabel(row.advice) }}
              </p>
              <p v-if="row.failureReason" class="danger">
                <b>失败原因：</b>
                {{ row.failureReason }}
              </p>
              <el-button size="small" @click="details(row)">
                查看完整分析详情
              </el-button>
            </div>
          </template>
        </el-table-column>
        <el-table-column prop="taskId" label="任务 ID" width="95" />
        <el-table-column
          prop="content"
          label="评价内容"
          min-width="240"
          show-overflow-tooltip
        />
        <el-table-column prop="rating" label="评分" width="65" />
        <el-table-column label="任务状态" width="110">
          <template #default="{ row }">
            <el-tag
              :type="
                row.taskStatus === 'SUCCESS'
                  ? 'success'
                  : row.taskStatus === 'FAILED'
                    ? 'danger'
                    : 'info'
              "
            >
              {{ statusLabel(row.taskStatus) }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column label="情感" width="100">
          <template #default="{ row }">
            {{ sentimentLabel(row.sentiment) }}
          </template>
        </el-table-column>
        <el-table-column label="置信度" width="95">
          <template #default="{ row }">
            {{
              row.confidence == null
                ? '—'
                : `${(row.confidence * 100).toFixed(1)}%`
            }}
          </template>
        </el-table-column>
        <el-table-column label="风险" width="90">
          <template #default="{ row }">{{ riskLabel(row.riskLevel) }}</template>
        </el-table-column>
        <el-table-column label="操作" width="80" fixed="right">
          <template #default="{ row }">
            <el-button link type="primary" @click="details(row)">
              详情
            </el-button>
          </template>
        </el-table-column>
      </el-table>
      <PaginationBar
        v-if="results.data.value"
        v-model:page="page"
        :size="10"
        :total="results.data.value.total"
      />
    </section>
  </template>
  <el-drawer v-model="showDetails" title="分析详情" size="min(600px, 100%)">
    <template v-if="selected">
      <dl class="result-details">
        <dt>原始评价</dt>
        <dd>{{ selected.content }}</dd>
        <dt>评分 / 任务状态</dt>
        <dd>{{ selected.rating }} / {{ statusLabel(selected.taskStatus) }}</dd>
        <dt>情感 / 置信度 / 风险</dt>
        <dd>
          {{ sentimentLabel(selected.sentiment) }} /
          {{
            selected.confidence == null
              ? '—'
              : `${(selected.confidence * 100).toFixed(1)}%`
          }}
          / {{ riskLabel(selected.riskLevel) }}
        </dd>
        <dt>课程维度</dt>
        <dd>{{ jsonLabel(selected.topics) }}</dd>
        <dt>原文证据</dt>
        <dd>{{ jsonLabel(selected.topicEvidence) }}</dd>
        <dt>关键词</dt>
        <dd>{{ jsonLabel(selected.keywords) }}</dd>
        <dt>教学建议</dt>
        <dd>{{ jsonLabel(selected.advice) }}</dd>
        <dt>来源信息</dt>
        <dd>
          语言：{{ selected.language || '—' }}
          <br />
          情感来源：{{ selected.sentimentSource || '—' }}
          <br />
          运行设备：{{ selected.sentimentDevice || '—' }}
          <br />
          建议来源：{{ selected.adviceSource || '—' }}
        </dd>
        <dt>长文本处理</dt>
        <dd>
          {{
            selected.longTextHandled === null
              ? '—'
              : selected.longTextHandled
                ? '已处理'
                : '未触发'
          }}
          /
          {{
            selected.longTextTruncated === null
              ? '—'
              : selected.longTextTruncated
                ? '已截断'
                : '未截断'
          }}
        </dd>
        <dt>重试与失败</dt>
        <dd>
          重试 {{ selected.retryCount }} 次
          <br />
          {{ selected.failureReason || '暂无失败原因' }}
          <br />
          死信时间：{{ dateLabel(selected.deadLetteredAt) }}
        </dd>
        <dt>结果 ID / 任务完成时间</dt>
        <dd>
          {{ selected.resultId || '—' }} /
          {{ dateLabel(selected.taskCompletedAt) }}
        </dd>
      </dl>
    </template>
  </el-drawer>
</template>

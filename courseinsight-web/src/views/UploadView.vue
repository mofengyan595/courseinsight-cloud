<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { ElMessage } from 'element-plus'
import { UploadFilled, ArrowRight } from '@element-plus/icons-vue'
import * as api from '../api'
import { csvError } from '../utils/file'
import { useAction } from '../composables/useAction'
import { useResource } from '../composables/useResource'
import { dateLabel } from '../utils/format'
import CoursePicker from '../components/CoursePicker.vue'
import PageHeading from '../components/PageHeading.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import PaginationBar from '../components/PaginationBar.vue'
import { useAuthStore } from '../stores/auth'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const initial = Number(route.query.courseId)
const courseId = ref<number | undefined>(
  Number.isSafeInteger(initial) && initial > 0 ? initial : undefined,
)
const file = ref<File>()
const batchId = ref('')
const dragging = ref(false)
const { busy, error, run } = useAction()
const historyPage = ref(1)
const history = useResource(
  (signal) => api.batches({ page: historyPage.value, size: 10 }, signal),
  [historyPage],
)
function select(value?: File) {
  error.value = csvError(value)
  file.value = error.value ? undefined : value
}
function drop(event: DragEvent) {
  dragging.value = false
  if (!busy.value) select(event.dataTransfer?.files[0])
}
async function upload() {
  error.value = !courseId.value ? '请先选择课程' : csvError(file.value)
  if (error.value || !courseId.value || !file.value) return
  const selectedCourse = courseId.value
  const selectedFile = file.value
  await run(async () => {
    const batch = await api.uploadBatch(selectedCourse, selectedFile)
    ElMessage.success(
      `上传成功：已创建 ${batch.totalCount} 条待处理任务，等待异步队列调度`,
    )
    await router.push(`/analysis/${batch.batchId}`)
  })
}
function restore() {
  if (/^[1-9]\d*$/.test(batchId.value.trim()))
    void router.push(`/analysis/${batchId.value.trim()}`)
}
</script>
<template>
  <PageHeading
    title="批量分析"
    :description="
      auth.canManage
        ? '上传课程评价 CSV，异步分析情感、课程维度与教学建议。'
        : '查看已有任务的处理进度与评价分析结果。'
    "
  />
  <div v-if="auth.canManage" class="workflow-steps">
    <div class="current">
      <b>01</b>
      <span>上传评价</span>
    </div>
    <i></i>
    <div>
      <b>02</b>
      <span>队列处理中</span>
    </div>
    <i></i>
    <div>
      <b>03</b>
      <span>查看分析结果</span>
    </div>
  </div>
  <div v-if="auth.canManage" class="upload-layout">
    <section class="section-card">
      <h2>创建分析任务</h2>
      <p class="muted">选择目标课程，提交一份符合格式的 CSV 文件。</p>
      <ErrorNotice :message="error" />
      <label class="field-label">1. 选择课程</label>
      <CoursePicker v-model="courseId" />
      <label class="field-label">2. 上传评价文件</label>
      <div
        class="upload-drop"
        :class="{ dragging, selected: file }"
        @dragover.prevent="dragging = true"
        @dragleave.prevent="dragging = false"
        @drop.prevent="drop"
      >
        <el-icon :size="40"><UploadFilled /></el-icon>
        <strong>
          {{ file ? file.name : '点击选择文件，或将文件拖到这里' }}
        </strong>
        <span>
          {{
            file
              ? `${(file.size / 1024).toFixed(1)} KB · 已选择，尚未上传`
              : 'CSV 格式 · 最大 2MB · 最多 200 条评价'
          }}
        </span>
        <input
          type="file"
          accept=".csv,text/csv"
          aria-label="上传 CSV 文件"
          :disabled="busy"
          @change="select(($event.target as HTMLInputElement).files?.[0])"
        />
      </div>
      <div class="form-actions">
        <el-button v-if="file" :disabled="busy" @click="file = undefined">
          移除文件
        </el-button>
        <el-button type="primary" :loading="busy" @click="upload">
          {{ busy ? '正在上传…' : '上传并进入处理队列' }}
          <el-icon class="button-icon"><ArrowRight /></el-icon>
        </el-button>
      </div>
      <p class="field-hint">
        上传成功后会跳转到任务详情。处理进度以服务端实际状态为准。
      </p>
    </section>
    <aside class="section-card csv-guide">
      <span class="eyebrow">开始之前</span>
      <h2>准备你的 CSV 文件</h2>
      <p>请使用 UTF-8 编码，包含以下两列表头：</p>
      <pre>
content,rating
课程讲解清晰，案例实用,5
作业量偏大，希望优化,2</pre
      >
      <ul>
        <li>
          <b>content</b>
          ：非空评价，最多 2000 字符。
        </li>
        <li>
          <b>rating</b>
          ：1 到 5 的整数。
        </li>
        <li>含逗号或换行的内容请用双引号包围。</li>
        <li>文件完整格式由后端校验，错误会显示具体原因。</li>
      </ul>
      <a href="/analysis-batch-sample.csv" download>
        <el-button plain class="full-width">下载 CSV 样例</el-button>
      </a>
      <div class="guide-divider"></div>
      <h3>恢复已有任务</h3>
      <p class="field-hint">
        可从下方历史任务直接返回，也可输入批次 ID 或打开保存的任务链接。
      </p>
      <form @submit.prevent="restore">
        <el-input
          v-model="batchId"
          placeholder="批次 ID"
          aria-label="批次 ID"
        />
        <el-button
          native-type="submit"
          class="full-width"
          :disabled="!/^[1-9]\d*$/.test(batchId.trim())"
        >
          查看任务
        </el-button>
      </form>
    </aside>
  </div>
  <section class="section-card batch-history" aria-label="历史分析任务">
    <div class="section-toolbar">
      <div>
        <h2>历史分析任务</h2>
        <p class="muted">
          已上传的任务会保留，离开页面后可从这里继续查看，无需重新上传 CSV。
        </p>
      </div>
      <el-button :loading="history.loading.value" @click="history.reload">
        刷新任务
      </el-button>
    </div>
    <ErrorNotice :message="history.error.value" @retry="history.reload" />
    <el-table
      v-loading="history.loading.value"
      :data="history.data.value?.items || []"
      :empty-text="
        auth.canManage
          ? '暂无历史任务，上传 CSV 后会显示在这里'
          : '本人课程暂无批量分析任务'
      "
      stripe
    >
      <el-table-column prop="batchId" label="批次 ID" width="95" />
      <el-table-column
        prop="originalFilename"
        label="文件"
        min-width="200"
        show-overflow-tooltip
      />
      <el-table-column
        prop="courseName"
        label="课程"
        min-width="180"
        show-overflow-tooltip
      />
      <el-table-column prop="totalCount" label="评价数" width="85" />
      <el-table-column label="创建时间" width="180">
        <template #default="{ row }">{{ dateLabel(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="110" fixed="right">
        <template #default="{ row }">
          <RouterLink :to="`/analysis/${row.batchId}`">
            <el-button link type="primary">查看任务</el-button>
          </RouterLink>
        </template>
      </el-table-column>
    </el-table>
    <PaginationBar
      v-if="history.data.value"
      v-model:page="historyPage"
      :size="10"
      :total="history.data.value.total"
    />
  </section>
</template>
<style scoped>
.batch-history {
  margin-top: 24px;
}
</style>

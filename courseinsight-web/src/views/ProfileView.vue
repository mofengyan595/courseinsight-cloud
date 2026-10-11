<script setup lang="ts">
import { ref } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { useAuthStore } from '../stores/auth'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import { useAction } from '../composables/useAction'
import { roleLabel, dateLabel } from '../utils/format'
import PageHeading from '../components/PageHeading.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import PaginationBar from '../components/PaginationBar.vue'
const auth = useAuthStore()
const page = ref(1)
const { data, loading, error, reload } = useResource(
  (signal) => api.myComments({ page: page.value, size: 10 }, signal),
  [page],
)
const action = useAction()
async function remove(id: number) {
  try {
    await ElMessageBox.confirm(
      '删除后该评价将不再作为有效评价展示，且不能从网页恢复。确定删除？',
      '删除我的评价',
      {
        type: 'warning',
        confirmButtonText: '确认删除',
        cancelButtonText: '取消',
      },
    )
  } catch {
    return
  }
  await action.run(async () => {
    await api.deleteComment(id)
    ElMessage.success('评价已删除')
    if (data.value?.items.length === 1 && page.value > 1) page.value--
    else await reload()
  })
}
</script>
<template>
  <PageHeading
    title="个人信息"
    description="查看当前账号与管理自己提交的课程评价。"
  />
  <section class="section-card profile-card">
    <span class="profile-avatar">
      {{ auth.session?.displayName?.slice(0, 1) }}
    </span>
    <div>
      <h2>{{ auth.session?.displayName }}</h2>
      <p class="muted">
        {{ auth.session?.username }} · {{ roleLabel(auth.session?.role) }}
      </p>
      <p class="field-hint">
        用户 ID：{{ auth.session?.userId }} ·
        本页身份信息来自登录响应，权限以服务端当前记录为准。
      </p>
    </div>
  </section>
  <section class="section-card">
    <div class="section-toolbar">
      <h2>我的评价</h2>
      <el-button @click="reload">刷新评价</el-button>
    </div>
    <ErrorNotice :message="error" @retry="reload" />
    <ErrorNotice :message="action.error.value" />
    <el-table
      v-loading="loading"
      :data="data?.items || []"
      empty-text="你还没有提交评价"
    >
      <el-table-column prop="id" label="评价 ID" width="95" />
      <el-table-column label="课程" width="110">
        <template #default="{ row }">
          <RouterLink :to="`/courses/${row.courseId}`" class="primary">
            课程 #{{ row.courseId }}
          </RouterLink>
        </template>
      </el-table-column>
      <el-table-column
        prop="content"
        label="评价内容"
        min-width="240"
        show-overflow-tooltip
      />
      <el-table-column prop="rating" label="评分" width="70" />
      <el-table-column label="提交时间" width="180">
        <template #default="{ row }">{{ dateLabel(row.createdAt) }}</template>
      </el-table-column>
      <el-table-column label="操作" width="85" fixed="right">
        <template #default="{ row }">
          <el-button
            link
            type="danger"
            :disabled="action.busy.value"
            @click="remove(row.id)"
          >
            删除
          </el-button>
        </template>
      </el-table-column>
    </el-table>
    <PaginationBar
      v-if="data"
      v-model:page="page"
      :size="10"
      :total="data.total"
    />
  </section>
</template>

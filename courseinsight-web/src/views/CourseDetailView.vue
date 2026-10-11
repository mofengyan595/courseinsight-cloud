<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute } from 'vue-router'
import { ElMessage } from 'element-plus'
import type { FormInstance } from 'element-plus'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import { useAction } from '../composables/useAction'
import { useAuthStore } from '../stores/auth'
import { dateLabel } from '../utils/format'
import CourseForm from '../components/CourseForm.vue'
import PageHeading from '../components/PageHeading.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
import PaginationBar from '../components/PaginationBar.vue'
const id = String(useRoute().params.id)
const auth = useAuthStore()
const open = ref(false)
const page = ref(1)
const details = useResource((signal) => api.course(id, signal))
const list = useResource(
  (signal) => api.comments(id, { page: page.value, size: 10 }, signal),
  [page],
)
const { busy, error, run } = useAction()
const formRef = ref<FormInstance>()
const form = reactive({ content: '', rating: 0 })
async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  await run(async () => {
    await api.createComment(id, {
      content: form.content.trim(),
      rating: form.rating,
    })
    form.content = ''
    form.rating = 0
    ElMessage.success('评价已提交，分析任务已创建；分析将在后台异步进行')
    page.value = 1
    await list.reload()
  })
}
</script>
<template>
  <PageHeading
    :title="details.data.value?.name || '课程详情'"
    :description="
      details.data.value
        ? `${details.data.value.code} · ${details.data.value.teacherName}`
        : '查看课程信息与评价'
    "
  >
    <RouterLink to="/courses"><el-button>返回课程列表</el-button></RouterLink>
    <el-button v-if="auth.canManage && details.data.value" @click="open = true">
      编辑课程
    </el-button>
  </PageHeading>
  <ErrorNotice :message="details.error.value" @retry="details.reload" />
  <el-skeleton v-if="details.loading.value" :rows="3" animated />
  <template v-if="details.data.value">
    <section class="section-card">
      <div class="section-toolbar">
        <h2>课程信息</h2>
        <el-tag :type="details.data.value.status === 1 ? 'success' : 'info'">
          {{ details.data.value.status === 1 ? '启用' : '停用' }}
        </el-tag>
      </div>
      <p class="preserve-text">
        {{ details.data.value.description || '暂无课程简介' }}
      </p>
      <p class="field-hint">
        创建于 {{ dateLabel(details.data.value.createdAt) }}
      </p>
      <div v-if="auth.canManage" class="inline-actions">
        <RouterLink :to="{ path: '/analysis', query: { courseId: id } }">
          <el-button type="primary" plain>批量分析评价</el-button>
        </RouterLink>
        <RouterLink :to="{ path: '/analytics', query: { courseId: id } }">
          <el-button>查看数据看板</el-button>
        </RouterLink>
      </div>
    </section>
    <section class="section-card">
      <h2>提交课程评价</h2>
      <p class="muted">
        评价匿名展示。每个账号对同一课程仅可提交一条有效评价，可在个人信息中管理。
      </p>
      <ErrorNotice :message="error" />
      <el-form
        ref="formRef"
        :model="form"
        label-position="top"
        @submit.prevent="submit"
      >
        <el-form-item
          label="课程评分"
          prop="rating"
          :rules="[
            {
              type: 'number',
              min: 1,
              max: 5,
              required: true,
              message: '请选择 1 到 5 星评分',
              trigger: 'change',
            },
          ]"
        >
          <el-rate v-model="form.rating" show-text />
        </el-form-item>
        <el-form-item
          label="评价内容"
          prop="content"
          :rules="[
            {
              required: true,
              whitespace: true,
              message: '请输入评价内容',
              trigger: 'blur',
            },
          ]"
        >
          <el-input
            v-model="form.content"
            type="textarea"
            :rows="4"
            maxlength="2000"
            show-word-limit
            placeholder="分享你的学习体验，例如课程内容、讲解方式或作业安排…"
          />
        </el-form-item>
        <el-button type="primary" :loading="busy" native-type="submit">
          提交评价
        </el-button>
      </el-form>
    </section>
    <section class="section-card">
      <div class="section-toolbar">
        <h2>
          课程评价
          <span v-if="list.data.value" class="count-badge">
            {{ list.data.value.total }}
          </span>
        </h2>
        <el-button @click="list.reload">刷新评价</el-button>
      </div>
      <ErrorNotice :message="list.error.value" @retry="list.reload" />
      <el-skeleton v-if="list.loading.value" :rows="4" animated />
      <template v-else-if="list.data.value">
        <el-empty
          v-if="!list.data.value.items.length"
          description="还没有评价，分享第一条学习反馈吧"
        />
        <article
          v-for="item in list.data.value.items"
          :key="item.id"
          class="comment-item"
        >
          <div class="comment-meta">
            <strong>{{ item.anonymous ? '匿名评价' : '课程评价' }}</strong>
            <el-rate :model-value="item.rating" disabled />
            <time>{{ dateLabel(item.createdAt) }}</time>
          </div>
          <p class="preserve-text">{{ item.content }}</p>
        </article>
        <PaginationBar
          v-model:page="page"
          :size="10"
          :total="list.data.value.total"
        />
      </template>
    </section>
    <CourseForm
      v-model="open"
      :course="details.data.value"
      @saved="details.reload"
    />
  </template>
</template>

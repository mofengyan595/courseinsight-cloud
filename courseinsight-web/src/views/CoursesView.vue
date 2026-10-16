<script setup lang="ts">
import { ref } from 'vue'
import { useRouter } from 'vue-router'
import { Plus, Search, Reading, ArrowRight } from '@element-plus/icons-vue'
import * as api from '../api'
import { useResource } from '../composables/useResource'
import { useAuthStore } from '../stores/auth'
import PageHeading from '../components/PageHeading.vue'
import CourseForm from '../components/CourseForm.vue'
import PaginationBar from '../components/PaginationBar.vue'
import ErrorNotice from '../components/ErrorNotice.vue'
const auth = useAuthStore()
const router = useRouter()
const keyword = ref('')
const search = ref('')
const page = ref(1)
const open = ref(false)
const { data, loading, error, reload } = useResource(
  (signal) =>
    api.courses({ page: page.value, size: 9, keyword: search.value }, signal),
  [page, search],
)
function find() {
  page.value = 1
  search.value = keyword.value.trim()
  void reload()
}
</script>
<template>
  <PageHeading
    title="课程列表"
    description="浏览课程、收集评价，开启你的课程洞察。"
  >
    <el-button
      v-if="auth.canManage"
      type="primary"
      :icon="Plus"
      @click="open = true"
    >
      创建课程
    </el-button>
  </PageHeading>
  <div class="welcome-banner">
    <div>
      <span class="eyebrow">从反馈出发</span>
      <h2>更好地理解每一门课程</h2>
      <p>查看课程详情与学生评价，发现值得保留的优势和需要改进的方向。</p>
    </div>
    <el-icon class="banner-icon"><Reading /></el-icon>
  </div>
  <section class="section-card">
    <div class="section-toolbar">
      <h2>
        {{ auth.session?.role === 'TEACHER' ? '我的课程' : '全部课程' }}
        <span v-if="data" class="count-badge">{{ data.total }}</span>
      </h2>
      <form class="search-row" @submit.prevent="find">
        <el-input
          v-model="keyword"
          :prefix-icon="Search"
          placeholder="课程名称、代码或教师"
          maxlength="100"
          clearable
          aria-label="课程搜索"
        />
        <el-button native-type="submit">搜索</el-button>
      </form>
    </div>
    <ErrorNotice :message="error" @retry="reload" />
    <el-skeleton v-if="loading" :rows="6" animated />
    <template v-else-if="data">
      <el-empty
        v-if="!data.items.length"
        :description="
          search
            ? '没有找到相关课程'
            : auth.session?.role === 'TEACHER'
              ? '暂无分配给你的课程，请联系教务管理员'
              : '还没有课程，从创建第一门课程开始'
        "
      />
      <div v-else class="course-grid">
        <article v-for="item in data.items" :key="item.id" class="course-card">
          <div class="course-card-top">
            <span class="course-symbol">
              <el-icon><Reading /></el-icon>
            </span>
            <el-tag
              :type="item.status === 1 ? 'success' : 'info'"
              effect="plain"
              size="small"
            >
              {{ item.status === 1 ? '启用' : '停用' }}
            </el-tag>
          </div>
          <span class="course-code">{{ item.code }}</span>
          <h3>
            <RouterLink :to="`/courses/${item.id}`">{{ item.name }}</RouterLink>
          </h3>
          <p class="course-teacher">授课教师 · {{ item.teacherName }}</p>
          <p class="course-description">
            {{ item.description || '暂未填写课程简介' }}
          </p>
          <RouterLink :to="`/courses/${item.id}`" class="course-link">
            查看课程
            <el-icon><ArrowRight /></el-icon>
          </RouterLink>
        </article>
      </div>
      <PaginationBar v-model:page="page" :size="9" :total="data.total" />
    </template>
  </section>
  <CourseForm
    v-if="auth.canManage"
    v-model="open"
    @saved="router.push(`/courses/${$event}`)"
  />
</template>

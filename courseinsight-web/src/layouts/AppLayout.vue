<script setup lang="ts">
import { ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import {
  Reading,
  DataAnalysis,
  Upload,
  User,
  SwitchButton,
  Menu,
  School,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { roleLabel } from '../utils/format'
const route = useRoute()
const router = useRouter()
const auth = useAuthStore()
const expanded = ref(false)
function logout() {
  auth.signOut()
  void router.replace('/login')
}
</script>
<template>
  <div class="workspace">
    <button
      v-if="expanded"
      class="sidebar-scrim"
      aria-label="关闭导航"
      @click="expanded = false"
    ></button>
    <aside class="sidebar" :class="{ expanded }">
      <RouterLink to="/courses" class="brand">
        <span class="brand-mark">
          <el-icon :size="26"><School /></el-icon>
        </span>
        <span>
          CourseInsight
          <small>课程洞察 · Cloud</small>
        </span>
      </RouterLink>
      <div class="nav-label">工作空间</div>
      <nav @click="expanded = false">
        <RouterLink
          to="/courses"
          :class="{ active: route.path.startsWith('/courses') }"
        >
          <el-icon><Reading /></el-icon>
          课程列表
        </RouterLink>
        <RouterLink
          v-if="auth.canAnalyze"
          to="/analysis"
          :class="{
            active:
              route.path.startsWith('/analysis') &&
              !route.path.startsWith('/analytics'),
          }"
        >
          <el-icon><Upload /></el-icon>
          批量分析
        </RouterLink>
        <RouterLink v-if="auth.canAnalyze" to="/analytics">
          <el-icon><DataAnalysis /></el-icon>
          数据看板
        </RouterLink>
        <RouterLink to="/profile">
          <el-icon><User /></el-icon>
          个人信息
        </RouterLink>
      </nav>
      <div class="sidebar-note">
        <span class="note-dot"></span>
        让每一条反馈都有价值
        <p>从课程评价到教学洞察</p>
      </div>
      <div class="sidebar-user">
        <span class="avatar">
          {{ auth.session?.displayName?.slice(0, 1) || 'U' }}
        </span>
        <div>
          {{ auth.session?.displayName }}
          <small>{{ roleLabel(auth.session?.role) }}</small>
        </div>
        <el-button
          text
          class="logout-button"
          aria-label="退出登录"
          @click="logout"
        >
          <el-icon><SwitchButton /></el-icon>
        </el-button>
      </div>
    </aside>
    <div class="workspace-main">
      <header class="topbar">
        <div class="breadcrumb">
          <el-button
            class="menu-toggle"
            text
            aria-label="打开导航"
            @click="expanded = !expanded"
          >
            <el-icon><Menu /></el-icon>
          </el-button>
          <span>工作空间</span>
          <span>/</span>
          <strong>{{ route.meta.title }}</strong>
        </div>
        <span class="topbar-badge">
          {{ roleLabel(auth.session?.role) }}工作台
        </span>
      </header>
      <main class="page-content"><RouterView :key="route.path" /></main>
      <footer class="page-footer">
        CourseInsight Cloud
        <span>课程评价与教学分析平台</span>
      </footer>
    </div>
  </div>
</template>

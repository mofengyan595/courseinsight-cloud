<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance } from 'element-plus'
import {
  School,
  ArrowRight,
  ChatDotRound,
  DataAnalysis,
  Connection,
} from '@element-plus/icons-vue'
import { useAuthStore } from '../stores/auth'
import { useAction } from '../composables/useAction'
import { safeRedirect } from '../router'
import ErrorNotice from '../components/ErrorNotice.vue'
const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const form = reactive({ username: '', password: '' })
const formRef = ref<FormInstance>()
const { busy, error, run } = useAction()
async function submit() {
  if (!(await formRef.value?.validate().catch(() => false))) return
  await run(async () => {
    await auth.signIn({
      username: form.username.trim(),
      password: form.password,
    })
    form.password = ''
    await router.replace(safeRedirect(route.query.redirect))
  })
}
</script>
<template>
  <div class="login-page">
    <section class="login-story">
      <div class="brand">
        <span class="brand-mark">
          <el-icon :size="28"><School /></el-icon>
        </span>
        <span>
          CourseInsight
          <small>课程洞察 · Cloud</small>
        </span>
      </div>
      <div class="story-content">
        <div class="story-label">每一条评价，都是改进的起点</div>
        <h1>
          听见真实反馈
          <br />
          看见教学的
          <span>更多可能。</span>
        </h1>
        <p>
          连接课程、评价与分析，让分散的声音
          <br class="desktop-only" />
          成为清晰、可行动的教学洞察。
        </p>
        <div class="story-features">
          <div>
            <el-icon><ChatDotRound /></el-icon>
            <span>
              汇聚课程评价
              <small>让反馈集中有序</small>
            </span>
          </div>
          <div>
            <el-icon><Connection /></el-icon>
            <span>
              异步批量分析
              <small>进度与结果随时可见</small>
            </span>
          </div>
          <div>
            <el-icon><DataAnalysis /></el-icon>
            <span>
              发现教学洞察
              <small>用数据支持改进</small>
            </span>
          </div>
        </div>
      </div>
      <span class="story-footer">COURSE FEEDBACK. CLEARER INSIGHTS.</span>
    </section>
    <section class="login-panel">
      <div class="login-form-wrap">
        <div class="eyebrow">WELCOME BACK</div>
        <h2>欢迎回到课程洞察</h2>
        <p class="muted">登录你的账号，开始今天的工作。</p>
        <ErrorNotice :message="error" />
        <el-form
          ref="formRef"
          :model="form"
          label-position="top"
          size="large"
          @submit.prevent="submit"
        >
          <el-form-item
            label="用户名"
            prop="username"
            :rules="[
              {
                required: true,
                whitespace: true,
                message: '请输入用户名',
                trigger: 'blur',
              },
            ]"
          >
            <el-input
              v-model="form.username"
            aria-label="用户名"
              name="username"
              autocomplete="username"
              placeholder="请输入用户名"
            />
          </el-form-item>
          <el-form-item
            label="密码"
            prop="password"
            :rules="[
              { required: true, message: '请输入密码', trigger: 'blur' },
            ]"
          >
            <el-input
              v-model="form.password"
            aria-label="密码"
              name="password"
              type="password"
              autocomplete="current-password"
              show-password
              placeholder="请输入密码"
            />
          </el-form-item>
          <el-button
            type="primary"
            class="full-width login-submit"
            :loading="busy"
            native-type="submit"
          >
            登录工作台
            <el-icon class="button-icon"><ArrowRight /></el-icon>
          </el-button>
        </el-form>
        <p class="login-hint">
          请使用管理员分配的账号。演示环境账号见仓库 README。
        </p>
        <div class="login-security">登录状态仅保存在当前浏览器标签页。</div>
      </div>
    </section>
  </div>
</template>

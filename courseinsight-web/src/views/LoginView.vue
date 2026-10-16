<script setup lang="ts">
import { reactive, ref } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import type { FormInstance } from 'element-plus'
import {
  School,
  ArrowRight,
  ChatDotRound,
  Lock,
  User,
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
  <div class="auth-page">
    <main class="auth-shell">
      <section class="auth-story" aria-label="课程洞察介绍">
        <a class="auth-brand" href="/login" aria-label="CourseInsight 课程洞察">
          <span class="auth-brand-mark">
            <el-icon :size="23"><School /></el-icon>
          </span>
          <span>
            CourseInsight
            <small>课程洞察 · Cloud</small>
          </span>
        </a>
        <div class="auth-story-copy">
          <span class="auth-kicker">
            <i />
            让每一条反馈都有价值
          </span>
          <h1>
            听见真实反馈，
            <br />
            看见教学的
            <span>更多可能。</span>
          </h1>
          <p>
            从学生的声音中发现线索，
            <br />
            让教学改进，有迹可循。
          </p>
        </div>
        <div class="feedback-visual" aria-hidden="true">
          <div class="visual-orbit" />
          <div class="feedback-note">
            <span class="note-icon">
              <el-icon><ChatDotRound /></el-icon>
            </span>
            <span>
              从一条真实评价开始
              <small>倾听 · 理解 · 改进</small>
            </span>
            <i class="note-dot" />
          </div>
          <div class="insight-note">
            <div class="note-heading">
              <span>让反馈成为洞察</span>
              <span class="mini-badge">CourseInsight</span>
            </div>
            <div class="insight-flow">
              <span>课程评价</span>
              <i />
              <span>智能分析</span>
              <i />
              <strong>教学改进</strong>
            </div>
            <div class="visual-lines">
              <i />
              <i />
              <i />
            </div>
          </div>
        </div>
        <footer class="auth-story-footer">
          <span>汇聚声音，连接改变。</span>
          <span>COURSEINSIGHT CLOUD</span>
        </footer>
      </section>
      <section class="auth-panel" aria-labelledby="login-title">
        <div class="auth-form-wrap">
          <div class="auth-welcome-icon">
            <el-icon :size="26"><School /></el-icon>
          </div>
          <span class="auth-eyebrow">你的课程洞察工作台</span>
          <h2 id="login-title">欢迎回来</h2>
          <p class="auth-description">登录账号，继续探索课程中的每一份反馈。</p>
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
                :prefix-icon="User"
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
                :prefix-icon="Lock"
              />
            </el-form-item>
            <el-button
              type="primary"
              class="auth-submit"
              :loading="busy"
              native-type="submit"
            >
              登录工作台
              <el-icon><ArrowRight /></el-icon>
            </el-button>
          </el-form>
          <p class="auth-account-hint">
            请使用管理员分配的账号。
            <br />
            <span>演示环境账号见仓库 README。</span>
          </p>
          <div class="auth-privacy">
            <el-icon><Lock /></el-icon>
            <span>登录状态仅保存在当前浏览器标签页</span>
          </div>
        </div>
      </section>
    </main>
    <p class="auth-page-footer">CourseInsight · 让课程反馈成为教学改进的起点</p>
  </div>
</template>
<style scoped>
.auth-page {
  min-height: 100vh;
  display: flex;
  flex-direction: column;
  justify-content: center;
  padding: 36px;
  background:
    radial-gradient(ellipse at 4% 4%, #eee8fa 0, transparent 45%),
    radial-gradient(ellipse at 95% 95%, #f0edf8 0, transparent 40%), #f8f7fb;
}
.auth-shell {
  width: 100%;
  max-width: 1160px;
  min-height: 710px;
  margin: 0 auto;
  display: grid;
  grid-template-columns: 1fr 1fr;
  background: #fff;
  border: 1px solid #ece8f4;
  border-radius: 24px;
  box-shadow:
    0 24px 80px #4938650a,
    0 3px 16px #44385c04;
  overflow: hidden;
}
.auth-story {
  position: relative;
  display: flex;
  flex-direction: column;
  padding: 40px 44px 28px;
  background: linear-gradient(145deg, #f0eaf9 0%, #eee9f8 45%, #e8e1f5 100%);
  min-width: 0;
  overflow: hidden;
}
.auth-brand {
  position: relative;
  z-index: 1;
  display: flex;
  align-items: center;
  gap: 11px;
  width: fit-content;
  color: #3d2c5b;
  font-size: 19px;
  font-weight: 700;
  letter-spacing: -0.5px;
}
.auth-brand small {
  display: block;
  font-size: 10px;
  font-weight: 400;
  letter-spacing: 2px;
  color: #9583ad;
  margin-top: 4px;
}
.auth-brand-mark {
  width: 42px;
  height: 42px;
  display: grid;
  place-items: center;
  background: #6a4cc2;
  color: white;
  border-radius: 12px;
  box-shadow: 0 5px 14px #7753bd26;
}
.auth-story-copy {
  position: relative;
  z-index: 1;
  margin-top: 57px;
}
.auth-kicker {
  display: flex;
  align-items: center;
  gap: 8px;
  font-size: 11px;
  letter-spacing: 1px;
  color: #9077af;
}
.auth-kicker i {
  width: 5px;
  height: 5px;
  background: #a58ac8;
  border-radius: 50%;
}
.auth-story h1 {
  font-size: clamp(30px, 2.7vw, 38px);
  line-height: 1.65;
  letter-spacing: -1px;
  color: #3d2e55;
  margin: 19px 0 15px;
  font-weight: 600;
}
.auth-story h1 span {
  display: block;
  color: #7954be;
}
.auth-story-copy > p {
  font-size: 13px;
  line-height: 1.9;
  color: #9786a9;
  margin: 0;
}
.feedback-visual {
  position: relative;
  margin: 26px 0 22px;
  height: 189px;
}
.visual-orbit {
  position: absolute;
  border: 1px solid #cfc0e04d;
  width: 275px;
  height: 275px;
  border-radius: 50%;
  top: -22px;
  left: 96px;
  box-shadow:
    0 0 0 30px #d5c6e51a,
    0 0 0 62px #d5c6e512;
}
.feedback-note {
  position: absolute;
  display: flex;
  align-items: center;
  gap: 11px;
  top: 0;
  left: 0;
  max-width: 90%;
  background: #ffffffba;
  border: 1px solid #ffffffa6;
  padding: 14px 17px;
  border-radius: 12px;
  box-shadow: 0 10px 22px #72539109;
  font-size: 12px;
  color: #716182;
  transform: rotate(-3deg);
}
.feedback-note small {
  display: block;
  margin-top: 5px;
  font-size: 10px;
  color: #aca0b8;
}
.note-icon {
  color: #9b7dc5;
  background: #f1ebfa;
  padding: 8px;
  width: 33px;
  height: 33px;
  border-radius: 9px;
  font-size: 16px;
}
.feedback-note .note-dot {
  margin-left: 12px;
  margin-right: 0;
  width: 5px;
  height: 5px;
  border-radius: 50%;
  background: #b5a2ce;
}
.insight-note {
  position: absolute;
  top: 80px;
  right: 0;
  width: 87%;
  max-width: 370px;
  padding: 18px;
  border: 1px solid #ffffffa6;
  background: #ffffffe8;
  border-radius: 13px;
  box-shadow: 0 12px 28px #69458b0a;
  transform: rotate(2deg);
}
.note-heading {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 8px;
  font-size: 11px;
  color: #74657e;
}
.mini-badge {
  font-size: 8px;
  color: #b3a3c6;
}
.insight-flow {
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 5px;
  font-size: 10px;
  color: #9b8aaa;
  margin-top: 17px;
}
.insight-flow span,
.insight-flow strong {
  padding: 6px 8px;
  border-radius: 5px;
  background: #f8f5fb;
  white-space: nowrap;
  font-weight: 400;
}
.insight-flow strong {
  background: #eee7f7;
  color: #9372ba;
}
.insight-flow > i {
  height: 1px;
  flex: 1;
  background: #e5dcef;
}
.visual-lines {
  display: flex;
  gap: 5px;
  margin-top: 14px;
}
.visual-lines i {
  width: 25%;
  height: 3px;
  border-radius: 2px;
  background: #f0eaf7;
}
.visual-lines i:nth-child(2) {
  width: 36%;
}
.visual-lines i:nth-child(3) {
  width: 15%;
}
.auth-story-footer {
  position: relative;
  margin-top: auto;
  display: flex;
  align-items: center;
  justify-content: space-between;
  gap: 10px;
  font-size: 10px;
  color: #a899ba;
}
.auth-story-footer span:last-child {
  font-size: 8px;
  letter-spacing: 1.2px;
}
.auth-panel {
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 54px;
  min-width: 0;
}
.auth-form-wrap {
  width: 100%;
  max-width: 340px;
}
.auth-welcome-icon {
  display: grid;
  place-items: center;
  width: 52px;
  height: 52px;
  border: 1px solid #ede6f7;
  border-radius: 15px;
  color: #8867bb;
  background: #f8f5fc;
  margin-bottom: 27px;
}
.auth-eyebrow {
  display: block;
  color: #a398b0;
  font-size: 11px;
  letter-spacing: 1px;
}
.auth-form-wrap h2 {
  color: #33253f;
  font-size: 29px;
  letter-spacing: -0.7px;
  font-weight: 600;
  margin: 10px 0 12px;
}
.auth-description {
  font-size: 12px;
  color: #9e96a8;
  margin: 0 0 30px;
  line-height: 1.8;
}
.auth-form-wrap :deep(.el-form-item) {
  margin-bottom: 24px;
}
.auth-form-wrap :deep(.el-form-item__label) {
  color: #63556f;
  font-size: 12px;
  margin-bottom: 9px;
}
.auth-form-wrap :deep(.el-form-item.is-required .el-form-item__label::before) {
  display: none;
}
.auth-form-wrap :deep(.el-input__wrapper) {
  height: 48px;
  padding-inline: 14px;
  background: #fdfcfe;
  box-shadow: 0 0 0 1px #e8e2ef inset;
  border-radius: 9px;
}
.auth-form-wrap :deep(.el-input__wrapper.is-focus) {
  box-shadow:
    0 0 0 1px #9b7ac6 inset,
    0 0 0 3px #9b7ac612;
}
.auth-form-wrap :deep(.el-input__inner) {
  font-size: 13px;
}
.auth-form-wrap :deep(.el-input__prefix) {
  color: #b2a1c2;
  margin-right: 6px;
}
.auth-submit {
  display: flex;
  width: 100%;
  height: 48px;
  margin-top: 8px;
  background: #7654b5;
  border-color: #7654b5;
  border-radius: 9px;
  font-size: 13px;
  box-shadow: 0 5px 14px #7850b31c;
}
.auth-submit:hover {
  background: #6745a6;
  border-color: #6745a6;
}
.auth-submit .el-icon {
  margin-left: 13px;
}
.auth-account-hint {
  text-align: center;
  font-size: 11px;
  line-height: 1.9;
  color: #a199ad;
  margin: 21px 0 0;
}
.auth-account-hint span {
  color: #b4acbd;
}
.auth-privacy {
  border-top: 1px solid #f0ecf5;
  margin-top: 30px;
  padding-top: 18px;
  display: flex;
  align-items: center;
  justify-content: center;
  gap: 6px;
  font-size: 10px;
  color: #b1a6bb;
}
.auth-page-footer {
  font-size: 10px;
  text-align: center;
  color: #b7afc1;
  margin: 23px 0 0;
  letter-spacing: 0.5px;
}
@media (max-width: 1050px) {
  .auth-page {
    padding: 24px;
  }
  .auth-shell {
    min-height: 690px;
  }
  .auth-story {
    padding: 34px 30px 26px;
  }
  .auth-panel {
    padding: 42px 34px;
  }
  .auth-story h1 {
    font-size: 31px;
  }
  .auth-story-footer span:last-child {
    display: none;
  }
  .feedback-note {
    padding-inline: 12px;
  }
  .insight-note {
    padding: 14px;
    width: 94%;
  }
}
@media (max-width: 760px) {
  .auth-page {
    padding: 24px 18px;
  }
  .auth-shell {
    grid-template-columns: 1fr;
    max-width: 460px;
    min-height: auto;
    border-radius: 19px;
  }
  .auth-story {
    padding: 25px 28px;
  }
  .auth-brand {
    font-size: 18px;
  }
  .auth-brand-mark {
    width: 38px;
    height: 38px;
    border-radius: 11px;
  }
  .auth-story-copy {
    margin-top: 22px;
  }
  .auth-kicker,
  .auth-story-copy > p,
  .feedback-visual,
  .auth-story-footer {
    display: none;
  }
  .auth-story h1 {
    font-size: 25px;
    line-height: 1.5;
    margin: 0;
  }
  .auth-story h1 br {
    display: none;
  }
  .auth-story h1 span {
    display: inline;
  }
  .auth-panel {
    padding: 30px 28px;
  }
  .auth-welcome-icon {
    display: none;
  }
  .auth-form-wrap {
    max-width: none;
  }
  .auth-form-wrap h2 {
    font-size: 25px;
  }
  .auth-description {
    margin-bottom: 26px;
  }
  .auth-account-hint {
    margin-top: 18px;
  }
  .auth-privacy {
    margin-top: 24px;
  }
  .auth-page-footer {
    margin-top: 18px;
    font-size: 9px;
  }
}
</style>

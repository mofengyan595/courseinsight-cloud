import { createRouter, createWebHistory } from 'vue-router'
import { useAuthStore } from '../stores/auth'
import AppLayout from '../layouts/AppLayout.vue'

export const router = createRouter({
  history: createWebHistory(),
  routes: [
    {
      path: '/login',
      name: 'login',
      component: () => import('../views/LoginView.vue'),
      meta: { public: true, title: '登录' },
    },
    {
      path: '/',
      component: AppLayout,
      children: [
        { path: '', redirect: '/courses' },
        {
          path: 'courses',
          component: () => import('../views/CoursesView.vue'),
          meta: { title: '课程列表' },
        },
        {
          path: 'courses/:id(\\d+)',
          component: () => import('../views/CourseDetailView.vue'),
          meta: { title: '课程详情' },
        },
        {
          path: 'analysis',
          component: () => import('../views/UploadView.vue'),
          meta: { title: '批量分析', manager: true },
        },
        {
          path: 'analysis/:batchId(\\d+)',
          component: () => import('../views/BatchView.vue'),
          meta: { title: '分析任务详情', manager: true },
        },
        {
          path: 'analytics',
          component: () => import('../views/AnalyticsView.vue'),
          meta: { title: '数据看板', manager: true },
        },
        {
          path: 'profile',
          component: () => import('../views/ProfileView.vue'),
          meta: { title: '个人信息' },
        },
        {
          path: 'forbidden',
          component: () => import('../views/StatusView.vue'),
          meta: { title: '权限不足' },
        },
        {
          path: ':pathMatch(.*)*',
          component: () => import('../views/StatusView.vue'),
          meta: { title: '页面不存在' },
        },
      ],
    },
  ],
})
router.beforeEach((to) => {
  const auth = useAuthStore()
  auth.sync()
  if (!to.meta.public && !auth.session)
    return { name: 'login', query: { redirect: to.fullPath } }
  if (to.meta.manager && !auth.canManage) return '/forbidden'
  if (to.name === 'login' && auth.session) return '/courses'
})
router.afterEach((to) => {
  document.title = `${String(to.meta.title || '课程洞察')} · CourseInsight`
})

export function safeRedirect(value: unknown) {
  return typeof value === 'string' &&
    /^\/(?!\/)/.test(value) &&
    !value.startsWith('/login') &&
    !value.includes('\\')
    ? value
    : '/courses'
}

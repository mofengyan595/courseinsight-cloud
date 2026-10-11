import { createApp } from 'vue'
import { createPinia } from 'pinia'
import ElementPlus, { ElMessage } from 'element-plus'
import zhCn from 'element-plus/es/locale/lang/zh-cn'
import 'element-plus/dist/index.css'
import './style.css'
import App from './App.vue'
import { router } from './router'
import { useAuthStore } from './stores/auth'

const app = createApp(App)
app.use(createPinia()).use(router).use(ElementPlus, { locale: zhCn })
let redirecting = false
window.addEventListener('auth-expired', () => {
  useAuthStore().signOut()
  if (router.currentRoute.value.name === 'login' || redirecting) return
  redirecting = true
  ElMessage.warning('登录已过期，请重新登录')
  void router
    .replace({
      name: 'login',
      query: { redirect: router.currentRoute.value.fullPath },
    })
    .finally(() => {
      redirecting = false
    })
})
app.mount('#app')

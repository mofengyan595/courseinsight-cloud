import { defineStore } from 'pinia'
import { computed, ref } from 'vue'
import * as api from '../api'
import { clearSession, readSession, saveSession } from '../auth/session'
import type { LoginRequest } from '../types/api'

export const useAuthStore = defineStore('auth', () => {
  const session = ref(readSession())
  const canAnalyze = computed(
    () => session.value?.role === 'TEACHER' || session.value?.role === 'ADMIN',
  )
  const canManage = computed(() => session.value?.role === 'ADMIN')
  async function signIn(data: LoginRequest) {
    session.value = saveSession(await api.login(data))
  }
  function signOut() {
    clearSession()
    session.value = null
  }
  function sync() {
    session.value = readSession()
  }
  return { session, canAnalyze, canManage, signIn, signOut, sync }
})

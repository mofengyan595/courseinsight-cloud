import { beforeEach, describe, expect, it, vi } from 'vitest'
import { clearSession, readSession, saveSession } from './session'
import { createPinia, setActivePinia } from 'pinia'
import { useAuthStore } from '../stores/auth'
import * as api from '../api'
import type { LoginResponse } from '../types/api'
vi.mock('../api', () => ({ login: vi.fn() }))
const identity: LoginResponse = {
  accessToken: 'test-only',
  tokenType: 'Bearer',
  expiresIn: 30,
  userId: 3,
  username: 'test',
  displayName: '测试',
  role: 'STUDENT',
}
beforeEach(() => {
  sessionStorage.clear()
  setActivePinia(createPinia())
})
describe('登录状态', () => {
  it('成功登录后保存过期时间、角色，退出清除', async () => {
    vi.mocked(api.login).mockResolvedValue(identity)
    const auth = useAuthStore()
    await auth.signIn({ username: 'test', password: 'test-only-password' })
    expect(readSession()?.userId).toBe(3)
    expect(auth.canManage).toBe(false)
    auth.signOut()
    expect(readSession()).toBeNull()
  })
  it('登录失败不创建状态', async () => {
    vi.mocked(api.login).mockRejectedValue(new Error('用户名或密码错误'))
    await expect(
      useAuthStore().signIn({ username: 'test', password: 'wrong' }),
    ).rejects.toThrow('用户名或密码错误')
    expect(readSession()).toBeNull()
  })
  it('过期与损坏的状态会被清除', () => {
    saveSession({ ...identity, expiresIn: -1 })
    expect(readSession()).toBeNull()
    sessionStorage.setItem('courseinsight.session.v1', '{bad-json')
    expect(readSession()).toBeNull()
    expect(sessionStorage.length).toBe(0)
    clearSession()
  })
  it.each(['STUDENT', 'TEACHER', 'ADMIN'] as const)(
    '%s 按角色区分分析与管理入口',
    async (role) => {
      vi.mocked(api.login).mockResolvedValue({ ...identity, role })
      const auth = useAuthStore()
      await auth.signIn({ username: 'test', password: 'test' })
      expect(auth.canAnalyze).toBe(role !== 'STUDENT')
      expect(auth.canManage).toBe(role === 'ADMIN')
    },
  )
})

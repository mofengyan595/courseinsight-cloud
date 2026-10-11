import type { LoginResponse } from '../types/api'

const key = 'courseinsight.session.v1'
export interface Session extends LoginResponse {
  expiresAt: number
}

export function clearSession() {
  sessionStorage.removeItem(key)
}
export function readSession(): Session | null {
  try {
    const value = sessionStorage.getItem(key)
    if (!value) return null
    const data = JSON.parse(value) as Session
    if (
      !data.accessToken ||
      !Number.isFinite(data.expiresAt) ||
      data.expiresAt <= Date.now() ||
      !['STUDENT', 'TEACHER', 'ADMIN'].includes(data.role) ||
      !data.userId
    ) {
      clearSession()
      return null
    }
    return data
  } catch {
    clearSession()
    return null
  }
}
export function saveSession(data: LoginResponse): Session {
  const session = { ...data, expiresAt: Date.now() + data.expiresIn * 1000 }
  sessionStorage.setItem(key, JSON.stringify(session))
  return session
}

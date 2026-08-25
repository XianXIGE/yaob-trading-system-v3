import { defineStore } from 'pinia'
import { ref, computed } from 'vue'
import { login as apiLogin, logout as apiLogout, getMe } from '@/api/auth'

interface UserInfo {
  username: string
  is_vip: boolean
  is_admin: boolean
  vip_expire_at?: string | null
}

export const useAuthStore = defineStore('auth', () => {
  const user = ref<UserInfo | null>(
    (() => {
      const stored = sessionStorage.getItem('yaob_user')
      try { return stored ? JSON.parse(stored) : null } catch { return null }
    })()
  )

  const isLoggedIn = computed(() => !!user.value)
  const isAdmin = computed(() => user.value?.is_admin ?? false)
  const isVip = computed(() => user.value?.is_vip ?? false)
  const username = computed(() => user.value?.username ?? '')

  async function login(username: string, password: string) {
    const res = await apiLogin(username, password)
    // 后端返回 {code:200, data:{username, is_vip, is_admin, csrf_token}}
    const data = (res as any).data || res
    user.value = {
      username: data.username,
      is_vip: data.is_vip,
      is_admin: data.is_admin,
    }
    sessionStorage.setItem('yaob_user', JSON.stringify(user.value))
    if (data.csrf_token) sessionStorage.setItem('yaob_csrf', data.csrf_token)
    return user.value
  }

  async function fetchMe() {
    try {
      const res = await getMe()
      const data = (res as any).data || res
      if (data && data.username) {
        user.value = {
          username: data.username,
          is_vip: data.is_vip,
          is_admin: data.is_admin,
          vip_expire_at: data.vip_expire_at,
        }
        sessionStorage.setItem('yaob_user', JSON.stringify(user.value))
        if (data.csrf_token) sessionStorage.setItem('yaob_csrf', data.csrf_token)
        return true
      }
      return false
    } catch {
      user.value = null
      sessionStorage.removeItem('yaob_user')
      return false
    }
  }

  async function logout() {
    try { await apiLogout() } catch { /* ignore */ }
    user.value = null
    sessionStorage.removeItem('yaob_user')
    sessionStorage.removeItem('yaob_csrf')
  }

  return { user, isLoggedIn, isAdmin, isVip, username, login, logout, fetchMe }
})

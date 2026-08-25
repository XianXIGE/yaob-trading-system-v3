import axios, { type AxiosInstance, type InternalAxiosRequestConfig, type AxiosResponse } from 'axios'
import { ElMessage } from 'element-plus'

const request: AxiosInstance = axios.create({
  baseURL: '/yaob/api',
  timeout: 30000,
  headers: { 'Content-Type': 'application/json' },
  withCredentials: true,  // 携带 session cookie
})

request.interceptors.request.use(
  (config: InternalAxiosRequestConfig) => {
    // 写请求携带 CSRF Token（从登录/注册响应或 /me 接口获取，存 sessionStorage）
    const csrf = sessionStorage.getItem('yaob_csrf')
    const method = (config.method || 'get').toUpperCase()
    if (csrf && !['GET', 'HEAD', 'OPTIONS'].includes(method)) {
      config.headers['X-CSRF-Token'] = csrf
    }
    return config
  },
  (error) => Promise.reject(error)
)

request.interceptors.response.use(
  (response: AxiosResponse) => {
    // 后端统一返回 {code, msg, data}
    const res = response.data
    if (res && typeof res.code === 'number') {
      if (res.code === 200) {
        return res
      } else {
        ElMessage.error(res.msg || '请求失败')
        return Promise.reject(new Error(res.msg || '请求失败'))
      }
    }
    return res
  },
  (error) => {
    if (error.response) {
      const { status, data } = error.response
      if (status === 401) {
        sessionStorage.removeItem('yaob_user')
        const current = window.location.pathname
        if (!current.includes('/login')) {
          ElMessage.error('登录已过期，请重新登录')
          window.location.href = '/yaob/login'
        }
      } else if (status === 403) {
        ElMessage.error(data?.msg || data?.message || '权限不足')
      } else {
        ElMessage.error(data?.msg || data?.message || `请求失败 (${status})`)
      }
    } else if (error.request) {
      ElMessage.error('网络连接失败，请检查网络')
    } else {
      ElMessage.error('请求发送失败')
    }
    return Promise.reject(error)
  }
)

export default request

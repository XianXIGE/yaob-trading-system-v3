import request from './request'

export interface LoginResponse {
  code: number
  msg: string
  data: {
    username: string
    is_vip: boolean
    is_admin: boolean
  }
}

export function login(username: string, password: string) {
  return request.post<any, LoginResponse>('/login', { username, password })
}

export function register(username: string, password: string) {
  return request.post('/register', { username, password })
}

export function logout() {
  return request.post('/logout')
}

export function getMe() {
  return request.get('/me')
}

export function changePassword(oldPassword: string, newPassword: string) {
  return request.post('/change_password', { old_password: oldPassword, new_password: newPassword })
}

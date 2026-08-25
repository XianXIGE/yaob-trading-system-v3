import request from './request'

export function getUsers() {
  return request.get('/admin/users')
}

export function setVip(username: string, vip: boolean, days: number) {
  return request.post('/admin/set_vip', { username, vip, days })
}

export function deleteUser(username: string) {
  return request.post('/admin/delete_user', { username })
}

// ===== C 功能：用户详情（admin only） =====
export function getUserOverview(userId: number) {
  return request.get(`/admin/users/${userId}/overview`)
}

export function getUserPositions(userId: number) {
  return request.get(`/admin/users/${userId}/positions`)
}

export function getUserTrades(userId: number) {
  return request.get(`/admin/users/${userId}/trades`)
}

export function getUserStrategies(userId: number) {
  return request.get(`/admin/users/${userId}/strategies`)
}

export function getUserExcluded(userId: number) {
  return request.get(`/admin/users/${userId}/excluded`)
}

export function getAdminLogs(userId?: number, limit = 200) {
  const params: Record<string, unknown> = { limit }
  if (userId != null) params.userId = userId
  return request.get('/admin/logs', { params })
}

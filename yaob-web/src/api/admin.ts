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

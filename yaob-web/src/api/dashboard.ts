import request from './request'

export function getDashboard() {
  return request.get('/dashboard')
}

export function getStats() {
  return request.get('/stats')
}

export function resetStats() {
  return request.post('/reset_stats')
}

export function testAlert() {
  return request.post('/test_alert')
}

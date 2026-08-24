import request from './request'

export function toggleAutoTrade() {
  return request.post('/toggle_auto_trade')
}

export function toggleMarginMode() {
  return request.post('/toggle_margin_mode')
}

export function togglePositionMode() {
  return request.post('/toggle_position_mode')
}

export function toggleExcludeLargeCap() {
  return request.post('/toggle_exclude_large_cap')
}

export function control(open_margin: string, leverage: number) {
  return request.post('/control', { openMargin: open_margin, leverage })
}

export function setApiKeys(apiKey: string, apiSecret: string) {
  return request.post('/set_api_keys', { api_key: apiKey, api_secret: apiSecret })
}

export function clearApiKeys() {
  return request.post('/clear_api_keys')
}

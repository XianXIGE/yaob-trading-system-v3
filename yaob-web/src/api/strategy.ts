import request from './request'

export function getStrategyParams() {
  return request.get('/get_strategy_params')
}

export function saveStrategyParams(strategyParams: Record<string, any>) {
  return request.post('/save_strategy_params', { strategy_params: strategyParams })
}

export function toggleStrategy(strategy: string) {
  return request.post('/toggle_strategy', { strategy })
}

export function addStrategy(strategy: string, params?: Record<string, any>, type?: string, description?: string) {
  return request.post('/add_strategy', { strategy, params, type, description })
}

export function deleteStrategy(strategy: string) {
  return request.delete('/delete_strategy', { params: { strategy } })
}

export function getStrategyStats() {
  return request.get('/strategy_stats')
}

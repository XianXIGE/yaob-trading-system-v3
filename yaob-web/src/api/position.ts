import request from './request'

export function getTradeHistory(params?: {
  strategy?: string
  direction?: string
  start_date?: string
  end_date?: string
  page?: number
  page_size?: number
}) {
  return request.get('/trade_history', { params })
}

export function getTradeProfitStats(params?: { start_date?: string; end_date?: string }) {
  return request.get('/trade_profit_stats', { params })
}

export function closePosition(symbol: string) {
  return request.post('/close_position', { symbol })
}

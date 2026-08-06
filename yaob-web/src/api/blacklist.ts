import request from './request'

export function getExcludedSymbolsCategorized() {
  return request.get('/get_excluded_symbols_categorized')
}

export function addExcludedSymbols(symbols: string[], category?: string) {
  return request.post('/add_excluded_symbols', { symbols, category })
}

export function removeExcludedSymbols(symbols: string[], category?: string) {
  return request.post('/remove_excluded_symbols', { symbols, category })
}

export function clearExcludedSymbols() {
  return request.post('/clear_excluded_symbols')
}

export function restoreDefaultExcluded() {
  return request.post('/restore_default_excluded')
}

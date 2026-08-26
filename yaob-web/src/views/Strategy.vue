<template>
  <div class="strategy-page">
    <div class="page-header">
      <div>
        <h3>策略配置</h3>
        <span class="page-desc">配置各策略参数和开关状态</span>
      </div>
      <el-button type="primary" size="small" @click="showAddDialog = true">+ 新增策略</el-button>
    </div>

    <div class="strategy-list">
      <div v-for="item in strategyItems" :key="item.key" class="yaob-card strategy-card">
        <div class="yaob-card-title">
          <div class="strategy-header">
            <span class="strategy-name">策略 {{ item.key.toUpperCase() }}</span>
            <el-tag :class="item.type === 'short' ? 'tag-short' : item.type === 'long' ? 'tag-long' : ''" size="small" effect="plain">
              {{ item.type === 'short' ? '做空' : item.type === 'long' ? '做多' : '自定义' }}
            </el-tag>
          </div>
          <div class="strategy-actions">
            <el-switch v-model="enabledMap[item.key]" @change="handleToggle(item.key)" :loading="togglingMap[item.key]" size="small" />
            <el-button type="danger" size="small" text @click="handleDelete(item.key)">删除</el-button>
          </div>
        </div>
        <p class="strategy-desc">{{ item.description }}</p>
        <el-table :data="getParamList(item.key)" size="small" style="width: 100%">
          <el-table-column prop="label" label="参数" min-width="120" />
          <el-table-column label="值" width="200">
            <template #default="{ row }">
              <div style="display: flex; align-items: center; gap: 4px;">
                <el-input-number v-model="paramsData[item.key][row.field]" size="small" :step="row.step || 0.01" :min="row.min !== undefined ? row.min : -Infinity" controls-position="right" style="width: 150px;" />
                <span v-if="row.unit" style="color: #999; font-size: 12px; white-space: nowrap;">{{ row.unit }}</span>
              </div>
            </template>
          </el-table-column>
        </el-table>
        <div style="text-align: right; margin-top: 12px;">
          <el-button type="primary" size="small" @click="handleSave(item.key)" :loading="savingMap[item.key]">保存</el-button>
        </div>
      </div>
    </div>

    <!-- 新增策略弹窗 -->
    <el-dialog v-model="showAddDialog" title="新增策略" width="380px" :close-on-click-modal="false">
      <el-form :model="addForm" label-width="80px">
        <el-form-item label="策略字母">
          <el-input v-model="addForm.strategy" placeholder="输入字母 A-Z（如 G）" maxlength="1" style="text-transform: uppercase;" />
        </el-form-item>
        <el-form-item label="类型">
          <el-select v-model="addForm.type" style="width: 100%;">
            <el-option label="做空" value="short" />
            <el-option label="做多" value="long" />
            <el-option label="自定义" value="custom" />
          </el-select>
        </el-form-item>
        <el-form-item label="描述">
          <el-input v-model="addForm.description" type="textarea" :rows="2" placeholder="策略描述（选填）" />
        </el-form-item>
        <el-divider content-position="left">初始参数（可自定义增删）</el-divider>
        <div v-for="(row, idx) in addForm.paramRows" :key="idx" class="param-row">
          <el-input v-model="row.key" size="small" placeholder="参数名（如 lookback_days）" style="width: 48%;" />
          <el-input-number v-model="row.value" size="small" :controls="false" placeholder="值" style="width: 40%;" />
          <el-button type="danger" size="small" text :disabled="addForm.paramRows.length <= 1" @click="removeParamRow(idx)">✕</el-button>
        </div>
        <div style="text-align: left; margin-top: 4px;">
          <el-button size="small" text type="primary" @click="addParamRow">＋ 添加参数</el-button>
        </div>
      </el-form>
      <template #footer>
        <el-button @click="showAddDialog = false">取消</el-button>
        <el-button type="primary" :loading="adding" @click="handleAdd">添加</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, onMounted, computed } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getStrategyParams, saveStrategyParams, toggleStrategy, addStrategy, deleteStrategy } from '@/api/strategy'
import { getDashboard } from '@/api/dashboard'
import { useAuthStore } from '@/stores/auth'

const auth = useAuthStore()
const paramsData = ref<Record<string, any>>({})
const enabledMap = reactive<Record<string, boolean>>({})
const togglingMap = reactive<Record<string, boolean>>({})
const savingMap = reactive<Record<string, boolean>>({})
const showAddDialog = ref(false)
const adding = ref(false)
const addForm = reactive({ strategy: '', type: 'short', description: '', paramRows: [{ key: 'tp_ratio', value: 0 }, { key: 'sl_ratio', value: 0 }] })

function addParamRow() {
  addForm.paramRows.push({ key: '', value: 0 })
}
function removeParamRow(idx: number) {
  if (addForm.paramRows.length <= 1) return
  addForm.paramRows.splice(idx, 1)
}
// 把参数行数组转换为 { key: value } 对象，忽略空参数名
function buildParams() {
  const p: Record<string, number> = {}
  for (const row of addForm.paramRows) {
    const k = row.key.trim()
    if (k) p[k] = row.value ?? 0
  }
  return p
}
function resetAddForm() {
  addForm.strategy = ''
  addForm.description = ''
  addForm.paramRows = [{ key: 'tp_ratio', value: 0 }, { key: 'sl_ratio', value: 0 }]
}

// 内置策略描述（A-F），新增的自定义策略用用户输入的描述
const builtinDesc: Record<string, { type: string; description: string }> = {
  a: { type: 'short', description: '做空 - 24h涨幅区间扫描，寻找高涨幅标的做空' },
  b: { type: 'short', description: '做空 - 当日涨幅突破，大幅上涨后做空' },
  c: { type: 'long', description: '做多 - 高点回撤反弹，回落后做多' },
  d: { type: 'short', description: '做空 - 短时急涨做空，分钟级急涨做空' },
  e: { type: 'long', description: '强趋势回踩：30天涨幅>100%，EMA50上方，回撤20%-40%至0.618 Fib' },
  f: { type: 'fibonacci', description: '1小时斐波那契位置，15分钟确认入场' },
  g: { type: 'multi', description: '日内多空三重过滤：1h EMA20/60趋势+量比+RSI，6子信号(关注做多/回调做多/超跌反弹/关注做空/反弹做空/冲高回落做空)，4h同向评级A/B' },
  h: { type: 'multi', description: 'BTC/ETH专属：只绑BTCUSDT/ETHUSDT，4h多空分水岭(EMA20)，回调/超跌做多，反抽分水岭滞涨做空，防守/目标动态取4h EMA' },
}

// 自定义策略描述存储
const customDesc = ref<Record<string, { type: string; description: string }>>({})

const strategyItems = computed(() => {
  const keys = Object.keys(paramsData.value)
  return keys.map(k => {
    const builtin = builtinDesc[k.toLowerCase()]
    // 优先用后端持久化的元信息（自定义策略入库后刷新不丢）
    const p = paramsData.value[k]
    const storedType = p?.strategy_type
    const storedDesc = p?.description
    const custom = customDesc.value[k]
    return {
      key: k,
      type: storedType || builtin?.type || custom?.type || 'custom',
      description: storedDesc || builtin?.description || custom?.description || '自定义策略',
    }
  }).sort((a, b) => a.key.localeCompare(b.key))
})

const paramDefs: Record<string, { label: string; field: string; step?: number; min?: number; unit?: string }[]> = {
  a: [
    { label: '回看天数', field: 'lookback_days', step: 1, min: 1, unit: '天' },
    { label: '涨幅下限', field: 'gain_min', step: 0.01, unit: '%' },
    { label: '涨幅上限', field: 'gain_max', step: 0.01, unit: '%' },
    { label: '最低成交额', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  b: [
    { label: '涨幅阈值', field: 'gain_threshold', step: 0.01, unit: '%' },
    { label: '最低成交额', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  c: [
    { label: '回看天数', field: 'lookback_days', step: 1, min: 1, unit: '天' },
    { label: '回撤阈值', field: 'drop_threshold', step: 0.01, unit: '%' },
    { label: '最低成交额', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  d: [
    { label: '窗口', field: 'window_minutes', step: 1, min: 1, unit: '分钟' },
    { label: '涨幅阈值', field: 'gain_threshold', step: 0.01, unit: '%' },
    { label: '最低成交额', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  e: [
    { label: '30天涨幅下限', field: 'gain_30d_min', step: 1, min: 0, unit: '%' },
    { label: 'EMA周期', field: 'ema_period', step: 1, min: 1, unit: '根' },
    { label: '回调下限', field: 'pullback_min', step: 1, min: 0, unit: '%' },
    { label: '回调上限', field: 'pullback_max', step: 1, min: 0, unit: '%' },
    { label: '斐波那契入场位', field: 'fib_entry', step: 0.001 },
    { label: '成交量放大倍数', field: 'volume_mult', step: 0.1, min: 1 },
    { label: 'RSI回升阈值', field: 'rsi_threshold', step: 1, min: 0, unit: '' },
    { label: '24h成交额下限', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  f: [
    { label: '回看小时', field: 'lookback_hours', step: 1, min: 1, unit: '小时' },
    { label: '斐波那契做多', field: 'fib_long', step: 0.001 },
    { label: '斐波那契做空', field: 'fib_short', step: 0.001 },
    { label: '触及容差', field: 'tolerance_ratio', step: 0.1, min: 0, unit: '%' },
    { label: '24h成交额下限', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 1, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  g: [
    { label: '短EMA周期', field: 'ema_short', step: 1, min: 1, unit: '根' },
    { label: '长EMA周期', field: 'ema_long', step: 1, min: 1, unit: '根' },
    { label: '顺势最低量比', field: 'vol_ratio_min', step: 0.1, min: 1, unit: '倍' },
    { label: '超跌RSI阈值', field: 'rsi_oversold', step: 1, min: 0, max: 100, unit: '' },
    { label: '上影/实体比', field: 'wick_body_ratio', step: 0.1, min: 0, unit: '倍' },
    { label: '24h成交额下限', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '价格涨幅(止盈)', field: 'tp_ratio', step: 1, min: 0, unit: '%' },
    { label: '价格跌幅(止损)', field: 'sl_ratio', step: 1, unit: '%' },
  ],
  h: [
    { label: '4h分水岭EMA', field: 'ema_short', step: 1, min: 1, unit: '根' },
    { label: '4h趋势EMA', field: 'ema_long', step: 1, min: 1, unit: '根' },
    { label: '24h成交额下限', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
    { label: '止盈比例', field: 'tp_ratio', step: 1, min: 0, unit: '%' },
    { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
  ],
}

// 通用参数模板（新增策略默认显示这些参数）
const defaultParamDefs = [
  { label: '最低成交额', field: 'vol_min', step: 1000000, min: 0, unit: 'USDT' },
  { label: '止盈比例', field: 'tp_ratio', step: 10, min: 0, unit: '%' },
  { label: '止损比例', field: 'sl_ratio', step: 1, unit: '%' },
]

function getParamList(key: string) {
  return paramDefs[key.toLowerCase()] || defaultParamDefs
}

async function fetchParams() {
  try {
    const [paramsRes, dashRes] = await Promise.all([getStrategyParams(), getDashboard()])
    const pd = (paramsRes as any)?.data || paramsRes
    const dd = (dashRes as any)?.data || dashRes
    paramsData.value = pd || {}
    const states = dd?.strategyStates || {}
    for (const s of Object.keys(pd || {})) {
      enabledMap[s] = states[s.toUpperCase()] ?? false
    }
  } catch { /* handled */ }
}

async function handleToggle(key: string) {
  togglingMap[key] = true
  try {
    await toggleStrategy(key)
    ElMessage.success(`策略 ${key.toUpperCase()} 已${enabledMap[key] ? '开启' : '关闭'}`)
  } catch {
    enabledMap[key] = !enabledMap[key]
  } finally {
    togglingMap[key] = false
  }
}

async function handleSave(key: string) {
  savingMap[key] = true
  try {
    await saveStrategyParams({ [key]: paramsData.value[key] })
    ElMessage.success(`策略 ${key.toUpperCase()} 参数已保存`)
  } catch {
    /* handled */
  } finally {
    savingMap[key] = false
  }
}

async function handleAdd() {
  const s = addForm.strategy.trim().toUpperCase()
  if (!s || !/^[A-Z]$/.test(s)) {
    ElMessage.warning('请输入单个字母 A-Z')
    return
  }
  const params = buildParams()
  if (Object.keys(params).length === 0) {
    ElMessage.warning('请至少填写一个参数项')
    return
  }
  adding.value = true
  try {
    await addStrategy(s, params, addForm.type, addForm.description || `自定义策略 ${s}`)
    // 用新增时的初始参数填充显示
    paramsData.value[s.toLowerCase()] = {
      ...params,
      strategy_type: addForm.type,
      description: addForm.description || `自定义策略 ${s}`,
    }
    enabledMap[s.toLowerCase()] = false
    ElMessage.success(`策略 ${s} 已添加`)
    showAddDialog.value = false
    resetAddForm()
  } catch {
    /* handled */
  } finally {
    adding.value = false
  }
}

async function handleDelete(key: string) {
  try {
    await ElMessageBox.confirm(
      `确定要删除策略 ${key.toUpperCase()} 吗？删除后不可恢复。`,
      '删除策略',
      { type: 'warning', confirmButtonText: '确认删除', cancelButtonText: '取消' }
    )
    await deleteStrategy(key)
    // 从本地数据中移除
    const newData = { ...paramsData.value }
    delete newData[key]
    paramsData.value = newData
    delete enabledMap[key]
    ElMessage.success(`策略 ${key.toUpperCase()} 已删除`)
  } catch { /* cancelled or error */ }
}

onMounted(() => {
  auth.fetchMe().finally(() => {
    if (!auth.isVip) {
      ElMessage.warning('策略配置需要 VIP 权限')
    }
  })
  fetchParams()
})
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
  display: flex;
  justify-content: space-between;
  align-items: flex-start;
}

.page-header h3 {
  font-size: 18px;
  margin-bottom: 4px;
}

.page-desc {
  font-size: 13px;
  color: var(--text-secondary);
}

.strategy-list {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(380px, 1fr));
  gap: 16px;
}

.strategy-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.strategy-name {
  font-weight: 600;
}

.strategy-actions {
  display: flex;
  align-items: center;
  gap: 8px;
}

.strategy-desc {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 12px;
  line-height: 1.5;
}

.param-row {
  display: flex;
  align-items: center;
  gap: 6px;
  margin-bottom: 8px;
}

.param-row .el-button {
  flex: 0 0 auto;
}

@media (max-width: 768px) {
  .strategy-list {
    grid-template-columns: 1fr;
  }
}
</style>

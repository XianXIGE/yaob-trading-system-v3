<template>
  <div class="dashboard-page">
    <!-- Scanner Status -->
    <div class="yaob-card">
      <div class="yaob-card-title">
        <span>扫描器状态</span>
        <el-tag :type="scannerRunning ? 'success' : 'info'" size="small" effect="dark">
          <span class="status-dot" :class="scannerStatusClass"></span>
          {{ scannerStatusText }}
        </el-tag>
      </div>
      <div class="stats-grid">
        <div class="stat-item">
          <div class="stat-label">扫描状态</div>
          <div class="stat-value">{{ data?.scannerStatus || '-' }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">上次耗时</div>
          <div class="stat-value">{{ data?.lastScanDuration?.toFixed(1) || '0' }}s</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">下次扫描</div>
          <div class="stat-value countdown">{{ countdownDisplay }}</div>
        </div>
      </div>
    </div>

    <!-- Account Overview -->
    <div class="yaob-card">
      <div class="yaob-card-title">账户概览</div>
      <div class="stats-grid">
        <div class="stat-item">
          <div class="stat-label">总资产 (USDT)</div>
          <div class="stat-value">{{ formatNum(data?.accountTotalAssets) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">可用保证金</div>
          <div class="stat-value">{{ formatNum(data?.availableMargin) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">开仓保证金</div>
          <div class="stat-value">{{ formatNum(data?.openMargin) }}</div>
        </div>
        <div class="stat-item">
          <div class="stat-label">杠杆倍数</div>
          <div class="stat-value">{{ data?.leverage || 0 }}x</div>
        </div>
      </div>
    </div>

    <!-- Controls -->
    <div class="yaob-card">
      <div class="yaob-card-title">程序控制</div>
      <div class="controls-row">
        <div class="control-item">
          <span class="control-label">自动交易</span>
          <el-switch v-model="autoTrade" @change="handleToggleAutoTrade" :loading="ctrlLoading" />
        </div>
        <div class="control-item">
          <span class="control-label">全仓/逐仓</span>
          <el-switch
            v-model="crossMode"
            active-text="全仓"
            inactive-text="逐仓"
            @change="handleToggleMarginMode"
            :loading="ctrlLoading"
          />
        </div>
        <div class="control-item">
          <span class="control-label">排除大盘币</span>
          <el-switch v-model="excludeLargeCap" @change="handleToggleExcludeLargeCap" :loading="ctrlLoading" />
        </div>
        <div class="control-item">
          <el-button size="small" @click="showApiDialog = true">
            <el-icon><Key /></el-icon> API 密钥
          </el-button>
        </div>
      </div>
      <div class="controls-row" style="margin-top: 12px;">
        <div class="control-item">
          <span class="control-label">开仓保证金</span>
          <el-input-number v-model="editMargin" :min="1" :step="1" :precision="2" size="small" style="width: 130px;" />
          <span style="color: #999; font-size: 12px;">USDT</span>
        </div>
        <div class="control-item">
          <span class="control-label">杠杆倍数</span>
          <el-input-number v-model="editLeverage" :min="1" :max="125" :step="1" size="small" style="width: 110px;" />
          <span style="color: #999; font-size: 12px;">x</span>
        </div>
        <el-button size="small" type="primary" @click="handleSaveMarginLeverage" :loading="marginSaving">保存</el-button>
      </div>
    </div>

    <!-- 风控状态 -->
    <div class="yaob-card">
      <div class="yaob-card-title">🛡️ 风控状态</div>
      <div class="risk-items">
        <div class="risk-item">
          <span class="risk-label">当日盈亏</span>
          <span :class="['risk-value', dailyPnl >= 0 ? 'profit' : 'loss']">
            {{ dailyPnl.toFixed(2) }} U
          </span>
        </div>
        <div class="risk-item">
          <span class="risk-label">熔断状态</span>
          <span :class="['risk-value', circuitBreaker ? 'loss' : 'profit']">
            {{ circuitBreaker ? '⚠️ 已熔断' : '✅ 正常' }}
          </span>
        </div>
        <div class="risk-item">
          <span class="risk-label">持仓数</span>
          <span class="risk-value">{{ positionsCount }} / 10</span>
        </div>
      </div>
    </div>

    <!-- Strategy States -->
    <div class="yaob-card" v-if="data?.strategyStates">
      <div class="yaob-card-title">策略状态</div>
      <div class="strategy-states-grid">
        <div v-for="(state, key) in data.strategyStates" :key="key" class="strategy-state-item" :class="{ 'strategy-active': state }">
          <span class="strategy-state-name">{{ key }}</span>
          <span class="strategy-state-dot" :class="state ? 'dot-on' : 'dot-off'"></span>
          <span class="strategy-state-label" :class="state ? 'label-on' : 'label-off'">{{ state ? '开启' : '关闭' }}</span>
        </div>
      </div>
    </div>

    <!-- Stats Overview -->
    <StatsCard title="统计概览" :items="statsItems" />

    <!-- Positions -->
    <PositionTable :positions="data?.positions || []" @refresh="fetchData" />

    <!-- Candidate Pool -->
    <CandidateTable :candidates="data?.candidatePool || []" :loading="loading" @refresh="fetchData" />

    <!-- API Key Dialog -->
    <ApiKeyDialog v-model="showApiDialog" @saved="fetchData" />
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted, onUnmounted } from 'vue'
import { ElMessageBox, ElMessage } from 'element-plus'
import { Key } from '@element-plus/icons-vue'
import { getDashboard, getStats } from '@/api/dashboard'
import { toggleAutoTrade, toggleMarginMode, toggleExcludeLargeCap, control } from '@/api/trade'
import PositionTable from '@/components/PositionTable.vue'
import CandidateTable from '@/components/CandidateTable.vue'
import StatsCard from '@/components/StatsCard.vue'
import ApiKeyDialog from '@/components/ApiKeyDialog.vue'

const data = ref<any>(null)
const stats = ref<any>(null)
const loading = ref(false)
const ctrlLoading = ref(false)
const showApiDialog = ref(false)

const autoTrade = ref(false)
const crossMode = ref(false)
const excludeLargeCap = ref(false)
const editMargin = ref<number>(5)
const editLeverage = ref<number>(5)
const marginSaving = ref(false)

// 风控状态
const dailyPnl = computed(() => data.value?.dailyPnl ?? 0)
const circuitBreaker = computed(() => data.value?.circuitBreaker ?? false)
const positionsCount = computed(() => data.value?.positions?.length ?? 0)

// Countdown
const nextScanTime = ref<number>(0)
const now = ref<number>(Date.now())
let countdownTimer: ReturnType<typeof setInterval>
let refreshTimer: ReturnType<typeof setInterval>

const scannerRunning = computed(() => {
  const s = data.value?.scannerStatus || ''
  return s.includes('扫描') && !s.includes('倒计时')
})
const scannerStatusClass = computed(() => scannerRunning.value ? 'running' : 'stopped')
const scannerStatusText = computed(() => data.value?.scannerStatus || '未知')

const countdownDisplay = computed(() => {
  const diff = Math.max(0, nextScanTime.value - now.value)
  const seconds = Math.ceil(diff / 1000)
  const m = Math.floor(seconds / 60)
  const s = seconds % 60
  return `${String(m).padStart(2, '0')}:${String(s).padStart(2, '0')}`
})

const statsItems = computed(() => [
  { label: '总交易次数', value: stats.value?.totalTrades ?? '-' },
  { label: '盈利次数', value: stats.value?.winTrades ?? '-', color: 'profit-color' },
  { label: '胜率', value: stats.value?.winRate != null ? `${stats.value.winRate.toFixed(1)}%` : '-' },
  { label: '累计盈亏', value: stats.value?.totalPnl != null ? Number(stats.value.totalPnl).toFixed(2) : '-', color: (stats.value?.totalPnl ?? 0) >= 0 ? 'profit-color' : 'loss-color' },
])

function formatNum(v: any): string {
  if (v == null) return '-'
  return Number(v).toFixed(2)
}

async function fetchData() {
  loading.value = true
  try {
    const [d, s] = await Promise.all([getDashboard(), getStats()])
    // 后端返回 {code:200, data:{...}}, request 拦截器已返回 res(body)
    const dashData = (d as any)?.data || d
    const statsData = (s as any)?.data || s
    data.value = dashData
    stats.value = statsData
    autoTrade.value = dashData?.autoTradeEnabled ?? false
    crossMode.value = dashData?.marginMode === 'cross'
    excludeLargeCap.value = dashData?.excludeLargeCap ?? false
    editMargin.value = dashData?.openMargin ?? 5
    editLeverage.value = dashData?.leverage ?? 5
    if (dashData?.nextScanTimestamp) {
      nextScanTime.value = dashData.nextScanTimestamp * 1000
    }
  } catch { /* handled */ } finally {
    loading.value = false
  }
}

async function handleToggleAutoTrade(val: boolean) {
  if (val) {
    try {
      await ElMessageBox.confirm(
        '开启自动交易后，系统将自动执行开仓/平仓操作。确认开启？',
        '开启自动交易',
        { type: 'warning', confirmButtonText: '确认开启', cancelButtonText: '取消' }
      )
    } catch {
      autoTrade.value = false
      return
    }
  }
  ctrlLoading.value = true
  try {
    await toggleAutoTrade()
    ElMessage.success(`自动交易已${val ? '开启' : '关闭'}`)
    await fetchData()
  } catch {
    autoTrade.value = !val
  } finally {
    ctrlLoading.value = false
  }
}

async function handleToggleMarginMode(val: boolean) {
  ctrlLoading.value = true
  try {
    await toggleMarginMode()
    ElMessage.success(`已切换为${val ? '全仓' : '逐仓'}模式`)
    await fetchData()
  } catch {
    crossMode.value = !val
  } finally {
    ctrlLoading.value = false
  }
}

async function handleToggleExcludeLargeCap(val: boolean) {
  ctrlLoading.value = true
  try {
    await toggleExcludeLargeCap()
    ElMessage.success(`排除大盘币已${val ? '开启' : '关闭'}`)
    await fetchData()
  } catch {
    excludeLargeCap.value = !val
  } finally {
    ctrlLoading.value = false
  }
}

async function handleSaveMarginLeverage() {
  if (editMargin.value <= 0 || editLeverage.value <= 0) {
    ElMessage.warning('保证金和杠杆必须大于 0')
    return
  }
  marginSaving.value = true
  try {
    await control(String(editMargin.value), editLeverage.value)
    ElMessage.success(`已设置：保证金 ${editMargin.value} USDT，杠杆 ${editLeverage.value}x`)
    await fetchData()
  } catch {
    ElMessage.error('保存失败')
  } finally {
    marginSaving.value = false
  }
}

onMounted(() => {
  fetchData()
  countdownTimer = setInterval(() => { now.value = Date.now() }, 100)
  refreshTimer = setInterval(fetchData, 15000)
})

onUnmounted(() => {
  clearInterval(countdownTimer)
  clearInterval(refreshTimer)
})
</script>

<style scoped>
.controls-row {
  display: flex;
  flex-wrap: wrap;
  gap: 20px;
  align-items: center;
}

.control-item {
  display: flex;
  align-items: center;
  gap: 8px;
}

.control-label {
  font-size: 14px;
  color: var(--text-secondary);
}

.strategy-states-grid {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(120px, 1fr));
  gap: 10px;
}

.strategy-state-item {
  display: flex;
  align-items: center;
  justify-content: space-between;
  background: var(--bg-primary);
  padding: 8px 12px;
  border-radius: 6px;
  border: 1px solid var(--border-color);
  transition: all 0.3s;
}

.strategy-state-item.strategy-active {
  background: rgba(64, 158, 255, 0.08);
  border-color: rgba(64, 158, 255, 0.3);
}

.strategy-state-name {
  font-weight: 600;
  font-size: 14px;
}

.strategy-state-dot {
  width: 8px;
  height: 8px;
  border-radius: 50%;
}

.dot-on {
  background: #409eff;
  box-shadow: 0 0 6px rgba(64, 158, 255, 0.5);
}

.dot-off {
  background: #c0c4cc;
}

.strategy-state-label {
  font-size: 12px;
  font-weight: 500;
}

.label-on {
  color: #409eff;
}

.label-off {
  color: #909399;
}

.risk-items {
  display: grid;
  grid-template-columns: repeat(auto-fit, minmax(160px, 1fr));
  gap: 16px;
}

.risk-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 12px 16px;
  background: var(--bg-primary);
  border-radius: 8px;
  border: 1px solid var(--border-color);
}

.risk-label {
  font-size: 13px;
  color: var(--text-secondary);
}

.risk-value {
  font-size: 18px;
  font-weight: 700;
}

.risk-value.profit {
  color: #00d68f;
}

.risk-value.loss {
  color: #ff4d4f;
}

@media (max-width: 768px) {
  .controls-row {
    flex-direction: column;
    align-items: flex-start;
    gap: 12px;
  }
  .strategy-states-grid {
    grid-template-columns: repeat(2, 1fr);
  }
}
</style>

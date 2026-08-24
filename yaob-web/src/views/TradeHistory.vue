<template>
  <div class="history-page">
    <div class="page-header">
      <h3>交易流水</h3>
    </div>

    <!-- Filters -->
    <div class="yaob-card">
      <div class="filter-row">
        <el-select v-model="filter.strategy" placeholder="策略" clearable size="small" style="width: 100px;">
          <el-option v-for="s in ['A','B','C','D','E','F','G']" :key="s" :label="s" :value="s" />
        </el-select>
        <el-select v-model="filter.direction" placeholder="方向" clearable size="small" style="width: 100px;">
          <el-option label="做空" value="SHORT" />
          <el-option label="做多" value="LONG" />
        </el-select>
        <el-date-picker v-model="filter.dateRange" type="daterange" size="small" range-separator="-"
          start-placeholder="开始" end-placeholder="结束" value-format="YYYY-MM-DD"
          style="width: 240px;" />
        <el-button size="small" type="primary" @click="fetchData">查询</el-button>
        <el-button size="small" @click="exportCSV" :loading="exporting" :disabled="!trades.length">导出CSV</el-button>
      </div>
    </div>

    <!-- Charts - 只在有数据时才渲染 -->
    <template v-if="trades.length > 0">
      <ProfitChart v-if="profitData?.daily?.length" title="每日盈亏" :option="dailyChartOption" :height="280" />
      <div class="charts-grid">
        <ProfitChart v-if="profitData?.by_symbol?.length" title="标的盈亏排行" :option="symbolChartOption" :height="280" />
        <ProfitChart v-if="strategyStats.length" title="策略胜率对比" :option="strategyChartOption" :height="280" />
      </div>
    </template>

    <!-- Trade History Table -->
    <div class="yaob-card">
      <div class="yaob-card-title">交易记录 ({{ trades.length }})</div>
      <el-table :data="trades" style="width: 100%" size="small" class="hide-on-mobile" empty-text="暂无交易记录">
        <el-table-column prop="symbol" label="交易对" width="120" />
        <el-table-column prop="strategy" label="策略" width="60" />
        <el-table-column label="方向" width="70">
          <template #default="{ row }">
            <el-tag :class="row.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
              {{ row.direction === 'SHORT' ? '空' : '多' }}
            </el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="qty" label="数量" width="90" />
        <el-table-column prop="entryPrice" label="开仓价" width="90" />
        <el-table-column prop="exitPrice" label="平仓价" width="90" />
        <el-table-column label="盈亏%" width="80">
          <template #default="{ row }">
            <span :class="(row.pnlRatio ?? 0) >= 0 ? 'profit-color' : 'loss-color'">
              {{ row.pnlRatio != null ? Number(row.pnlRatio).toFixed(2) + '%' : '-' }}
            </span>
          </template>
        </el-table-column>
        <el-table-column prop="openedAt" label="开仓时间" width="150" />
        <el-table-column prop="closedAt" label="平仓时间" width="150" />
        <el-table-column prop="closeReason" label="平仓原因" min-width="80" show-overflow-tooltip />
      </el-table>

      <!-- Mobile cards -->
      <div class="mobile-cards hide-on-desktop">
        <div v-for="t in trades" :key="t.id || t.symbol + t.openedAt" class="mobile-card-item">
          <div class="card-row">
            <span class="label">{{ t.symbol }}</span>
            <el-tag :class="t.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
              {{ t.direction === 'SHORT' ? '空' : '多' }}
            </el-tag>
          </div>
          <div class="card-row"><span class="label">策略</span><span>{{ t.strategy }}</span></div>
          <div class="card-row"><span class="label">开仓价</span><span>{{ t.entryPrice }}</span></div>
          <div class="card-row"><span class="label">平仓价</span><span>{{ t.exitPrice }}</span></div>
          <div class="card-row">
            <span class="label">盈亏%</span>
            <span :class="(t.pnlRatio ?? 0) >= 0 ? 'profit-color' : 'loss-color'">
              {{ t.pnlRatio != null ? Number(t.pnlRatio).toFixed(2) + '%' : '-' }}
            </span>
          </div>
          <div class="card-row"><span class="label">开仓</span><span style="font-size:12px;">{{ t.openedAt }}</span></div>
          <div class="card-row"><span class="label">平仓</span><span style="font-size:12px;">{{ t.closedAt }}</span></div>
          <div class="card-row"><span class="label">原因</span><span style="font-size:12px;">{{ t.closeReason }}</span></div>
        </div>
        <el-empty v-if="!trades.length" description="暂无交易记录" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive, computed, onMounted } from 'vue'
import { getTradeHistory, getTradeProfitStats } from '@/api/position'
import { getStrategyStats } from '@/api/strategy'
import ProfitChart from '@/components/ProfitChart.vue'

const trades = ref<any[]>([])
const profitData = ref<any>(null)
const strategyStats = ref<any[]>([])
const exporting = ref(false)

const filter = reactive({
  strategy: '',
  direction: '',
  dateRange: null as [string, string] | null,
})

const dailyChartOption = computed(() => {
  const daily = profitData.value?.daily || []
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: daily.map((d: any) => d.day || d.date),
      axisLabel: { color: '#606266' },
      axisLine: { lineStyle: { color: '#e4e7ed' } },
    },
    yAxis: {
      type: 'value',
      axisLabel: { color: '#606266' },
      splitLine: { lineStyle: { color: '#ebeef5' } },
    },
    series: [{
      type: 'bar',
      data: daily.map((d: any) => ({
        value: d.pnl,
        itemStyle: { color: d.pnl >= 0 ? '#67c23a' : '#f56c6c' },
      })),
    }],
  }
})

const symbolChartOption = computed(() => {
  const bySymbol = (profitData.value?.by_symbol || []).slice(0, 15)
  return {
    tooltip: { trigger: 'axis' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'value',
      axisLabel: { color: '#606266' },
      splitLine: { lineStyle: { color: '#ebeef5' } },
    },
    yAxis: {
      type: 'category',
      data: bySymbol.map((s: any) => s.symbol),
      axisLabel: { color: '#606266' },
      axisLine: { lineStyle: { color: '#e4e7ed' } },
    },
    series: [{
      type: 'bar',
      data: bySymbol.map((s: any) => ({
        value: s.pnl,
        itemStyle: { color: s.pnl >= 0 ? '#67c23a' : '#f56c6c' },
      })),
    }],
  }
})

const strategyChartOption = computed(() => {
  const ss = strategyStats.value
  return {
    tooltip: { trigger: 'item' },
    grid: { left: '3%', right: '4%', bottom: '3%', containLabel: true },
    xAxis: {
      type: 'category',
      data: ss.map((s: any) => s.strategy),
      axisLabel: { color: '#606266' },
      axisLine: { lineStyle: { color: '#e4e7ed' } },
    },
    yAxis: {
      type: 'value',
      max: 100,
      axisLabel: { color: '#606266', formatter: '{value}%' },
      splitLine: { lineStyle: { color: '#ebeef5' } },
    },
    series: [{
      type: 'bar',
      data: ss.map((s: any) => ({
        value: s.winRate ?? s.win_rate ?? 0,
        itemStyle: { color: '#409eff' },
      })),
      label: { show: true, position: 'top', formatter: '{c}%', color: '#606266' },
    }],
  }
})

async function fetchData() {
  try {
    const [histRes, profitRes, statsRes] = await Promise.all([
      getTradeHistory(),
      getTradeProfitStats(),
      getStrategyStats(),
    ])
    const histData = (histRes as any)?.data || histRes
    const pfData = (profitRes as any)?.data || profitRes
    const stData = (statsRes as any)?.data || statsRes
    trades.value = histData?.trades || []
    profitData.value = pfData
    strategyStats.value = stData?.strategyStats || stData?.strategy_stats || []
  } catch { /* handled */ }
}

function exportCSV() {
  exporting.value = true
  try {
    const headers = ['交易对', '策略', '方向', '数量', '开仓价', '平仓价', '盈亏%', '开仓时间', '平仓时间', '平仓原因']
    const rows = trades.value.map((t) => [
      t.symbol, t.strategy, t.direction, t.qty, t.entryPrice, t.exitPrice,
      t.pnlRatio, t.openedAt, t.closedAt, t.closeReason,
    ])
    const csv = [headers, ...rows].map((r) => r.map((c) => `"${c ?? ''}"`).join(',')).join('\n')
    const blob = new Blob(['\ufeff' + csv], { type: 'text/csv;charset=utf-8;' })
    const url = URL.createObjectURL(blob)
    const a = document.createElement('a')
    a.href = url
    a.download = `trade_history_${new Date().toISOString().slice(0, 10)}.csv`
    a.click()
    URL.revokeObjectURL(url)
  } finally {
    exporting.value = false
  }
}

onMounted(fetchData)
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
}
.page-header h3 {
  font-size: 18px;
}

.filter-row {
  display: flex;
  flex-wrap: wrap;
  gap: 10px;
  align-items: center;
}

.charts-grid {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
}

@media (max-width: 768px) {
  .charts-grid {
    grid-template-columns: 1fr;
  }
  .filter-row {
    flex-direction: column;
    align-items: stretch;
  }
  .filter-row .el-select,
  .filter-row .el-date-picker {
    width: 100% !important;
  }
}
</style>

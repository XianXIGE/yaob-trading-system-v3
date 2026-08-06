<template>
  <div class="yaob-card">
    <div class="yaob-card-title">
      <span>持仓列表</span>
      <span v-if="positions.length" class="count-badge">{{ positions.length }} 个持仓</span>
    </div>
    <!-- Desktop table -->
    <el-table :data="positions" style="width: 100%" size="small" class="hide-on-mobile" empty-text="暂无持仓">
      <el-table-column prop="symbol" label="交易对" min-width="100" />
      <el-table-column label="方向" min-width="60">
        <template #default="{ row }">
          <el-tag :class="row.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
            {{ row.direction === 'SHORT' ? '空' : '多' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="数量" min-width="80">
        <template #default="{ row }">{{ formatNum(row.amount) }}</template>
      </el-table-column>
      <el-table-column label="保证金" min-width="80">
        <template #default="{ row }">{{ formatNum(row.margin) }}</template>
      </el-table-column>
      <el-table-column label="杠杆" min-width="55">
        <template #default="{ row }">{{ row.leverage }}x</template>
      </el-table-column>
      <el-table-column label="开仓价" min-width="80">
        <template #default="{ row }">{{ formatPrice(row.entry_price) }}</template>
      </el-table-column>
      <el-table-column label="当前价" min-width="80">
        <template #default="{ row }">{{ formatPrice(row.current_price) }}</template>
      </el-table-column>
      <el-table-column label="盈亏" min-width="80">
        <template #default="{ row }">
          <span :class="(row.pnl ?? 0) >= 0 ? 'profit-color' : 'loss-color'" style="font-weight: 600;">
            {{ Number(row.pnl ?? 0).toFixed(2) }}
          </span>
        </template>
      </el-table-column>
      <el-table-column label="盈亏%" min-width="75">
        <template #default="{ row }">
          <span :class="(row.pnl_ratio ?? row.pnl_percent ?? 0) >= 0 ? 'profit-color' : 'loss-color'" style="font-weight: 600;">
            {{ Number(row.pnl_ratio ?? row.pnl_percent ?? 0).toFixed(2) }}%
          </span>
        </template>
      </el-table-column>
      <el-table-column label="操作" min-width="70" fixed="right">
        <template #default="{ row }">
          <el-button type="danger" size="small" plain @click="handleClose(row)">平仓</el-button>
        </template>
      </el-table-column>
    </el-table>
    <!-- Mobile cards -->
    <div class="mobile-cards hide-on-desktop">
      <div v-for="pos in positions" :key="pos.symbol" class="mobile-card-item">
        <div class="card-row">
          <span class="label" style="font-weight: 600;">{{ pos.symbol }}</span>
          <el-tag :class="pos.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
            {{ pos.direction === 'SHORT' ? '空' : '多' }}
          </el-tag>
        </div>
        <div class="card-row"><span class="label">数量</span><span>{{ formatNum(pos.amount) }}</span></div>
        <div class="card-row"><span class="label">杠杆</span><span>{{ pos.leverage }}x</span></div>
        <div class="card-row"><span class="label">开仓价</span><span>{{ formatPrice(pos.entry_price) }}</span></div>
        <div class="card-row"><span class="label">当前价</span><span>{{ formatPrice(pos.current_price) }}</span></div>
        <div class="card-row">
          <span class="label">盈亏</span>
          <span :class="(pos.pnl ?? 0) >= 0 ? 'profit-color' : 'loss-color'" style="font-weight: 600;">
            {{ Number(pos.pnl ?? 0).toFixed(2) }} ({{ Number(pos.pnl_ratio ?? pos.pnl_percent ?? 0).toFixed(2) }}%)
          </span>
        </div>
        <div style="text-align: right; margin-top: 8px;">
          <el-button type="danger" size="small" plain @click="handleClose(pos)">平仓</el-button>
        </div>
      </div>
      <el-empty v-if="!positions.length" description="暂无持仓" />
    </div>
  </div>
</template>

<script setup lang="ts">
import { ElMessageBox, ElMessage } from 'element-plus'
import { closePosition } from '@/api/position'

interface Position {
  symbol: string
  direction: string
  amount: number
  margin: number
  leverage: number
  entry_price: number
  current_price: number
  pnl: number
  pnl_ratio: number
  pnl_percent: number
}

const props = defineProps<{ positions: Position[] }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

function formatNum(v: any): string {
  if (v == null) return '-'
  return Number(v).toFixed(4)
}

function formatPrice(v: any): string {
  if (v == null) return '-'
  const n = Number(v)
  if (n >= 1000) return n.toFixed(2)
  if (n >= 1) return n.toFixed(4)
  if (n >= 0.01) return n.toFixed(6)
  return n.toPrecision(4)
}

async function handleClose(pos: Position) {
  try {
    await ElMessageBox.confirm(
      `确定要平仓 ${pos.symbol} ${pos.direction === 'SHORT' ? '做空' : '做多'} 吗？`,
      '平仓确认',
      { type: 'warning', confirmButtonText: '确认平仓', cancelButtonText: '取消' }
    )
    await closePosition(pos.symbol)
    ElMessage.success(`${pos.symbol} 平仓指令已发送`)
    emit('refresh')
  } catch { /* cancelled or error */ }
}
</script>

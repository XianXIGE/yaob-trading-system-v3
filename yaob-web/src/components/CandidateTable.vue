<template>
  <div class="yaob-card">
    <div class="yaob-card-title">
      <span>候选池 ({{ candidates.length }})</span>
      <el-button size="small" plain @click="emit('refresh')" :loading="loading">
        <el-icon><Refresh /></el-icon> 刷新
      </el-button>
    </div>
    <!-- Desktop table -->
    <el-table :data="candidates" style="width: 100%" size="small" class="hide-on-mobile" empty-text="候选池为空">
      <el-table-column prop="symbol" label="交易对" min-width="100" />
      <el-table-column prop="strategy" label="策略" min-width="60" />
      <el-table-column label="方向" min-width="60">
        <template #default="{ row }">
          <el-tag :class="row.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
            {{ row.direction === 'SHORT' ? '空' : '多' }}
          </el-tag>
        </template>
      </el-table-column>
      <el-table-column label="当前价" min-width="90">
        <template #default="{ row }">
          {{ formatPrice(row.current_price) }}
        </template>
      </el-table-column>
      <el-table-column label="优先级" min-width="80">
        <template #default="{ row }">
          <span :class="Number(row.priority) >= 0.4 ? 'profit-color' : ''" style="font-weight: 600;">
            {{ Number(row.priority * 100).toFixed(1) }}%
          </span>
        </template>
      </el-table-column>
      <el-table-column label="状态" min-width="80">
        <template #default="{ row }">
          <el-tag size="small" effect="plain" type="info">{{ row.unopen_reason || '等待开仓' }}</el-tag>
        </template>
      </el-table-column>
      <el-table-column prop="reason" label="触发原因" min-width="160" show-overflow-tooltip />
    </el-table>
    <!-- Mobile cards -->
    <div class="mobile-cards hide-on-desktop">
      <div v-for="c in candidates" :key="c.symbol + c.strategy" class="mobile-card-item">
        <div class="card-row">
          <span class="label" style="font-weight: 600;">{{ c.symbol }}</span>
          <el-tag :class="c.direction === 'SHORT' ? 'tag-short' : 'tag-long'" size="small" effect="plain">
            {{ c.direction === 'SHORT' ? '空' : '多' }}
          </el-tag>
        </div>
        <div class="card-row"><span class="label">策略</span><span>{{ c.strategy }}</span></div>
        <div class="card-row"><span class="label">当前价</span><span>{{ formatPrice(c.current_price) }}</span></div>
        <div class="card-row"><span class="label">优先级</span><span>{{ Number(c.priority * 100).toFixed(1) }}%</span></div>
        <div class="card-row"><span class="label">状态</span><span style="font-size:12px;">{{ c.unopen_reason || '等待开仓' }}</span></div>
        <div class="card-row"><span class="label">原因</span><span style="text-align: right; max-width: 180px; overflow: hidden; text-overflow: ellipsis;">{{ c.reason }}</span></div>
      </div>
      <el-empty v-if="!candidates.length" description="候选池为空" />
    </div>
  </div>
</template>

<script setup lang="ts">
interface Candidate {
  symbol: string
  strategy: string
  direction: string
  reason: string
  current_price: number
  priority: number
  unopen_reason: string
}

defineProps<{ candidates: Candidate[]; loading?: boolean }>()
const emit = defineEmits<{ (e: 'refresh'): void }>()

function formatPrice(v: any): string {
  if (v == null) return '-'
  const n = Number(v)
  if (n >= 1000) return n.toFixed(2)
  if (n >= 1) return n.toFixed(4)
  if (n >= 0.01) return n.toFixed(6)
  return n.toPrecision(4)
}
</script>

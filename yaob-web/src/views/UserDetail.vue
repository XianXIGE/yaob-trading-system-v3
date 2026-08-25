<template>
  <div class="user-detail-page">
    <div class="page-header">
      <el-button size="small" @click="$router.push('/admin')">← 返回用户列表</el-button>
      <h3 style="margin: 0;">用户详情：{{ username || ('#' + userId) }}</h3>
      <el-tag v-if="overview?.is_admin" type="danger" size="small">管理员</el-tag>
      <el-tag v-else type="info" size="small">普通用户</el-tag>
      <el-tag v-if="overview?.is_vip" type="warning" size="small">VIP</el-tag>
      <el-tag v-else type="info" size="small">普通</el-tag>
    </div>

    <el-tabs v-model="activeTab">
      <!-- 账户概览 -->
      <el-tab-pane label="账户概览" name="overview">
        <div class="yaob-card">
          <el-descriptions :column="2" border size="small">
            <el-descriptions-item label="用户名">{{ overview?.username }}</el-descriptions-item>
            <el-descriptions-item label="注册时间">{{ overview?.created_at }}</el-descriptions-item>
            <el-descriptions-item label="保证金模式">{{ overview?.margin_mode }}</el-descriptions-item>
            <el-descriptions-item label="持仓模式">{{ overview?.position_mode }}</el-descriptions-item>
            <el-descriptions-item label="开仓保证金">{{ overview?.open_margin }} U</el-descriptions-item>
            <el-descriptions-item label="杠杆">{{ overview?.leverage }}x</el-descriptions-item>
            <el-descriptions-item label="自动交易">
              <el-tag :type="overview?.auto_trade_enabled ? 'success' : 'info'" size="small">
                {{ overview?.auto_trade_enabled ? '开启' : '关闭' }}
              </el-tag>
            </el-descriptions-item>
            <el-descriptions-item label="过滤大盘币">{{ overview?.exclude_large_cap ? '是' : '否' }}</el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="yaob-card" style="margin-top: 16px;">
          <div class="yaob-card-title">资产</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="钱包余额">{{ asset?.wallet_balance ?? '—' }} U</el-descriptions-item>
            <el-descriptions-item label="可用余额">{{ asset?.available_balance ?? '—' }} U</el-descriptions-item>
            <el-descriptions-item label="未实现盈亏">{{ asset?.unrealized_profit ?? '—' }} U</el-descriptions-item>
            <el-descriptions-item label="总权益">{{ asset?.total_equity ?? '—' }} U</el-descriptions-item>
            <el-descriptions-item label="累计已实现盈亏">{{ overview?.realized_pnl ?? '—' }} U</el-descriptions-item>
            <el-descriptions-item label="数据来源">
              <el-tag :type="asset?.source === 'binance_realtime' ? 'success' : 'warning'" size="small">
                {{ asset?.source === 'binance_realtime' ? '币安实时' : '数据库估算' }}
              </el-tag>
            </el-descriptions-item>
          </el-descriptions>
        </div>

        <div class="yaob-card" style="margin-top: 16px;">
          <div class="yaob-card-title">汇总</div>
          <el-descriptions :column="3" border size="small">
            <el-descriptions-item label="当前持仓数">{{ overview?.open_position_count ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="历史交易数">{{ overview?.total_trade_count ?? 0 }}</el-descriptions-item>
            <el-descriptions-item label="VIP到期">{{ overview?.vip_expiry ?? '永久' }}</el-descriptions-item>
          </el-descriptions>
        </div>
      </el-tab-pane>

      <!-- 当前持仓 -->
      <el-tab-pane :label="`当前持仓 (${positions.length})`" name="positions">
        <div class="yaob-card">
          <el-table :data="positions" size="small" empty-text="无持仓" style="width: 100%">
            <el-table-column prop="symbol" label="币种" />
            <el-table-column prop="strategy" label="策略" width="60" />
            <el-table-column label="方向" width="80">
              <template #default="{ row }">
                <el-tag :type="row.direction === 'LONG' ? 'danger' : 'success'" size="small">{{ row.direction }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" />
            <el-table-column prop="entry_price" label="开仓价" />
            <el-table-column prop="leverage" label="杠杆" width="60" />
            <el-table-column prop="tp_ratio" label="TP%" width="70" />
            <el-table-column prop="sl_ratio" label="SL%" width="70" />
            <el-table-column prop="status" label="状态" width="70" />
            <el-table-column prop="opened_at" label="开仓时间" width="170" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 历史交易 -->
      <el-tab-pane :label="`历史交易 (${trades.length})`" name="trades">
        <div class="yaob-card">
          <el-table :data="trades" size="small" empty-text="无交易记录" style="width: 100%">
            <el-table-column prop="symbol" label="币种" />
            <el-table-column prop="strategy" label="策略" width="60" />
            <el-table-column label="方向" width="80">
              <template #default="{ row }">
                <el-tag :type="row.direction === 'LONG' ? 'danger' : 'success'" size="small">{{ row.direction }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="qty" label="数量" />
            <el-table-column prop="entry_price" label="开仓价" />
            <el-table-column prop="exit_price" label="平仓价" />
            <el-table-column label="盈亏" width="90">
              <template #default="{ row }">
                <span :style="{ color: (row.pnl ?? 0) >= 0 ? '#f56c6c' : '#67c23a' }">{{ row.pnl }}</span>
              </template>
            </el-table-column>
            <el-table-column prop="close_reason" label="平仓原因" width="90" />
            <el-table-column prop="closed_at" label="平仓时间" width="170" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 策略配置 -->
      <el-tab-pane :label="`策略配置 (${strategies.length})`" name="strategies">
        <div class="yaob-card">
          <el-table :data="strategies" size="small" empty-text="无策略配置" style="width: 100%">
            <el-table-column prop="strategy" label="策略" width="60" />
            <el-table-column label="类型" width="90">
              <template #default="{ row }">{{ stratType(row.strategy_type) }}</template>
            </el-table-column>
            <el-table-column label="开关" width="70">
              <template #default="{ row }">
                <el-tag :type="row.enabled ? 'success' : 'info'" size="small">{{ row.enabled ? '开' : '关' }}</el-tag>
              </template>
            </el-table-column>
            <el-table-column prop="tp_ratio" label="TP%" width="70" />
            <el-table-column prop="sl_ratio" label="SL%" width="70" />
            <el-table-column prop="description" label="描述" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 黑名单/风控 -->
      <el-tab-pane :label="`黑名单 (${excluded.count ?? 0})`" name="excluded">
        <div class="yaob-card">
          <el-table :data="excluded.excluded_symbols || []" size="small" empty-text="无黑名单" style="width: 100%">
            <el-table-column prop="symbol" label="币种" />
            <el-table-column prop="category" label="分类" width="120" />
            <el-table-column prop="created_at" label="加入时间" width="180" />
          </el-table>
        </div>
      </el-tab-pane>

      <!-- 操作日志 -->
      <el-tab-pane :label="`操作日志 (${logs.length})`" name="logs">
        <div class="yaob-card">
          <el-table :data="logs" size="small" empty-text="暂无操作日志" style="width: 100%">
            <el-table-column prop="created_at" label="时间" width="170" />
            <el-table-column prop="operator" label="操作者" width="100" />
            <el-table-column prop="action" label="操作" width="120" />
            <el-table-column prop="target_username" label="目标用户" width="110" />
            <el-table-column prop="detail" label="详情" />
            <el-table-column prop="ip" label="IP" width="120" />
          </el-table>
        </div>
      </el-tab-pane>
    </el-tabs>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { useRoute } from 'vue-router'
import {
  getUserOverview, getUserPositions, getUserTrades, getUserStrategies, getUserExcluded, getAdminLogs,
} from '@/api/admin'

const route = useRoute()
const userId = Number(route.params.userId)
const activeTab = ref('overview')

const overview = ref<any>(null)
const positions = ref<any[]>([])
const trades = ref<any[]>([])
const strategies = ref<any[]>([])
const excluded = ref<any>({})
const logs = ref<any[]>([])

const username = computed(() => overview.value?.username || '')
const asset = computed(() => overview.value?.asset || {})

function stratType(t?: string) {
  const map: Record<string, string> = {
    short: '做空', long: '做多', fibonacci: '斐波那契', multi: '多空三重过滤',
  }
  return (t && map[t]) || t || '—'
}

async function load() {
  try {
    const [ov, pos, trd, stg, exc, lg] = await Promise.all([
      getUserOverview(userId),
      getUserPositions(userId),
      getUserTrades(userId),
      getUserStrategies(userId),
      getUserExcluded(userId),
      getAdminLogs(userId),
    ])
    const unwrap = (r: any) => (r && r.data != null ? r.data : r)
    overview.value = unwrap(ov)
    positions.value = unwrap(pos) || []
    trades.value = unwrap(trd) || []
    const stgD = unwrap(stg) || {}
    strategies.value = stgD.strategies || []
    excluded.value = unwrap(exc) || {}
    logs.value = unwrap(lg) || []
  } catch { /* 已由拦截器提示 */ }
}

onMounted(load)
</script>

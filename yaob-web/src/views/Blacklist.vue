<template>
  <div class="blacklist-page">
    <div class="page-header">
      <h3>黑名单管理</h3>
    </div>

    <div class="yaob-card">
      <div class="yaob-card-title">
        <span>添加黑名单</span>
      </div>
      <div class="add-row">
        <el-select v-model="addCategory" size="small" style="width: 120px;">
          <el-option label="币种市值" value="manual" />
          <el-option label="股指市值" value="large_cap" />
        </el-select>
        <el-input v-model="newSymbol" placeholder="输入交易对，多个用逗号分隔" size="small" @keyup.enter="handleAdd" />
        <el-button size="small" type="primary" @click="handleAdd" :loading="adding">添加</el-button>
        <el-button size="small" type="danger" plain @click="handleClearAll">清空</el-button>
        <el-button size="small" @click="handleRestoreDefault">恢复默认币种市值</el-button>
      </div>
    </div>

    <div class="yaob-card" v-for="cat in categories" :key="cat.key">
      <div class="yaob-card-title">
        <span>{{ cat.label }} ({{ cat.items.length }})</span>
        <el-button size="small" type="danger" plain @click="handleRemoveCategory(cat.key)">批量删除</el-button>
      </div>
      <div class="symbol-tags">
        <el-tag
          v-for="sym in cat.items"
          :key="sym"
          closable
          @close="handleRemove([sym], cat.key)"
          effect="plain"
          style="margin: 4px;"
        >
          {{ sym }}
        </el-tag>
        <el-empty v-if="!cat.items.length" description="无" :image-size="40" />
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import {
  getExcludedSymbolsCategorized,
  addExcludedSymbols,
  removeExcludedSymbols,
  clearExcludedSymbols,
  restoreDefaultExcluded,
} from '@/api/blacklist'

const data = ref<{ manual: string[]; large_cap: string[] }>({ manual: [], large_cap: [] })
const newSymbol = ref('')
const adding = ref(false)
const addCategory = ref('manual')

const categories = computed(() => [
  { key: 'manual' as const, label: '币种市值', items: data.value.manual || [] },
  { key: 'large_cap' as const, label: '股指市值', items: data.value.large_cap || [] },
])

async function fetchData() {
  try {
    const res = await getExcludedSymbolsCategorized()
    const d = (res as any)?.data || res
    data.value = d || { manual: [], large_cap: [] }
  } catch { /* handled */ }
}

async function handleAdd() {
  if (!newSymbol.value.trim()) return
  adding.value = true
  try {
    const symbols = newSymbol.value.split(',').map((s) => s.trim().toUpperCase()).filter(Boolean)
    await addExcludedSymbols(symbols, addCategory.value)
    ElMessage.success(`已添加 ${symbols.length} 个交易对到${addCategory.value === 'manual' ? '币种市值' : '股指市值'}`)
    newSymbol.value = ''
    await fetchData()
  } catch { /* handled */ } finally {
    adding.value = false
  }
}

async function handleRemove(symbols: string[], cat?: string) {
  try {
    await removeExcludedSymbols(symbols, cat)
    ElMessage.success('已删除')
    await fetchData()
  } catch { /* handled */ }
}

async function handleRemoveCategory(cat: string) {
  const items = data.value[cat as keyof typeof data.value] || []
  if (!items.length) return
  try {
    await ElMessageBox.confirm(`确定要删除 ${cat === 'manual' ? '币种市值' : '股指市值'} 中的 ${items.length} 个交易对吗？`, '批量删除', { type: 'warning' })
    await removeExcludedSymbols(items, cat)
    ElMessage.success('已批量删除')
    await fetchData()
  } catch { /* cancelled or error */ }
}

async function handleClearAll() {
  try {
    await ElMessageBox.confirm('确定要清空全部黑名单吗？此操作不可恢复。', '清空黑名单', { type: 'error', confirmButtonText: '确认清空', cancelButtonText: '取消' })
    await clearExcludedSymbols()
    ElMessage.success('黑名单已清空')
    await fetchData()
  } catch { /* cancelled or error */ }
}

async function handleRestoreDefault() {
  try {
    await ElMessageBox.confirm('确定要恢复默认币种市值吗？将自动拉取币安市值超20亿的币添加到币种市值。', '恢复默认币种市值', { type: 'info' })
    await restoreDefaultExcluded()
    ElMessage.success('已恢复默认币种市值（自动拉取市值>20亿的币）')
    await fetchData()
  } catch { /* cancelled or error */ }
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

.add-row {
  display: flex;
  gap: 8px;
  align-items: center;
}

.add-row .el-input {
  flex: 1;
}

.symbol-tags {
  display: flex;
  flex-wrap: wrap;
  gap: 4px;
}

@media (max-width: 768px) {
  .add-row {
    flex-direction: column;
    align-items: stretch;
  }
}
</style>

<template>
  <div class="yaob-card strategy-card">
    <div class="yaob-card-title">
      <div class="strategy-header">
        <span class="strategy-name">{{ strategyName }}</span>
        <el-tag :class="typeClass" size="small" effect="plain">{{ typeLabel }}</el-tag>
      </div>
      <el-switch v-model="enabled" @change="handleToggle" :loading="toggling" />
    </div>
    <p class="strategy-desc">{{ description }}</p>
    <el-table :data="paramList" size="small" style="width: 100%">
      <el-table-column prop="key" label="参数" width="150" />
      <el-table-column label="值">
        <template #default="{ row }">
          <el-input-number v-model="params[row.key]" size="small" :step="0.01" :min="0" controls-position="right" style="width: 120px;" />
        </template>
      </el-table-column>
    </el-table>
    <div style="text-align: right; margin-top: 12px;">
      <el-button type="primary" size="small" @click="handleSave" :loading="saving">保存</el-button>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, computed, watch } from 'vue'
import { ElMessage } from 'element-plus'
import { saveStrategyParams, toggleStrategy } from '@/api/strategy'

const props = defineProps<{
  strategyKey: string
  strategyName: string
  type: string
  description: string
  enabled: boolean
  params: Record<string, number>
}>()

const enabled = ref(props.enabled)
const params = ref({ ...props.params })
const toggling = ref(false)
const saving = ref(false)

watch(() => props.enabled, (v) => { enabled.value = v })
watch(() => props.params, (v) => { params.value = { ...v } }, { deep: true })

const typeLabel = computed(() => {
  if (props.type === 'short') return '做空'
  if (props.type === 'long') return '做多'
  return '斐波那契'
})

const typeClass = computed(() => {
  if (props.type === 'short') return 'tag-short'
  if (props.type === 'long') return 'tag-long'
  return ''
})

const paramList = computed(() =>
  Object.keys(params.value).map((key) => ({ key, value: params.value[key] }))
)

async function handleToggle(val: boolean) {
  toggling.value = true
  try {
    await toggleStrategy(props.strategyKey)
    ElMessage.success(`${props.strategyName} 已${val ? '开启' : '关闭'}`)
  } catch {
    enabled.value = !val
  } finally {
    toggling.value = false
  }
}

async function handleSave() {
  saving.value = true
  try {
    await saveStrategyParams({ [props.strategyKey]: params.value })
    ElMessage.success(`${props.strategyName} 参数已保存`)
  } catch {
    /* handled */
  } finally {
    saving.value = false
  }
}
</script>

<style scoped>
.strategy-header {
  display: flex;
  align-items: center;
  gap: 8px;
}

.strategy-name {
  font-weight: 600;
}

.strategy-desc {
  font-size: 12px;
  color: var(--text-secondary);
  margin-bottom: 12px;
  line-height: 1.5;
}
</style>

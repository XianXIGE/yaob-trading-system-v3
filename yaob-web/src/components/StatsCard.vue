<template>
  <div class="yaob-card">
    <div class="yaob-card-title">{{ title }}</div>
    <div class="stats-grid">
      <div class="stat-item" v-for="item in items" :key="item.label">
        <div class="stat-label">{{ item.label }}</div>
        <div class="stat-value" :class="item.color">
          {{ item.value }}
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
interface StatItem {
  label: string
  value: string | number
  color?: string
}

defineProps<{ title: string; items: StatItem[] }>()
</script>

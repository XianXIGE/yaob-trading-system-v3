import { defineStore } from 'pinia'
import { ref } from 'vue'
import { getDashboard } from '@/api/dashboard'

export const useDashboardStore = defineStore('dashboard', () => {
  const data = ref<any>(null)
  const loading = ref(false)
  const lastUpdate = ref<number>(0)

  async function fetch() {
    loading.value = true
    try {
      data.value = await getDashboard()
      lastUpdate.value = Date.now()
    } finally {
      loading.value = false
    }
  }

  return { data, loading, lastUpdate, fetch }
})

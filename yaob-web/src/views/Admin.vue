<template>
  <div class="admin-page">
    <div class="page-header">
      <h3>管理后台</h3>
    </div>

    <div class="yaob-card">
      <div class="yaob-card-title">用户管理</div>
      <el-table :data="users" style="width: 100%" size="small" class="hide-on-mobile" empty-text="暂无用户">
        <el-table-column prop="username" label="用户名" width="150" />
        <el-table-column label="VIP状态" width="100">
          <template #default="{ row }">
            <el-tag v-if="row.is_vip" type="warning" size="small" effect="dark">VIP</el-tag>
            <el-tag v-else type="info" size="small">普通</el-tag>
          </template>
        </el-table-column>
        <el-table-column prop="vip_expiry" label="VIP到期" width="180" />
        <el-table-column prop="created_at" label="注册时间" width="180" />
        <el-table-column label="操作" width="240" fixed="right">
          <template #default="{ row }">
            <el-button size="small" type="primary" plain @click="openVipDialog(row)">授权VIP</el-button>
            <el-button size="small" type="warning" plain @click="handleRevokeVip(row)" :disabled="!row.is_vip">撤销VIP</el-button>
            <el-button size="small" type="danger" plain @click="handleDelete(row)">删除</el-button>
          </template>
        </el-table-column>
      </el-table>

      <!-- Mobile cards -->
      <div class="mobile-cards hide-on-desktop">
        <div v-for="u in users" :key="u.username" class="mobile-card-item">
          <div class="card-row">
            <span class="label">{{ u.username }}</span>
            <el-tag v-if="u.is_vip" type="warning" size="small" effect="dark">VIP</el-tag>
            <el-tag v-else type="info" size="small">普通</el-tag>
          </div>
          <div class="card-row" v-if="u.vip_expiry"><span class="label">VIP到期</span><span style="font-size:12px">{{ u.vip_expiry }}</span></div>
          <div class="card-row"><span class="label">注册</span><span style="font-size:12px">{{ u.created_at }}</span></div>
          <div style="display:flex; gap:6px; margin-top:8px; flex-wrap:wrap;">
            <el-button size="small" type="primary" plain @click="openVipDialog(u)">授权VIP</el-button>
            <el-button size="small" type="warning" plain @click="handleRevokeVip(u)" :disabled="!u.is_vip">撤销VIP</el-button>
            <el-button size="small" type="danger" plain @click="handleDelete(u)">删除</el-button>
          </div>
        </div>
        <el-empty v-if="!users.length" description="暂无用户" />
      </div>
    </div>

    <!-- VIP Dialog -->
    <el-dialog v-model="vipDialogVisible" title="授权VIP" width="350px">
      <p style="margin-bottom: 12px;">为用户 <b>{{ editingUser?.username }}</b> 授权VIP</p>
      <el-form label-width="80px">
        <el-form-item label="天数">
          <el-input-number v-model="vipDays" :min="0" :max="3650" size="default" style="width: 100%;" />
          <p style="font-size:12px; color: var(--text-secondary); margin-top:4px;">0 = 永久</p>
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="vipDialogVisible = false">取消</el-button>
        <el-button type="primary" :loading="vipLoading" @click="handleGrantVip">确认授权</el-button>
      </template>
    </el-dialog>
  </div>
</template>

<script setup lang="ts">
import { ref, onMounted } from 'vue'
import { ElMessage, ElMessageBox } from 'element-plus'
import { getUsers, setVip, deleteUser } from '@/api/admin'

interface UserItem {
  username: string
  is_vip: boolean
  vip_expiry: string | null
  created_at: string
}

const users = ref<UserItem[]>([])
const vipDialogVisible = ref(false)
const editingUser = ref<UserItem | null>(null)
const vipDays = ref(30)
const vipLoading = ref(false)

async function fetchUsers() {
  try {
    const res = await getUsers()
    users.value = res.users || res || []
  } catch { /* handled */ }
}

function openVipDialog(user: UserItem) {
  editingUser.value = user
  vipDays.value = 30
  vipDialogVisible.value = true
}

async function handleGrantVip() {
  if (!editingUser.value) return
  vipLoading.value = true
  try {
    await setVip(editingUser.value.username, true, vipDays.value)
    ElMessage.success(`已为 ${editingUser.value.username} 授权VIP ${vipDays.value === 0 ? '永久' : vipDays.value + '天'}`)
    vipDialogVisible.value = false
    await fetchUsers()
  } catch { /* handled */ } finally {
    vipLoading.value = false
  }
}

async function handleRevokeVip(user: UserItem) {
  try {
    await ElMessageBox.confirm(`确定要撤销 ${user.username} 的VIP吗？`, '撤销VIP', { type: 'warning' })
    await setVip(user.username, false, 0)
    ElMessage.success(`已撤销 ${user.username} 的VIP`)
    await fetchUsers()
  } catch { /* cancelled or error */ }
}

async function handleDelete(user: UserItem) {
  try {
    await ElMessageBox.confirm(`确定要删除用户 ${user.username} 吗？此操作不可恢复。`, '删除用户', { type: 'error', confirmButtonText: '确认删除', cancelButtonText: '取消' })
    await deleteUser(user.username)
    ElMessage.success(`已删除用户 ${user.username}`)
    await fetchUsers()
  } catch { /* cancelled or error */ }
}

onMounted(fetchUsers)
</script>

<style scoped>
.page-header {
  margin-bottom: 16px;
}
.page-header h3 {
  font-size: 18px;
}
</style>

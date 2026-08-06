<template>
  <el-container class="main-layout">
    <!-- Top Nav -->
    <el-header class="top-header">
      <div class="header-left">
        <el-icon class="hamburger hide-on-desktop" @click="sidebarOpen = !sidebarOpen" :size="22">
          <Fold v-if="sidebarOpen" />
          <Expand v-else />
        </el-icon>
        <div class="logo-wrap">
          <span class="logo-icon">🪙</span>
          <span class="logo-text">妖币交易系统</span>
          <span class="logo-version">V3.0</span>
        </div>
      </div>
      <div class="header-right">
        <span class="user-name hide-on-mobile">{{ auth.user?.username }}</span>
        <span v-if="auth.isVip" class="vip-badge">VIP</span>
        <el-button size="small" plain @click="showPwdDialog = true">改密码</el-button>
        <el-button type="danger" size="small" plain @click="handleLogout">退出</el-button>
      </div>
    </el-header>

    <el-container class="body-container">
      <!-- Sidebar (desktop) -->
      <el-aside class="sidebar hide-on-mobile" width="180px">
        <el-menu :default-active="activeMenu" router>
          <el-menu-item index="/dashboard">
            <el-icon><DataBoard /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/strategy">
            <el-icon><Setting /></el-icon>
            <span>策略配置</span>
          </el-menu-item>
          <el-menu-item index="/history">
            <el-icon><List /></el-icon>
            <span>交易流水</span>
          </el-menu-item>
          <el-menu-item index="/blacklist">
            <el-icon><Warning /></el-icon>
            <span>黑名单</span>
          </el-menu-item>
          <el-menu-item v-if="auth.isAdmin" index="/admin">
            <el-icon><UserFilled /></el-icon>
            <span>管理后台</span>
          </el-menu-item>
        </el-menu>
      </el-aside>

      <!-- Mobile sidebar drawer -->
      <el-drawer v-model="sidebarOpen" direction="ltr" size="200px" :show-close="false" :with-header="false">
        <el-menu :default-active="activeMenu" router @select="sidebarOpen = false">
          <el-menu-item index="/dashboard">
            <el-icon><DataBoard /></el-icon>
            <span>仪表盘</span>
          </el-menu-item>
          <el-menu-item index="/strategy">
            <el-icon><Setting /></el-icon>
            <span>策略配置</span>
          </el-menu-item>
          <el-menu-item index="/history">
            <el-icon><List /></el-icon>
            <span>交易流水</span>
          </el-menu-item>
          <el-menu-item index="/blacklist">
            <el-icon><Warning /></el-icon>
            <span>黑名单</span>
          </el-menu-item>
          <el-menu-item v-if="auth.isAdmin" index="/admin">
            <el-icon><UserFilled /></el-icon>
            <span>管理后台</span>
          </el-menu-item>
        </el-menu>
      </el-drawer>

      <!-- Main content -->
      <el-main class="main-content">
        <router-view />
      </el-main>
    </el-container>

    <!-- 修改密码弹窗 -->
    <el-dialog v-model="showPwdDialog" title="修改密码" width="360px" :close-on-click-modal="false">
      <el-form ref="pwdFormRef" :model="pwdForm" :rules="pwdRules" label-width="70px">
        <el-form-item label="旧密码" prop="oldPassword">
          <el-input v-model="pwdForm.oldPassword" type="password" show-password placeholder="请输入旧密码" />
        </el-form-item>
        <el-form-item label="新密码" prop="newPassword">
          <el-input v-model="pwdForm.newPassword" type="password" show-password placeholder="至少6位" />
        </el-form-item>
        <el-form-item label="确认" prop="confirmPassword">
          <el-input v-model="pwdForm.confirmPassword" type="password" show-password placeholder="再次输入新密码" />
        </el-form-item>
      </el-form>
      <template #footer>
        <el-button @click="showPwdDialog = false">取消</el-button>
        <el-button type="primary" :loading="pwdLoading" @click="handleChangePassword">确认修改</el-button>
      </template>
    </el-dialog>
  </el-container>
</template>

<script setup lang="ts">
import { ref, computed, reactive } from 'vue'
import { useRoute, useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { changePassword } from '@/api/auth'
import { ElMessageBox, ElMessage, type FormInstance, type FormRules } from 'element-plus'

const auth = useAuthStore()
const route = useRoute()
const router = useRouter()
const sidebarOpen = ref(false)
const activeMenu = computed(() => route.path)

// 修改密码
const showPwdDialog = ref(false)
const pwdLoading = ref(false)
const pwdFormRef = ref<FormInstance>()
const pwdForm = reactive({ oldPassword: '', newPassword: '', confirmPassword: '' })
const pwdRules: FormRules = {
  oldPassword: [{ required: true, message: '请输入旧密码', trigger: 'blur' }],
  newPassword: [{ required: true, message: '请输入新密码', trigger: 'blur' }, { min: 6, message: '至少6位', trigger: 'blur' }],
  confirmPassword: [
    { required: true, message: '请确认密码', trigger: 'blur' },
    { validator: (_r: any, v: string, cb: any) => { v !== pwdForm.newPassword ? cb(new Error('两次密码不一致')) : cb() }, trigger: 'blur' },
  ],
}

async function handleChangePassword() {
  const valid = await pwdFormRef.value?.validate().catch(() => false)
  if (!valid) return
  pwdLoading.value = true
  try {
    await changePassword(pwdForm.oldPassword, pwdForm.newPassword)
    ElMessage.success('密码修改成功')
    showPwdDialog.value = false
    pwdForm.oldPassword = ''
    pwdForm.newPassword = ''
    pwdForm.confirmPassword = ''
  } catch { /* handled */ } finally {
    pwdLoading.value = false
  }
}

async function handleLogout() {
  try {
    await ElMessageBox.confirm('确定要退出登录吗？', '提示', { type: 'warning' })
    await auth.logout()
    router.push('/login')
  } catch { /* cancelled */ }
}
</script>

<style scoped>
.main-layout {
  height: 100vh;
}

.top-header {
  display: flex;
  align-items: center;
  justify-content: space-between;
  padding: 0 20px;
  height: 52px;
  line-height: 52px;
  background: linear-gradient(135deg, #1e293b 0%, #0f172a 100%);
  border-bottom: 1px solid rgba(59, 130, 246, 0.15);
  box-shadow: 0 2px 12px rgba(0, 0, 0, 0.15);
}

.header-left {
  display: flex;
  align-items: center;
  gap: 10px;
}

.logo-wrap {
  display: flex;
  align-items: center;
  gap: 6px;
}

.logo-icon {
  font-size: 22px;
}

.logo-text {
  font-size: 17px;
  font-weight: 700;
  color: #f1f5f9;
  letter-spacing: 1px;
}

.logo-version {
  font-size: 11px;
  color: #3b82f6;
  background: rgba(59, 130, 246, 0.1);
  padding: 1px 6px;
  border-radius: 4px;
  font-weight: 600;
}

.header-right {
  display: flex;
  align-items: center;
  gap: 8px;
}

.user-name {
  color: #cbd5e1;
  font-size: 13px;
  font-weight: 500;
}

.hamburger {
  cursor: pointer;
  color: #f1f5f9;
}

.body-container {
  height: calc(100vh - 52px);
}

.sidebar {
  background: #fff;
  border-right: 1px solid #e4e7ed;
  overflow-y: auto;
}

.sidebar .el-menu {
  border-right: none;
}

.main-content {
  background: #f0f2f5;
  overflow-y: auto;
  padding: 16px;
}

@media (max-width: 768px) {
  .main-content {
    padding: 10px;
  }
  .logo-text {
    font-size: 15px;
  }
  .logo-version {
    display: none;
  }
}
</style>

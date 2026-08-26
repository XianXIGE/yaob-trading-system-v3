<template>
  <div class="login-page" :class="theme">
    <!-- 背景动画 -->
    <div class="bg-decoration">
      <div class="bg-circle bg-circle-1"></div>
      <div class="bg-circle bg-circle-2"></div>
      <div class="bg-circle bg-circle-3"></div>
    </div>

    <!-- 主题切换 -->
    <button class="theme-toggle" @click="toggleTheme">
      <span v-if="theme === 'dark'">☀️</span>
      <span v-else>🌙</span>
    </button>

    <!-- 登录卡片 -->
    <div class="login-card">
      <div class="logo-area">
        <div class="logo-icon">🪙</div>
        <h2 class="login-title">妖币交易系统</h2>
        <p class="login-version">V3.7.0 · 智能合约交易</p>
      </div>

      <el-form ref="formRef" :model="form" :rules="rules" @submit.prevent="handleLogin" class="login-form">
        <el-form-item prop="username">
          <el-input v-model="form.username" placeholder="请输入用户名" :prefix-icon="User" size="large" />
        </el-form-item>
        <el-form-item prop="password">
          <el-input v-model="form.password" type="password" placeholder="请输入密码" :prefix-icon="Lock" size="large" show-password @keyup.enter="handleLogin" />
        </el-form-item>
        <el-button type="primary" size="large" :loading="loading" class="login-btn" @click="handleLogin">
          登 录
        </el-button>
      </el-form>

      <div class="login-divider">
        <span>或</span>
      </div>

      <p class="login-footer">
        还没有账号？<router-link to="/register">立即注册 →</router-link>
      </p>

      <div class="login-features">
        <div class="feature-item">
          <span class="feature-icon">🎯</span>
          <span>六策略智能扫描</span>
        </div>
        <div class="feature-item">
          <span class="feature-icon">🔁</span>
          <span>自动止盈止损</span>
        </div>
        <div class="feature-item">
          <span class="feature-icon">📊</span>
          <span>候选池实时监控</span>
        </div>
      </div>
    </div>
  </div>
</template>

<script setup lang="ts">
import { ref, reactive } from 'vue'
import { useRouter } from 'vue-router'
import { useAuthStore } from '@/stores/auth'
import { User, Lock } from '@element-plus/icons-vue'
import { ElMessage, type FormInstance, type FormRules } from 'element-plus'

const auth = useAuthStore()
const router = useRouter()
const formRef = ref<FormInstance>()
const loading = ref(false)
const theme = ref(localStorage.getItem('yaob-theme') || 'dark')

function toggleTheme() {
  theme.value = theme.value === 'dark' ? 'light' : 'dark'
  localStorage.setItem('yaob-theme', theme.value)
  applyTheme()
}

function applyTheme() {
  const root = document.documentElement
  if (theme.value === 'dark') {
    root.setAttribute('data-theme', 'dark')
  } else {
    root.removeAttribute('data-theme')
  }
}
applyTheme()

const form = reactive({ username: '', password: '' })
const rules: FormRules = {
  username: [{ required: true, message: '请输入用户名', trigger: 'blur' }],
  password: [{ required: true, message: '请输入密码', trigger: 'blur' }],
}

async function handleLogin() {
  const valid = await formRef.value?.validate().catch(() => false)
  if (!valid) return
  loading.value = true
  try {
    await auth.login(form.username, form.password)
    ElMessage.success('登录成功')
    router.push('/dashboard')
  } catch {
    /* error handled by interceptor */
  } finally {
    loading.value = false
  }
}
</script>

<style scoped>
/* ===== 暗色主题（默认） ===== */
.login-page {
  min-height: 100vh;
  display: flex;
  align-items: center;
  justify-content: center;
  padding: 16px;
  position: relative;
  overflow: hidden;
  transition: background 0.3s;
}

.login-page.dark {
  background: linear-gradient(135deg, #0f172a 0%, #1e293b 50%, #0f172a 100%);
}

.login-page.light {
  background: linear-gradient(135deg, #e0f2fe 0%, #f0f9ff 50%, #e0f7fa 100%);
}

/* ===== 背景装饰圆 ===== */
.bg-decoration {
  position: absolute;
  inset: 0;
  overflow: hidden;
  pointer-events: none;
}

.bg-circle {
  position: absolute;
  border-radius: 50%;
  filter: blur(80px);
  opacity: 0.15;
  animation: float 8s ease-in-out infinite;
}

.dark .bg-circle-1 {
  width: 400px; height: 400px;
  background: #3b82f6;
  top: -100px; left: -100px;
}
.dark .bg-circle-2 {
  width: 300px; height: 300px;
  background: #8b5cf6;
  bottom: -80px; right: -80px;
  animation-delay: 2s;
}
.dark .bg-circle-3 {
  width: 250px; height: 250px;
  background: #06b6d4;
  top: 50%; left: 60%;
  animation-delay: 4s;
}

.light .bg-circle-1 {
  width: 400px; height: 400px;
  background: #38bdf8;
  top: -100px; left: -100px;
  opacity: 0.25;
}
.light .bg-circle-2 {
  width: 300px; height: 300px;
  background: #a78bfa;
  bottom: -80px; right: -80px;
  opacity: 0.2;
  animation-delay: 2s;
}
.light .bg-circle-3 {
  width: 250px; height: 250px;
  background: #2dd4bf;
  top: 50%; left: 60%;
  opacity: 0.2;
  animation-delay: 4s;
}

@keyframes float {
  0%, 100% { transform: translate(0, 0) scale(1); }
  33% { transform: translate(30px, -30px) scale(1.05); }
  66% { transform: translate(-20px, 20px) scale(0.95); }
}

/* ===== 主题切换按钮 ===== */
.theme-toggle {
  position: absolute;
  top: 20px;
  right: 20px;
  width: 44px;
  height: 44px;
  border-radius: 50%;
  border: 1px solid rgba(255,255,255,0.1);
  background: rgba(255,255,255,0.05);
  backdrop-filter: blur(10px);
  cursor: pointer;
  font-size: 20px;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: all 0.3s;
  z-index: 10;
}
.theme-toggle:hover {
  transform: scale(1.1);
  background: rgba(255,255,255,0.1);
}

/* ===== 登录卡片 ===== */
.login-card {
  width: 100%;
  max-width: 420px;
  border-radius: 16px;
  padding: 36px 32px;
  position: relative;
  z-index: 1;
  backdrop-filter: blur(20px);
  transition: all 0.3s;
}

.dark .login-card {
  background: rgba(30, 41, 59, 0.8);
  border: 1px solid rgba(255,255,255,0.08);
  box-shadow: 0 20px 60px rgba(0,0,0,0.3);
}

.light .login-card {
  background: rgba(255, 255, 255, 0.85);
  border: 1px solid rgba(0,0,0,0.06);
  box-shadow: 0 20px 60px rgba(0,0,0,0.08);
}

/* ===== Logo 区域 ===== */
.logo-area {
  text-align: center;
  margin-bottom: 28px;
}

.logo-icon {
  font-size: 48px;
  margin-bottom: 8px;
  display: inline-block;
  animation: bounce 2s ease-in-out infinite;
}

@keyframes bounce {
  0%, 100% { transform: translateY(0); }
  50% { transform: translateY(-6px); }
}

.login-title {
  font-size: 24px;
  font-weight: 700;
  margin-bottom: 4px;
}

.dark .login-title { color: #f1f5f9; }
.light .login-title { color: #0f172a; }

.login-version {
  font-size: 13px;
}

.dark .login-version { color: #64748b; }
.light .login-version { color: #94a3b8; }

/* ===== 表单 ===== */
.login-form {
  margin-bottom: 16px;
}

.login-btn {
  width: 100%;
  height: 44px;
  font-size: 16px;
  font-weight: 600;
  letter-spacing: 2px;
  border-radius: 10px;
}

/* ===== 分割线 ===== */
.login-divider {
  text-align: center;
  margin: 20px 0;
  position: relative;
}
.login-divider::before {
  content: '';
  position: absolute;
  left: 0; right: 0; top: 50%;
  height: 1px;
}
.dark .login-divider::before { background: rgba(255,255,255,0.08); }
.light .login-divider::before { background: rgba(0,0,0,0.06); }

.login-divider span {
  position: relative;
  padding: 0 12px;
  font-size: 12px;
}
.dark .login-divider span { color: #64748b; background: rgba(30,41,59,0.8); }
.light .login-divider span { color: #94a3b8; background: rgba(255,255,255,0.85); }

/* ===== 底部链接 ===== */
.login-footer {
  text-align: center;
  font-size: 14px;
  margin-bottom: 24px;
}

.dark .login-footer { color: #64748b; }
.light .login-footer { color: #94a3b8; }

.login-footer a {
  color: #3b82f6;
  text-decoration: none;
  font-weight: 500;
}
.login-footer a:hover { text-decoration: underline; }

/* ===== 特性标签 ===== */
.login-features {
  display: flex;
  justify-content: space-around;
  gap: 8px;
  padding-top: 20px;
  border-top: 1px solid;
}

.dark .login-features { border-color: rgba(255,255,255,0.06); }
.light .login-features { border-color: rgba(0,0,0,0.04); }

.feature-item {
  display: flex;
  flex-direction: column;
  align-items: center;
  gap: 4px;
  font-size: 11px;
}

.dark .feature-item { color: #64748b; }
.light .feature-item { color: #94a3b8; }

.feature-icon {
  font-size: 20px;
}

/* ===== Element Plus 输入框适配 ===== */
.dark :deep(.el-input__wrapper) {
  background: rgba(15, 23, 42, 0.6);
  border: 1px solid rgba(255,255,255,0.08);
  border-radius: 10px;
  box-shadow: none;
}
.dark :deep(.el-input__wrapper:hover) {
  border-color: rgba(59, 130, 246, 0.3);
}
.dark :deep(.el-input__wrapper.is-focus) {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.dark :deep(.el-input__inner) {
  color: #f1f5f9;
}
.dark :deep(.el-input__inner::placeholder) {
  color: #475569;
}

.light :deep(.el-input__wrapper) {
  background: rgba(255, 255, 255, 0.8);
  border: 1px solid rgba(0,0,0,0.08);
  border-radius: 10px;
  box-shadow: none;
}
.light :deep(.el-input__wrapper:hover) {
  border-color: rgba(59, 130, 246, 0.3);
}
.light :deep(.el-input__wrapper.is-focus) {
  border-color: #3b82f6;
  box-shadow: 0 0 0 3px rgba(59, 130, 246, 0.1);
}
.light :deep(.el-input__inner) {
  color: #0f172a;
}

/* ===== 移动端适配 ===== */
@media (max-width: 768px) {
  .login-card {
    padding: 28px 20px;
    border-radius: 12px;
  }
  .logo-icon { font-size: 40px; }
  .login-title { font-size: 20px; }
  .login-features { flex-direction: column; gap: 12px; }
  .feature-item { flex-direction: row; }
}
</style>

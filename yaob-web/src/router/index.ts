import { createRouter, createWebHistory, type RouteRecordRaw } from 'vue-router'
import { useAuthStore } from '@/stores/auth'

const routes: RouteRecordRaw[] = [
  { path: '/login', name: 'Login', component: () => import('@/views/Login.vue'), meta: { public: true } },
  { path: '/register', name: 'Register', component: () => import('@/views/Register.vue'), meta: { public: true } },
  {
    path: '/',
    component: () => import('@/layouts/MainLayout.vue'),
    redirect: '/dashboard',
    children: [
      { path: 'dashboard', name: 'Dashboard', component: () => import('@/views/Dashboard.vue') },
      { path: 'strategy', name: 'Strategy', component: () => import('@/views/Strategy.vue') },
      { path: 'history', name: 'TradeHistory', component: () => import('@/views/TradeHistory.vue') },
      { path: 'blacklist', name: 'Blacklist', component: () => import('@/views/Blacklist.vue') },
      {
        path: 'admin',
        name: 'Admin',
        component: () => import('@/views/Admin.vue'),
        meta: { requiresAdmin: true },
      },
    ],
  },
  { path: '/:pathMatch(.*)*', redirect: '/dashboard' },
]

const router = createRouter({
  history: createWebHistory('/yaob/'),
  routes,
})

router.beforeEach(async (to, _from, next) => {
  const auth = useAuthStore()
  
  // 公开页面（登录/注册）
  if (to.meta.public) {
    if (auth.isLoggedIn && (to.name === 'Login' || to.name === 'Register')) {
      next('/dashboard')
    } else {
      next()
    }
    return
  }

  // 未登录 -> 尝试 fetchMe 确认 session 是否有效
  if (!auth.isLoggedIn) {
    const ok = await auth.fetchMe()
    if (!ok) {
      next('/login')
      return
    }
  }

  // 已登录，检查管理员权限
  if (to.meta.requiresAdmin && !auth.isAdmin) {
    next('/dashboard')
    return
  }

  next()
})

export default router

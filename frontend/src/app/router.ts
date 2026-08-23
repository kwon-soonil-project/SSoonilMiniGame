import type { Pinia } from 'pinia'
import { createRouter, createWebHistory } from 'vue-router'
import HomeView from '../features/auth/HomeView.vue'
import { useAuthStore } from '../features/auth/authStore'
import LobbyView from '../features/lobby/LobbyView.vue'
import RoomView from '../features/room/RoomView.vue'

export function createAppRouter(pinia: Pinia) {
  const router = createRouter({
    history: createWebHistory(),
    routes: [
      { path: '/', name: 'home', component: HomeView },
      { path: '/lobby', name: 'lobby', component: LobbyView, meta: { requiresAuth: true } },
      { path: '/rooms/:code', name: 'room', component: RoomView, props: true, meta: { requiresAuth: true } },
    ],
  })

  router.beforeEach(async (to) => {
    const auth = useAuthStore(pinia)
    if (to.name === 'home' && auth.error && !auth.initialized) return true
    try {
      await auth.initialize()
    } catch {
      return to.name === 'home' ? true : { name: 'home' }
    }
    if (to.meta.requiresAuth && !auth.actor) return { name: 'home' }
    if (to.name === 'home' && auth.actor) return { name: 'lobby' }
    return true
  })
  return router
}

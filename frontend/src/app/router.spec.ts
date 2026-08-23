import { createPinia } from 'pinia'
import { http, HttpResponse } from 'msw'
import { describe, expect, it } from 'vitest'
import { server } from '../test/setup'
import { useAuthStore } from '../features/auth/authStore'
import { createAppRouter } from './router'

describe('application router', () => {
  it('resolves room codes through the authenticated waiting-room route', () => {
    const router = createAppRouter(createPinia())

    const resolved = router.resolve('/rooms/123456')

    expect(resolved.name).toBe('room')
    expect(resolved.params.code).toBe('123456')
    expect(resolved.meta.requiresAuth).toBe(true)
  })

  it('shows home after a transient auth error and enters lobby after explicit recovery', async () => {
    let requests = 0
    server.use(http.get('/api/v1/me', () => {
      requests += 1
      if (requests === 1) return HttpResponse.json(
        { code: 'HTTP_ERROR', message: '인증 서버를 확인해 주세요.', requestId: 'request-500' },
        { status: 500 },
      )
      return HttpResponse.json({ actorId: 'guest-1', actorType: 'GUEST', nickname: '복구감자', memberId: null })
    }))
    const pinia = createPinia()
    const router = createAppRouter(pinia)
    const auth = useAuthStore(pinia)

    await router.push('/lobby')
    expect(router.currentRoute.value.path).toBe('/')
    expect(requests).toBe(1)

    await auth.retryInitialize()
    await router.push('/lobby')
    expect(router.currentRoute.value.path).toBe('/lobby')
  })
})

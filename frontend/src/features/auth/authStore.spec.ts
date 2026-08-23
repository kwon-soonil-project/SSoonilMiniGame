import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/setup'
import { useAuthStore } from './authStore'

describe('authStore', () => {
  it('creates a guest and stores the returned actor', async () => {
    server.use(http.post('/api/v1/auth/guest', async ({ request }) => {
      expect(await request.json()).toEqual({ nickname: '감자왕' })
      return HttpResponse.json(
        { actorId: 'guest-1', actorType: 'GUEST', nickname: '감자왕', memberId: null },
        { status: 201 },
      )
    }))

    const store = useAuthStore()
    await store.joinAsGuest('감자왕')

    expect(store.actor?.nickname).toBe('감자왕')
  })

  it('rejects a nickname outside the 2 to 12 character boundary before calling the server', async () => {
    let called = false
    server.use(http.post('/api/v1/auth/guest', () => {
      called = true
      return HttpResponse.json({}, { status: 201 })
    }))

    const store = useAuthStore()
    await expect(store.joinAsGuest('감')).rejects.toThrow('2~12자')
    expect(called).toBe(false)
  })

  it('treats an unauthorized current-user response as an anonymous session', async () => {
    server.use(http.get('/api/v1/me', () => HttpResponse.json(
      { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', requestId: 'request-401' },
      { status: 401 },
    )))

    const store = useAuthStore()
    await store.initialize()

    expect(store.initialized).toBe(true)
    expect(store.actor).toBeNull()
    expect(store.error).toBeNull()
  })

  it('can explicitly recover after a transient current-user failure', async () => {
    let requests = 0
    server.use(http.get('/api/v1/me', () => {
      requests += 1
      if (requests === 1) return HttpResponse.json(
        { code: 'HTTP_ERROR', message: '일시적인 오류입니다.', requestId: 'request-500' },
        { status: 500 },
      )
      return HttpResponse.json({
        actorId: 'member-1', actorType: 'MEMBER', nickname: '회원감자', memberId: 'member-id-1',
      })
    }))
    const store = useAuthStore()

    await expect(store.initialize()).rejects.toThrow('일시적인 오류')
    expect(store.initialized).toBe(false)
    expect(store.error).toBe('일시적인 오류입니다.')

    await store.retryInitialize()
    expect(store.initialized).toBe(true)
    expect(store.actor?.nickname).toBe('회원감자')
    expect(requests).toBe(2)
  })
})

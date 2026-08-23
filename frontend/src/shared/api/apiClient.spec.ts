import { beforeEach, describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/setup'
import { ApiError } from './ApiError'
import { apiRequest, invalidateCsrfToken } from './apiClient'

describe('apiRequest', () => {
  beforeEach(() => invalidateCsrfToken())

  it('adds the CSRF token and a canonical request id to unsafe requests', async () => {
    let csrfRequests = 0
    server.use(
      http.get('/api/v1/csrf', () => {
        csrfRequests += 1
        return HttpResponse.json({
          headerName: 'X-XSRF-TOKEN',
          parameterName: '_csrf',
          token: 'csrf-token',
        })
      }),
      http.post('/api/v1/rooms', ({ request }) => {
        expect(request.credentials).toBe('include')
        expect(request.headers.get('X-XSRF-TOKEN')).toBe('csrf-token')
        expect(request.headers.get('X-Request-Id')).toMatch(
          /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/,
        )
        return HttpResponse.json({ roomId: 'room-1' }, { status: 201 })
      }),
    )

    const room = await apiRequest<{ roomId: string }>('/api/v1/rooms', {
      method: 'POST',
      body: JSON.stringify({ title: '저녁 게임' }),
    })

    expect(room.roomId).toBe('room-1')
    expect(csrfRequests).toBe(1)
  })

  it('refreshes the CSRF token and retries one time after a forbidden response', async () => {
    let csrfRequests = 0
    let roomRequests = 0
    server.use(
      http.get('/api/v1/csrf', () => {
        csrfRequests += 1
        return HttpResponse.json({
          headerName: 'X-XSRF-TOKEN',
          parameterName: '_csrf',
          token: `csrf-${csrfRequests}`,
        })
      }),
      http.post('/api/v1/rooms', ({ request }) => {
        roomRequests += 1
        if (roomRequests === 1) {
          expect(request.headers.get('X-XSRF-TOKEN')).toBe('csrf-1')
          return HttpResponse.json(
            { code: 'FORBIDDEN', message: '요청 권한이 없습니다.', requestId: 'request-1' },
            { status: 403 },
          )
        }
        expect(request.headers.get('X-XSRF-TOKEN')).toBe('csrf-2')
        return HttpResponse.json({ roomId: 'room-1' }, { status: 201 })
      }),
    )

    await expect(apiRequest('/api/v1/rooms', { method: 'POST' })).resolves.toEqual({ roomId: 'room-1' })
    expect(csrfRequests).toBe(2)
    expect(roomRequests).toBe(2)
  })

  it('converts the security error envelope into ApiError', async () => {
    server.use(http.get('/api/v1/me', () => HttpResponse.json(
      { code: 'UNAUTHORIZED', message: '로그인이 필요합니다.', requestId: 'request-401' },
      { status: 401 },
    )))

    const error = await apiRequest('/api/v1/me').catch((cause: unknown) => cause)

    expect(error).toBeInstanceOf(ApiError)
    expect(error).toMatchObject({ code: 'UNAUTHORIZED', requestId: 'request-401', status: 401 })
  })
})

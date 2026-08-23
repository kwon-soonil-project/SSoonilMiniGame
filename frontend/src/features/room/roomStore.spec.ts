import { http, HttpResponse } from 'msw'
import { describe, expect, it, vi } from 'vitest'
import { ref } from 'vue'
import { server } from '../../test/setup'
import { useRoomStore, type RoomEvent, type RoomRealtimePort, type RoomSnapshot } from './roomStore'

const roomId = '00000000-0000-0000-0000-000000000701'
const hostId = '00000000-0000-0000-0000-000000000702'
const guestId = '00000000-0000-0000-0000-000000000703'
const snapshot: RoomSnapshot = {
  roomId,
  code: '123456',
  title: '친구들과 한 판',
  visibility: 'PUBLIC',
  gameType: 'LIAR',
  status: 'WAITING',
  passwordProtected: false,
  participantCount: 1,
  maxParticipants: 10,
  hostId,
  sequence: 1,
  rounds: 3,
  actionSeconds: 30,
  discussionSeconds: 90,
  categoryPack: 'all',
  participants: [{ actorId: hostId, nickname: '방장감자', ready: false, spectator: false }],
}

function event(sequence: number, type: string, payload: Record<string, unknown>): RoomEvent {
  return {
    version: 1,
    eventId: crypto.randomUUID(),
    requestId: crypto.randomUUID(),
    roomId,
    actorId: guestId,
    type,
    sequence,
    occurredAt: '2026-08-23T00:00:00Z',
    payload,
  }
}

function realtimeFake(order: string[] = []) {
  const handlers = new Map<string, (payload: unknown) => void>()
  const state = ref<'disconnected' | 'connecting' | 'connected' | 'reconnecting' | 'failed'>('disconnected')
  const stateHandlers = new Set<(value: typeof state.value) => void>()
  const published: Array<{ destination: string; body: string }> = []
  const realtime: RoomRealtimePort = {
    connectionState: state,
    subscribe(destination, handler) {
      order.push(destination)
      handlers.set(destination, handler)
      return () => handlers.delete(destination)
    },
    subscribeConnectionState(handler) {
      stateHandlers.add(handler)
      return () => stateHandlers.delete(handler)
    },
    async connect() {
      order.push('connect')
      state.value = 'connected'
      stateHandlers.forEach(handler => handler('connected'))
    },
    publish(destination, body) { published.push({ destination, body }) },
  }
  return {
    realtime,
    handlers,
    published,
    emitState(value: typeof state.value) {
      state.value = value
      stateHandlers.forEach(handler => handler(value))
    },
  }
}

describe('roomStore', () => {
  it('subscribes to public and private room events before loading the authoritative snapshot', async () => {
    const order: string[] = []
    const fake = realtimeFake(order)
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => {
        order.push('join')
        return HttpResponse.json(snapshot)
      }),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        order.push('snapshot')
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(order).toEqual([
      'join',
      `/topic/rooms/${roomId}`,
      `/user/queue/rooms/${roomId}`,
      'connect',
      'snapshot',
    ])
    expect(store.connection).toBe('connected')
  })

  it('replays an event received in the subscription-to-snapshot race window', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(2, 'PLAYER_JOINED', {
          actorId: guestId, nickname: '참가감자', ready: false, spectator: false,
        }))
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(store.snapshot?.participants.map(participant => participant.nickname)).toEqual(['방장감자', '참가감자'])
    expect(store.snapshot?.sequence).toBe(2)
  })

  it('recovers a skipped event and reloads again after reconnect subscriptions are restored', async () => {
    let snapshots = 0
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        snapshots += 1
        return HttpResponse.json({ ...snapshot, sequence: snapshots === 1 ? 1 : 4, participantCount: 2,
          participants: snapshots === 1 ? snapshot.participants : [...snapshot.participants, { actorId: guestId, nickname: '복구감자', ready: false, spectator: false }],
        })
      }),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(4, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: true }))
    expect(snapshots).toBe(2)
    expect(store.snapshot?.participants).toHaveLength(2)

    fake.emitState('reconnecting')
    fake.emitState('connected')
    await vi.waitFor(() => expect(snapshots).toBe(3))
    expect(store.connection).toBe('connected')
  })

  it('applies settings as a host-authoritative event and resets every ready flag', async () => {
    const fake = realtimeFake()
    const readySnapshot = { ...snapshot, participants: [{ ...snapshot.participants[0]!, ready: true }] }
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(readySnapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(readySnapshot)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(2, 'ROOM_SETTINGS_UPDATED', {
      gameType: 'CHOSUNG', maxParticipants: 12, rounds: 5, actionSeconds: 20,
      discussionSeconds: 60, categoryPack: 'food',
    }))

    expect(store.snapshot?.gameType).toBe('CHOSUNG')
    expect(store.snapshot?.rounds).toBe(5)
    expect(store.snapshot?.participants[0]?.ready).toBe(false)
  })

  it('uses canonical unique request IDs for ready, chat, settings, and leave commands', async () => {
    const fake = realtimeFake()
    let leaveRequestId = ''
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
      http.post(`/api/v1/rooms/${roomId}/leave`, ({ request }) => {
        leaveRequestId = request.headers.get('X-Request-Id') ?? ''
        return new HttpResponse(null, { status: 204 })
      }),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    store.sendReady(true)
    store.sendChat('안녕하세요')
    store.updateSettings({ gameType: 'LIAR', maxParticipants: 8, rounds: 4, actionSeconds: 20, discussionSeconds: 60, categoryPack: 'all' })
    await store.leave()

    const requestIds = fake.published.map(item => JSON.parse(item.body).requestId as string).concat(leaveRequestId)
    expect(new Set(requestIds).size).toBe(4)
    expect(requestIds.every(id => /^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/.test(id))).toBe(true)
    expect(fake.published.map(item => JSON.parse(item.body).type)).toEqual(['PLAYER_READY', 'CHAT_SEND', 'ROOM_SETTINGS_UPDATE'])
  })

  it('keeps password material out of room state and surfaces same-sequence private rejection', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json({ ...snapshot, password: 'secret', passwordHash: 'hash' })),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json({ ...snapshot, password: 'secret', passwordHash: 'hash' })),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)
    fake.handlers.get(`/user/queue/rooms/${roomId}`)?.(event(1, 'COMMAND_REJECTED', { code: 'CHAT_RATE_LIMITED' }))

    expect('password' in (store.snapshot as object)).toBe(false)
    expect('passwordHash' in (store.snapshot as object)).toBe(false)
    expect(store.commandError).toContain('채팅')
  })

  it('does not lose a same-sequence private rejection received during initial snapshot synchronization', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        fake.handlers.get(`/user/queue/rooms/${roomId}`)?.(
          event(1, 'COMMAND_REJECTED', { code: 'CHAT_RATE_LIMITED' }),
        )
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(store.commandError).toContain('채팅')
  })

  it('clears the previous room before a new code asks for a password', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
      http.post('/api/v1/rooms/654321/join', () => HttpResponse.json(
        { code: 'ROOM_PASSWORD_INVALID', message: '비밀번호가 올바르지 않습니다.', requestId: crypto.randomUUID() },
        { status: 400 },
      )),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await expect(store.join('654321', '', fake.realtime)).rejects.toMatchObject({ code: 'ROOM_PASSWORD_INVALID' })

    expect(store.snapshot).toBeNull()
    expect(store.passwordRequired).toBe(true)
  })
})

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
  canStart: false,
  participants: [{ actorId: hostId, nickname: '방장감자', ready: false, spectator: false }],
  chats: [],
  game: null,
}

function eventFor(eventRoomId: string, sequence: number, type: string, payload: Record<string, unknown>): RoomEvent {
  return {
    version: 1,
    eventId: crypto.randomUUID(),
    requestId: crypto.randomUUID(),
    roomId: eventRoomId,
    actorId: guestId,
    type,
    sequence,
    occurredAt: '2026-08-23T00:00:00Z',
    payload,
  }
}

function event(sequence: number, type: string, payload: Record<string, unknown>): RoomEvent {
  return eventFor(roomId, sequence, type, payload)
}

function realtimeFake(order: string[] = []) {
  const handlers = new Map<string, (payload: unknown) => void | Promise<void>>()
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
  const publicLiarState = {
    gameType: 'LIAR', round: 1, phase: 'ROLE_REVEAL', deadlineAt: '2026-08-24T00:00:05Z',
    hints: [], submittedPlayerIds: [], scores: { [hostId]: 0 },
  }

  const citizenPrivateState = { role: 'CITIZEN', category: '음식', word: '붕어빵', hintSubmitted: false, voteSubmitted: false }
  const liarPrivateState = { role: 'LIAR', category: '음식', hintSubmitted: false, voteSubmitted: false }

  async function joinRoom(fake: ReturnType<typeof realtimeFake>): Promise<ReturnType<typeof useRoomStore>> {
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)
    return store
  }

  it('keeps private role data out of public state replacement', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: { ...publicLiarState, role: 'LIAR', word: '비밀' } }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))

    expect(store.snapshot?.game?.publicState).not.toHaveProperty('role')
    expect(store.snapshot?.game?.publicState).not.toHaveProperty('word')
    expect(store.snapshot?.game?.privateState).toMatchObject(liarPrivateState)
  })

  it('retains only explicit public-game fields from a malicious nested payload', async () => {
    const store = await joinRoom(realtimeFake())
    const allowedHint = { playerId: guestId, text: '따뜻해요' }
    const malicious = {
      ...publicLiarState,
      hints: [{ ...allowedHint, role: 'LIAR', word: '비밀 제시어', aliases: ['비밀별칭'] }],
      roundResult: { winner: 'CITIZENS', invalidated: false, liarGuessedCorrectly: false, targetActorId: guestId },
      role: 'LIAR', word: '비밀 제시어', aliases: ['비밀별칭'], voteTarget: guestId,
    }

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: malicious }))

    expect(store.snapshot?.game?.publicState).toEqual({ ...publicLiarState, hints: [allowedHint], roundResult: {
      winner: 'CITIZENS', invalidated: false, liarGuessedCorrectly: false,
    } })
    expect(JSON.stringify(store.snapshot?.game?.publicState)).not.toContain('비밀')
    expect(JSON.stringify(store.snapshot?.game?.publicState)).not.toContain('targetActorId')
  })

  it('merges a same-sequence private sidecar received before its public game state', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: citizenPrivateState }))
    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))

    expect(store.snapshot?.sequence).toBe(2)
    expect(store.snapshot?.game).toMatchObject({ publicState: publicLiarState, privateState: citizenPrivateState })
  })

  it('does not require a second public sequence for a same-sequence private sidecar or duplicate delivery', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))
    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: { ...publicLiarState, phase: 'HINTING' } }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: citizenPrivateState }))

    expect(store.snapshot?.sequence).toBe(2)
    expect(store.snapshot?.game).toMatchObject({ publicState: publicLiarState, privateState: liarPrivateState })
  })

  it('ignores a stale private sidecar after a newer game state has arrived', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))
    await store.applyPublicEvent(event(3, 'GAME_STATE_CHANGED', { game: { ...publicLiarState, phase: 'HINTING' } }))
    await store.applyPrivateEvent(event(3, 'GAME_PRIVATE_STATE_CHANGED', { game: citizenPrivateState }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))

    expect(store.snapshot?.game).toMatchObject({ publicState: { phase: 'HINTING' }, privateState: citizenPrivateState })
  })

  it('merges a private sidecar after a later non-game public event', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))
    await store.applyPublicEvent(event(3, 'CHAT_MESSAGE', {
      messageId: 'game-sidecar-gap', actorId: guestId, nickname: '참가감자', body: '대화', sentAt: '2026-08-24T00:00:01Z',
    }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))

    expect(store.snapshot?.sequence).toBe(3)
    expect(store.snapshot?.game).toMatchObject({ publicState: publicLiarState, privateState: liarPrivateState })
  })

  it('replaces both game projections from the reconnect REST snapshot', async () => {
    const fake = realtimeFake()
    let snapshotRequests = 0
    const recovered = {
      ...snapshot,
      status: 'PLAYING',
      sequence: 4,
      canStart: true,
      game: { publicState: { ...publicLiarState, phase: 'HINTING' }, privateState: citizenPrivateState },
    }
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(++snapshotRequests === 1 ? snapshot : recovered)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    fake.emitState('reconnecting')
    fake.emitState('connected')
    await vi.waitFor(() => expect(store.snapshot?.sequence).toBe(4))

    expect(store.snapshot?.game).toMatchObject(recovered.game)
    expect(store.snapshot?.canStart).toBe(true)
  })

  it('retains canStart from REST snapshots and updates it from room events', async () => {
    const fake = realtimeFake()
    const joinSnapshot = { ...snapshot, canStart: true }
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(joinSnapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(joinSnapshot)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await store.applyPublicEvent(event(2, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: true, canStart: false }))
    await store.applyPublicEvent(event(3, 'ROOM_SETTINGS_UPDATED', {
      gameType: 'LIAR', maxParticipants: 10, rounds: 3, actionSeconds: 30, discussionSeconds: 90, categoryPack: 'all', canStart: true,
    }))

    expect(store.snapshot?.canStart).toBe(true)
  })

  it('removes game state when the server returns the room to waiting', async () => {
    const store = await joinRoom(realtimeFake())
    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))
    await store.applyPrivateEvent(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))

    await store.applyPublicEvent(event(3, 'GAME_STATE_CHANGED', { game: null }))

    expect(store.snapshot?.game).toBeNull()
    expect(store.snapshot?.status).toBe('WAITING')
  })

  it('marks the room as playing when a public game snapshot arrives', async () => {
    const store = await joinRoom(realtimeFake())

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))

    expect(store.snapshot?.status).toBe('PLAYING')
  })

  it('reloads the authoritative snapshot instead of applying an unknown game phase', async () => {
    const fake = realtimeFake()
    let snapshotRequests = 0
    const recovered = {
      ...snapshot,
      status: 'PLAYING',
      sequence: 3,
      game: { publicState: publicLiarState, privateState: citizenPrivateState },
    }
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(++snapshotRequests === 1 ? snapshot : recovered)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await store.applyPublicEvent(event(2, 'GAME_STATE_CHANGED', {
      game: { ...publicLiarState, phase: 'UNSUPPORTED_PHASE' },
    }))

    expect(snapshotRequests).toBe(2)
    expect(store.snapshot).toMatchObject({ sequence: 3, game: recovered.game })
  })

  it('publishes typed start and action envelopes', async () => {
    const fake = realtimeFake()
    const store = await joinRoom(fake)

    store.startGame()
    store.sendGameAction('HINT_SUBMIT', { text: '따뜻해요' })
    store.sendGameAction('RETURN_TO_WAITING', {})

    expect(fake.published.map(item => JSON.parse(item.body))).toMatchObject([
      { type: 'GAME_START', payload: {} },
      { type: 'GAME_ACTION', payload: { action: 'HINT_SUBMIT', data: { text: '따뜻해요' } } },
      { type: 'GAME_ACTION', payload: { action: 'RETURN_TO_WAITING', data: {} } },
    ])
  })

  it('replaces duplicate synchronization events by sequence before replaying them', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        void fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(2, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: false, canStart: false }))
        void fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(2, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: true, canStart: true }))
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(store.snapshot?.participants[0]?.ready).toBe(true)
    expect(store.snapshot?.canStart).toBe(true)
  })

  it('fails recovery rather than growing an unbounded synchronization buffer', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        for (let sequence = 2; sequence <= 102; sequence += 1) {
          void fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(sequence, 'CHAT_MESSAGE', {
            messageId: `overflow-${sequence}`, actorId: guestId, nickname: '참가감자', body: '대화', sentAt: '2026-08-24T00:00:01Z',
          }))
        }
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await expect(store.join('123456', '', fake.realtime)).rejects.toThrow('이벤트가 너무 많이 누적')
    expect(store.connection).toBe('failed')
  })

  it('does not retain cross-room private sidecars during synchronization', async () => {
    const fake = realtimeFake()
    const otherRoomId = '00000000-0000-0000-0000-000000000799'
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        for (let sequence = 2; sequence <= 102; sequence += 1) {
          void fake.handlers.get(`/user/queue/rooms/${roomId}`)?.(
            eventFor(otherRoomId, sequence, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }),
          )
        }
        void fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(2, 'GAME_STATE_CHANGED', { game: publicLiarState }))
        void fake.handlers.get(`/user/queue/rooms/${roomId}`)?.(event(2, 'GAME_PRIVATE_STATE_CHANGED', { game: liarPrivateState }))
        return HttpResponse.json(snapshot)
      }),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(store.connection).toBe('connected')
    expect(store.snapshot?.game).toMatchObject({ publicState: publicLiarState, privateState: liarPrivateState })
  })

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

  it('restores allow-listed chat history from the participant snapshot on reload', async () => {
    const fake = realtimeFake()
    const history = [{
      messageId: 'history-1', actorId: guestId, nickname: '참가감자', body: '이전 대화',
      sentAt: '2026-08-23T00:00:00Z',
    }]
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json({ ...snapshot, chats: history, password: 'secret' })),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json({
        ...snapshot, chats: history, passwordHash: 'argon-secret',
      })),
    )
    const store = useRoomStore()

    await store.join('123456', '', fake.realtime)

    expect(store.chats).toEqual(history)
    expect(store.snapshot).not.toHaveProperty('password')
    expect(store.snapshot).not.toHaveProperty('passwordHash')
  })

  it('replaces stale chat state with gap recovery history and deduplicates later delivery by messageId', async () => {
    const fake = realtimeFake()
    let snapshots = 0
    const first = { messageId: 'chat-1', actorId: hostId, nickname: '방장감자', body: '첫 대화', sentAt: '2026-08-23T00:00:00Z' }
    const recovered = { messageId: 'chat-2', actorId: guestId, nickname: '참가감자', body: '복구 대화', sentAt: '2026-08-23T00:00:02Z' }
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json({ ...snapshot, chats: [first] })),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        snapshots += 1
        return HttpResponse.json(snapshots === 1
          ? { ...snapshot, chats: [first] }
          : { ...snapshot, sequence: 4, chats: [recovered] })
      }),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(4, 'CHAT_MESSAGE', recovered))
    await fake.handlers.get(`/topic/rooms/${roomId}`)?.(event(5, 'CHAT_MESSAGE', recovered))

    expect(snapshots).toBe(2)
    expect(store.chats).toEqual([recovered])
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

  it('buffers a newer public event while the reconnect snapshot is delayed and replays it afterward', async () => {
    let snapshotRequests = 0
    let releaseReconnect!: () => void
    const reconnectGate = new Promise<void>(resolve => { releaseReconnect = resolve })
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, async () => {
        snapshotRequests += 1
        if (snapshotRequests === 2) await reconnectGate
        return HttpResponse.json(snapshotRequests === 2 ? { ...snapshot, title: '복구된 방' } : snapshot)
      }),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    fake.emitState('reconnecting')
    fake.emitState('connected')
    await vi.waitFor(() => expect(snapshotRequests).toBe(2))
    await fake.handlers.get(`/topic/rooms/${roomId}`)?.(
      event(2, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: true }),
    )
    await fake.handlers.get(`/user/queue/rooms/${roomId}`)?.(
      event(1, 'COMMAND_REJECTED', { code: 'CHAT_RATE_LIMITED' }),
    )
    releaseReconnect()

    await vi.waitFor(() => expect(store.snapshot?.title).toBe('복구된 방'))
    expect(store.snapshot?.sequence).toBe(2)
    expect(store.snapshot?.participants[0]?.ready).toBe(true)
    expect(store.commandError).toContain('채팅')
  })

  it('keeps a failed gap recovery handled and visible instead of rejecting the realtime callback', async () => {
    let snapshotRequests = 0
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => {
        snapshotRequests += 1
        if (snapshotRequests === 1) return HttpResponse.json(snapshot)
        return HttpResponse.json(
          { code: 'HTTP_ERROR', message: '방 상태를 다시 불러오지 못했습니다.', requestId: crypto.randomUUID() },
          { status: 500 },
        )
      }),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await expect(fake.handlers.get(`/topic/rooms/${roomId}`)?.(
      event(4, 'PLAYER_READY_CHANGED', { actorId: hostId, ready: true }),
    )).resolves.toBeUndefined()

    expect(store.error).toContain('다시 불러오지 못했습니다')
    expect(store.connection).toBe('failed')
    expect(store.snapshot?.sequence).toBe(1)
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

  it('converts synchronous and asynchronous offline publish failures into a stable inline error', async () => {
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)
    fake.realtime.publish = () => { throw new Error('socket closed') }

    expect(() => store.sendChat('동기 실패')).not.toThrow()
    expect(store.commandError).toContain('실시간')

    fake.realtime.publish = () => Promise.reject(new Error('late socket failure'))
    store.commandError = null
    store.sendReady(true)
    await vi.waitFor(() => expect(store.commandError).toContain('실시간'))
  })

  it('waits for a server-applied stale join response, leaves that room, and only then joins the newest code', async () => {
    const firstRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000711', code: '111111', title: '느린 방' }
    const secondRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000722', code: '222222', title: '빠른 방' }
    let releaseFirst!: () => void
    const firstGate = new Promise<void>(resolve => { releaseFirst = resolve })
    let firstStarted = false
    let firstAborted = false
    let secondStarted = false
    let cleanupRequestId = ''
    const lifecycle: string[] = []
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/111111/join', async ({ request }) => {
        firstStarted = true
        lifecycle.push('first-applied')
        request.signal.addEventListener('abort', () => { firstAborted = true })
        await firstGate
        lifecycle.push('first-response')
        return HttpResponse.json(firstRoom)
      }),
      http.post(`/api/v1/rooms/${firstRoom.roomId}/leave`, ({ request }) => {
        cleanupRequestId = request.headers.get('X-Request-Id') ?? ''
        lifecycle.push('first-cleanup')
        return new HttpResponse(null, { status: 204 })
      }),
      http.post('/api/v1/rooms/222222/join', () => {
        secondStarted = true
        lifecycle.push('second-join')
        return HttpResponse.json(secondRoom)
      }),
      http.get(`/api/v1/rooms/${firstRoom.roomId}/snapshot`, () => HttpResponse.json(firstRoom)),
      http.get(`/api/v1/rooms/${secondRoom.roomId}/snapshot`, () => HttpResponse.json(secondRoom)),
    )
    const store = useRoomStore()

    const first = store.join('111111', '', fake.realtime)
    await vi.waitFor(() => expect(firstStarted).toBe(true))
    const second = store.join('222222', '', fake.realtime)
    const firstAbortedBeforeRelease = firstAborted
    const secondStartedBeforeRelease = secondStarted
    releaseFirst()
    await Promise.all([first, second])

    expect(firstAbortedBeforeRelease).toBe(false)
    expect(secondStartedBeforeRelease).toBe(false)
    expect(store.snapshot?.code).toBe('222222')
    expect(lifecycle).toEqual(['first-applied', 'first-response', 'first-cleanup', 'second-join'])
    expect(cleanupRequestId).toMatch(/^[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}$/)
    expect(fake.handlers.has(`/topic/rooms/${firstRoom.roomId}`)).toBe(false)
    expect(fake.handlers.has(`/topic/rooms/${secondRoom.roomId}`)).toBe(true)
  })

  it('keeps a failed current-room cleanup visible and blocks the next join until an explicit retry succeeds', async () => {
    const nextRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000733', code: '333333', title: '다음 방' }
    let cleanupAttempts = 0
    let nextJoinAttempts = 0
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
      http.post(`/api/v1/rooms/${roomId}/leave`, () => {
        cleanupAttempts += 1
        if (cleanupAttempts === 1) return HttpResponse.json(
          { code: 'HTTP_ERROR', message: '이전 방을 정리하지 못했습니다.', requestId: crypto.randomUUID() },
          { status: 500 },
        )
        return new HttpResponse(null, { status: 204 })
      }),
      http.post('/api/v1/rooms/333333/join', () => {
        nextJoinAttempts += 1
        return HttpResponse.json(nextRoom)
      }),
      http.get(`/api/v1/rooms/${nextRoom.roomId}/snapshot`, () => HttpResponse.json(nextRoom)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await expect(store.join('333333', '', fake.realtime)).rejects.toThrow('정리하지 못했습니다')

    expect(store.snapshot?.code).toBe('123456')
    expect(store.error).toContain('정리하지 못했습니다')
    expect(nextJoinAttempts).toBe(0)

    await store.join('333333', '', fake.realtime)

    expect(cleanupAttempts).toBe(2)
    expect(nextJoinAttempts).toBe(1)
    expect(store.snapshot?.code).toBe('333333')
  })

  it('does not proceed with a queued join when stale-response cleanup fails, then retries explicitly', async () => {
    const firstRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000755', code: '555555' }
    const nextRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000766', code: '666666' }
    let releaseFirst!: () => void
    const firstGate = new Promise<void>(resolve => { releaseFirst = resolve })
    let firstStarted = false
    let cleanupAttempts = 0
    let nextJoinAttempts = 0
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/555555/join', async () => {
        firstStarted = true
        await firstGate
        return HttpResponse.json(firstRoom)
      }),
      http.post(`/api/v1/rooms/${firstRoom.roomId}/leave`, () => {
        cleanupAttempts += 1
        if (cleanupAttempts === 1) return HttpResponse.json(
          { code: 'HTTP_ERROR', message: '느린 입장 정리에 실패했습니다.', requestId: crypto.randomUUID() },
          { status: 500 },
        )
        return new HttpResponse(null, { status: 204 })
      }),
      http.post('/api/v1/rooms/666666/join', () => {
        nextJoinAttempts += 1
        return HttpResponse.json(nextRoom)
      }),
      http.get(`/api/v1/rooms/${nextRoom.roomId}/snapshot`, () => HttpResponse.json(nextRoom)),
    )
    const store = useRoomStore()

    const first = store.join('555555', '', fake.realtime)
    await vi.waitFor(() => expect(firstStarted).toBe(true))
    const queued = store.join('666666', '', fake.realtime)
    releaseFirst()
    const results = await Promise.allSettled([first, queued])

    expect(results.map(result => result.status)).toEqual(['rejected', 'rejected'])
    expect(cleanupAttempts).toBe(1)
    expect(nextJoinAttempts).toBe(0)
    expect(store.error).toContain('정리에 실패했습니다')

    await store.join('666666', '', fake.realtime)

    expect(cleanupAttempts).toBe(2)
    expect(nextJoinAttempts).toBe(1)
    expect(store.snapshot?.code).toBe('666666')
  })

  it('continues a room transition when cleanup confirms the actor is already absent', async () => {
    const nextRoom = { ...snapshot, roomId: '00000000-0000-0000-0000-000000000744', code: '444444', title: '새 방' }
    let nextJoinAttempts = 0
    const fake = realtimeFake()
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({ headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf' })),
      http.post('/api/v1/rooms/123456/join', () => HttpResponse.json(snapshot)),
      http.get(`/api/v1/rooms/${roomId}/snapshot`, () => HttpResponse.json(snapshot)),
      http.post(`/api/v1/rooms/${roomId}/leave`, () => HttpResponse.json(
        { code: 'ROOM_PARTICIPANT_NOT_FOUND', message: '이미 퇴장한 참가자입니다.', requestId: crypto.randomUUID() },
        { status: 403 },
      )),
      http.post('/api/v1/rooms/444444/join', () => {
        nextJoinAttempts += 1
        return HttpResponse.json(nextRoom)
      }),
      http.get(`/api/v1/rooms/${nextRoom.roomId}/snapshot`, () => HttpResponse.json(nextRoom)),
    )
    const store = useRoomStore()
    await store.join('123456', '', fake.realtime)

    await store.join('444444', '', fake.realtime)

    expect(nextJoinAttempts).toBe(1)
    expect(store.snapshot?.code).toBe('444444')
    expect(store.error).toBeNull()
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
      http.post(`/api/v1/rooms/${roomId}/leave`, () => new HttpResponse(null, { status: 204 })),
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
    expect(fake.handlers.has(`/topic/rooms/${roomId}`)).toBe(false)
    expect(fake.handlers.has(`/user/queue/rooms/${roomId}`)).toBe(false)
  })
})

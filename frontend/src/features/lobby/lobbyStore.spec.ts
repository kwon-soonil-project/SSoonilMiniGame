import { describe, expect, it } from 'vitest'
import { http, HttpResponse } from 'msw'
import { server } from '../../test/setup'
import { useLobbyStore, type LobbyRoom } from './lobbyStore'

const waitingRoom: LobbyRoom = {
  roomId: 'room-1',
  code: '123456',
  title: '라이어 모임',
  gameType: 'LIAR',
  status: 'WAITING',
  passwordProtected: true,
  participantCount: 3,
  maxParticipants: 8,
  hostNickname: '방장감자',
  sequence: 1,
}

describe('lobbyStore', () => {
  it('loads rooms using the search, game, and available filters', async () => {
    server.use(http.get('/api/v1/lobby/rooms', ({ request }) => {
      const url = new URL(request.url)
      expect(url.searchParams.get('query')).toBe('라이어')
      expect(url.searchParams.get('gameType')).toBe('LIAR')
      expect(url.searchParams.get('available')).toBe('true')
      return HttpResponse.json([waitingRoom])
    }))
    const store = useLobbyStore()
    store.filters.query = '라이어'
    store.filters.gameType = 'LIAR'

    await store.loadRooms()

    expect(store.rooms).toEqual([waitingRoom])
  })

  it('keeps only the boolean password flag from a lobby response', async () => {
    server.use(http.get('/api/v1/lobby/rooms', () => HttpResponse.json([
      { ...waitingRoom, password: 'server-mistake', passwordHash: 'never-store-this' },
    ])))
    const store = useLobbyStore()

    await store.loadRooms()

    expect(store.rooms).toEqual([waitingRoom])
    expect('password' in store.rooms[0]!).toBe(false)
    expect('passwordHash' in store.rooms[0]!).toBe(false)
  })

  it('keeps a REST failure in store state without rejecting view initialization', async () => {
    server.use(http.get('/api/v1/lobby/rooms', () => HttpResponse.json(
      { code: 'HTTP_ERROR', message: '잠시 후 다시 시도해 주세요.', requestId: 'request-500' },
      { status: 500 },
    )))
    const store = useLobbyStore()

    await expect(store.initialize({
      subscribeLobby: () => () => undefined,
      connect: async () => undefined,
    })).resolves.toBeUndefined()
    expect(store.error).toBe('잠시 후 다시 시도해 주세요.')
  })

  it('creates a room without exposing password material in client state', async () => {
    server.use(
      http.get('/api/v1/csrf', () => HttpResponse.json({
        headerName: 'X-XSRF-TOKEN', parameterName: '_csrf', token: 'csrf-token',
      })),
      http.post('/api/v1/rooms', async ({ request }) => {
        expect(await request.json()).toEqual({
          title: '초성퀴즈 한판',
          visibility: 'PUBLIC',
          password: '안전한번호',
          gameType: 'CHOSUNG',
        })
        return HttpResponse.json({
          ...waitingRoom,
          title: '초성퀴즈 한판',
          visibility: 'PUBLIC',
          gameType: 'CHOSUNG',
          passwordProtected: true,
          password: '안전한번호',
          passwordHash: 'never-store-this',
        }, { status: 201 })
      }),
    )
    const store = useLobbyStore()

    const created = await store.createRoom({
      title: '초성퀴즈 한판',
      visibility: 'PUBLIC',
      password: '안전한번호',
      gameType: 'CHOSUNG',
    })

    expect(created.passwordProtected).toBe(true)
    expect('password' in created).toBe(false)
    expect('passwordHash' in created).toBe(false)
  })

  it('applies lobby upsert and remove events incrementally', () => {
    const store = useLobbyStore()
    store.applyLobbyEvent({
      version: 1,
      eventId: 'event-1',
      requestId: 'request-1',
      roomId: 'room-1',
      actorId: 'guest-1',
      type: 'LOBBY_ROOM_UPSERT',
      sequence: 2,
      occurredAt: '2026-08-23T00:00:00Z',
      payload: waitingRoom,
    })
    expect(store.rooms).toEqual([{ ...waitingRoom, sequence: 2 }])

    store.applyLobbyEvent({
      version: 1,
      eventId: 'event-2',
      requestId: 'request-2',
      roomId: 'room-1',
      actorId: 'guest-1',
      type: 'LOBBY_ROOM_REMOVE',
      sequence: 3,
      occurredAt: '2026-08-23T00:00:01Z',
      payload: { roomId: 'room-1' },
    })
    expect(store.rooms).toEqual([])
  })

  it('subscribes before REST and merges buffered newer events over the snapshot', async () => {
    let lobbyHandler: ((event: unknown) => void) | null = null
    server.use(http.get('/api/v1/lobby/rooms', () => HttpResponse.json([
      { ...waitingRoom, participantCount: 1, sequence: 1 },
    ])))
    const store = useLobbyStore()

    await store.initialize({
      subscribeLobby: (handler) => {
        lobbyHandler = handler
        return () => undefined
      },
      connect: async () => {
        lobbyHandler?.({
          version: 1,
          eventId: 'event-buffered',
          requestId: 'request-buffered',
          roomId: waitingRoom.roomId,
          actorId: 'guest-2',
          type: 'LOBBY_ROOM_UPSERT',
          sequence: 2,
          occurredAt: '2026-08-23T00:00:01Z',
          payload: { ...waitingRoom, participantCount: 2, sequence: 2 },
        })
      },
    })

    expect(store.rooms[0]?.participantCount).toBe(2)
    expect(store.rooms[0]?.sequence).toBe(2)
  })

  it('reloads the authoritative snapshot when a realtime sequence gap is detected', async () => {
    let requests = 0
    server.use(http.get('/api/v1/lobby/rooms', () => {
      requests += 1
      return HttpResponse.json([{
        ...waitingRoom,
        participantCount: requests === 1 ? 1 : 4,
        sequence: requests === 1 ? 1 : 4,
      }])
    }))
    const store = useLobbyStore()
    await store.loadRooms()

    await store.applyLobbyEvent({
      version: 1,
      eventId: 'event-gap',
      requestId: 'request-gap',
      roomId: waitingRoom.roomId,
      actorId: 'guest-4',
      type: 'LOBBY_ROOM_UPSERT',
      sequence: 4,
      occurredAt: '2026-08-23T00:00:04Z',
      payload: { ...waitingRoom, participantCount: 2, sequence: 4 },
    })

    expect(requests).toBe(2)
    expect(store.rooms[0]?.participantCount).toBe(4)
  })

  it('removes realtime upserts that no longer match the selected game filter', async () => {
    const store = useLobbyStore()
    store.rooms = [waitingRoom]
    store.filters.gameType = 'DRAWING'

    await store.applyLobbyEvent({
      version: 1,
      eventId: 'event-filter', requestId: 'request-filter', roomId: waitingRoom.roomId,
      actorId: 'guest-1', type: 'LOBBY_ROOM_UPSERT', sequence: 2,
      occurredAt: '2026-08-23T00:00:02Z', payload: { ...waitingRoom, sequence: 2 },
    })

    expect(store.rooms).toEqual([])
  })

  it('removes realtime upserts that become full while available-only is selected', async () => {
    const store = useLobbyStore()
    store.rooms = [waitingRoom]

    await store.applyLobbyEvent({
      version: 1,
      eventId: 'event-full', requestId: 'request-full', roomId: waitingRoom.roomId,
      actorId: 'guest-1', type: 'LOBBY_ROOM_UPSERT', sequence: 2,
      occurredAt: '2026-08-23T00:00:02Z',
      payload: { ...waitingRoom, participantCount: waitingRoom.maxParticipants, sequence: 2 },
    })

    expect(store.rooms).toEqual([])
  })
})

import { defineStore } from 'pinia'
import { reactive, ref } from 'vue'
import { apiRequest } from '../../shared/api/apiClient'
import { realtimeClient, type RealtimeClient } from '../../shared/realtime/realtimeClient'

type LobbyRealtimePort = Pick<RealtimeClient, 'subscribeLobby' | 'connect'>

export type GameType = 'LIAR' | 'DRAWING' | 'CHOSUNG' | 'MAJORITY'

export interface LobbyRoom {
  roomId: string
  code: string
  title: string
  gameType: GameType
  status: 'WAITING' | 'PLAYING'
  passwordProtected: boolean
  participantCount: number
  maxParticipants: number
  hostNickname: string
  sequence: number
}

export interface CreateRoomInput {
  title: string
  visibility: 'PUBLIC' | 'PRIVATE'
  password?: string
  gameType: GameType
}

export interface CreatedRoom {
  roomId: string
  code: string
  title: string
  visibility: 'PUBLIC' | 'PRIVATE'
  gameType: GameType
  status: 'WAITING' | 'PLAYING'
  passwordProtected: boolean
  participantCount: number
  maxParticipants: number
  sequence: number
}

export interface LobbyEvent {
  version: number
  eventId: string
  requestId: string
  roomId: string
  actorId: string
  type: string
  sequence: number
  occurredAt: string
  payload: unknown
}

export const useLobbyStore = defineStore('lobby', () => {
  const rooms = ref<LobbyRoom[]>([])
  const filters = reactive<{ query: string; gameType: GameType | ''; available: boolean }>({
    query: '',
    gameType: '',
    available: true,
  })
  const loading = ref(false)
  const creating = ref(false)
  const error = ref<string | null>(null)
  const lastSequences = new Map<string, number>()
  const bufferedEvents: LobbyEvent[] = []
  let unsubscribeLobby: (() => void) | null = null
  let synchronizing = false
  let refreshPromise: Promise<void> | null = null

  async function loadRooms(): Promise<void> {
    loading.value = true
    error.value = null
    const parameters = new URLSearchParams()
    if (filters.query.trim()) parameters.set('query', filters.query.trim())
    if (filters.gameType) parameters.set('gameType', filters.gameType)
    parameters.set('available', String(filters.available))
    try {
      const loaded = await apiRequest<unknown[]>(`/api/v1/lobby/rooms?${parameters}`)
      rooms.value = loaded.flatMap(value => {
        const room = lobbyRoomFrom(value)
        return room ? [room] : []
      }).sort(compareRooms)
      for (const room of rooms.value) lastSequences.set(room.roomId, room.sequence)
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '방 목록을 불러오지 못했습니다.'
      throw cause
    } finally {
      loading.value = false
    }
  }

  async function createRoom(input: CreateRoomInput): Promise<CreatedRoom> {
    creating.value = true
    error.value = null
    const normalizedPassword = input.password?.trim()
    const body = {
      title: input.title.trim(),
      visibility: input.visibility,
      ...(normalizedPassword ? { password: normalizedPassword } : {}),
      gameType: input.gameType,
    }
    try {
      const response = await apiRequest<unknown>('/api/v1/rooms', {
        method: 'POST',
        body: JSON.stringify(body),
      })
      const created = createdRoomFrom(response)
      if (!created) throw new Error('방 생성 응답 형식이 잘못되었습니다.')
      return created
    } catch (cause) {
      error.value = cause instanceof Error ? cause.message : '방을 만들지 못했습니다.'
      throw cause
    } finally {
      creating.value = false
    }
  }

  async function applyLobbyEvent(rawEvent: unknown): Promise<void> {
    if (!isLobbyEvent(rawEvent)) return
    if (synchronizing) {
      bufferedEvents.push(rawEvent)
      return
    }
    const previousSequence = lastSequences.get(rawEvent.roomId)
    if (previousSequence !== undefined && rawEvent.sequence <= previousSequence) return
    if (previousSequence !== undefined && rawEvent.sequence > previousSequence + 1) {
      await refreshAuthoritativeSnapshot()
      return
    }
    lastSequences.set(rawEvent.roomId, rawEvent.sequence)

    if (rawEvent.type === 'LOBBY_ROOM_REMOVE') {
      rooms.value = rooms.value.filter(room => room.roomId !== rawEvent.roomId)
      return
    }
    if (rawEvent.type !== 'LOBBY_ROOM_UPSERT') return
    const payloadRoom = lobbyRoomFrom(rawEvent.payload)
    if (!payloadRoom) return
    const room = { ...payloadRoom, sequence: rawEvent.sequence }
    if (!matchesFilters(room, filters)) {
      rooms.value = rooms.value.filter(candidate => candidate.roomId !== room.roomId)
      return
    }
    const roomIndex = rooms.value.findIndex(candidate => candidate.roomId === room.roomId)
    if (roomIndex === -1) rooms.value.push(room)
    else rooms.value.splice(roomIndex, 1, room)
    rooms.value.sort(compareRooms)
  }

  async function initialize(client: LobbyRealtimePort = realtimeClient): Promise<void> {
    if (unsubscribeLobby) return
    synchronizing = true
    unsubscribeLobby = client.subscribeLobby(event => {
      void applyLobbyEvent(event)
    })
    try {
      await client.connect()
    } catch {
      error.value = '실시간 연결이 끊겼어요. 목록을 새로고침해 주세요.'
    }
    try {
      await loadRooms()
    } catch {
      // The view renders the store error and can retry without an unhandled promise.
    } finally {
      synchronizing = false
      await replayBufferedEvents()
    }
  }

  function dispose(): void {
    unsubscribeLobby?.()
    unsubscribeLobby = null
  }

  return { rooms, filters, loading, creating, error, loadRooms, createRoom, applyLobbyEvent, initialize, dispose }

  async function refreshAuthoritativeSnapshot(): Promise<void> {
    if (refreshPromise) return refreshPromise
    synchronizing = true
    refreshPromise = loadRooms().finally(async () => {
      synchronizing = false
      refreshPromise = null
      await replayBufferedEvents()
    })
    return refreshPromise
  }

  async function replayBufferedEvents(): Promise<void> {
    const pending = bufferedEvents.splice(0).sort((left, right) => left.sequence - right.sequence)
    for (const event of pending) await applyLobbyEvent(event)
  }
})

function compareRooms(left: LobbyRoom, right: LobbyRoom): number {
  if (left.status !== right.status) return left.status === 'WAITING' ? -1 : 1
  return left.title.localeCompare(right.title, 'ko')
}

function isLobbyEvent(value: unknown): value is LobbyEvent {
  if (typeof value !== 'object' || value === null) return false
  const candidate = value as Partial<LobbyEvent>
  return typeof candidate.roomId === 'string'
    && typeof candidate.type === 'string'
    && typeof candidate.sequence === 'number'
    && 'payload' in candidate
}

function lobbyRoomFrom(value: unknown): LobbyRoom | null {
  if (typeof value !== 'object' || value === null) return null
  const room = value as Partial<LobbyRoom>
  if (
    typeof room.roomId !== 'string'
    || typeof room.code !== 'string'
    || typeof room.title !== 'string'
    || !isGameType(room.gameType)
    || (room.status !== 'WAITING' && room.status !== 'PLAYING')
    || typeof room.passwordProtected !== 'boolean'
    || typeof room.participantCount !== 'number'
    || typeof room.maxParticipants !== 'number'
    || typeof room.hostNickname !== 'string'
    || typeof room.sequence !== 'number'
  ) return null
  return {
    roomId: room.roomId,
    code: room.code,
    title: room.title,
    gameType: room.gameType,
    status: room.status,
    passwordProtected: room.passwordProtected,
    participantCount: room.participantCount,
    maxParticipants: room.maxParticipants,
    hostNickname: room.hostNickname,
    sequence: room.sequence,
  }
}

function createdRoomFrom(value: unknown): CreatedRoom | null {
  if (typeof value !== 'object' || value === null) return null
  const room = value as Partial<CreatedRoom>
  if (
    typeof room.roomId !== 'string'
    || typeof room.code !== 'string'
    || typeof room.title !== 'string'
    || (room.visibility !== 'PUBLIC' && room.visibility !== 'PRIVATE')
    || !isGameType(room.gameType)
    || (room.status !== 'WAITING' && room.status !== 'PLAYING')
    || typeof room.passwordProtected !== 'boolean'
    || typeof room.participantCount !== 'number'
    || typeof room.maxParticipants !== 'number'
    || typeof room.sequence !== 'number'
  ) return null
  return {
    roomId: room.roomId,
    code: room.code,
    title: room.title,
    visibility: room.visibility,
    gameType: room.gameType,
    status: room.status,
    passwordProtected: room.passwordProtected,
    participantCount: room.participantCount,
    maxParticipants: room.maxParticipants,
    sequence: room.sequence,
  }
}

function matchesFilters(
  room: LobbyRoom,
  filters: { query: string; gameType: GameType | ''; available: boolean },
): boolean {
  const query = filters.query.trim().toLocaleLowerCase('ko')
  return (!query || room.title.toLocaleLowerCase('ko').includes(query))
    && (!filters.gameType || room.gameType === filters.gameType)
    && (!filters.available || room.participantCount < room.maxParticipants)
}

function isGameType(value: unknown): value is GameType {
  return value === 'LIAR' || value === 'DRAWING' || value === 'CHOSUNG' || value === 'MAJORITY'
}
